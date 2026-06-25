package com.monpai.sailboatmod.market.web.map;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 黑块检测 + 修复:扫描磁盘上已渲染的方形底图瓦片,定位"该 region 有存盘 chunk(本应有地形)却整张全黑"的
 * 瓦片,只对这些 region 触发离线增量重渲(startAreaRender),逐步把黑洞填回。
 *
 * <p>历史背景:刚修过的 region 永不增量写盘 bug 会留下全 {@code 0x00000000} 的黑瓦片在磁盘上;现有
 * repaintall/scan 是盲目区域重扫,不按像素内容定位黑洞。本类按像素判黑,且用 region 存盘情况交叉验证,
 * 避免对未生成区域(黑是对的)反复重渲。
 *
 * <p>线程安全:PNG 解码 + region 文件头解析都是磁盘 IO,放在守护线程跑(绝不碰 level.players());拿到
 * 黑洞 region 列表后用 {@code server.execute} 回主线程调 startAreaRender。
 */
public final class MarketWebMapBlackTileRepair {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** 一张 zoom-0 瓦片 = 32×32 chunk 格,每格 16×16 像素。按 chunk 格判黑,无需比例阈值。 */
    private static final int TILE_SIZE = MarketWebMapTileCoordinate.BASE_TILE_SIZE; // 512
    private static final int CELL = MarketWebMapConstants.CHUNK_SIZE;                // 16
    private static final int CELLS_PER_AXIS = TILE_SIZE / CELL;                      // 32

    private static final ExecutorService SCAN_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "marketweb-blacktile-repair");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean SCANNING = new AtomicBoolean(false);

    private MarketWebMapBlackTileRepair() {
    }

    /** 像素是否"黑/空":全透明(未写过的底图)或纯黑 RGB。注意 UNKNOWN_ARGB=0xFF2A2A2A 与水(饱和蓝)都不命中。 */
    static boolean isBlackPixel(int argb) {
        int alpha = (argb >>> 24) & 0xFF;
        int rgb = argb & 0x00FFFFFF;
        return alpha == 0 || rgb == 0x000000;
    }

    /**
     * 瓦片是否存在"整块全黑的 chunk 格"。把 512×512 切成 32×32 个 16×16 chunk 格,逐格检查:
     * 该格 256 像素若全部 isBlackPixel → 这是渲染漏填的黑块 → 整张瓦片需修。命中任意一格即返回 true。
     * <p>为何按格而非比例:行状黑块只占整张 30~60%,旧 0.98 比例阈值抓不到;而真实地形 chunk 几乎不可能
     * 16×16 全黑(深色方块也有亮度抖动),海洋=饱和蓝、未知色=0xFF2A2A2A 都非黑 → 不误伤。空/null 视为损坏。
     */
    static boolean hasBlackChunkCell(int[] pixels) {
        if (pixels == null || pixels.length != TILE_SIZE * TILE_SIZE) {
            return true;
        }
        for (int cellZ = 0; cellZ < CELLS_PER_AXIS; cellZ++) {
            for (int cellX = 0; cellX < CELLS_PER_AXIS; cellX++) {
                if (isCellAllBlack(pixels, cellX, cellZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 单个 16×16 chunk 格是否整块全黑。任一像素非黑即提前返回 false。 */
    private static boolean isCellAllBlack(int[] pixels, int cellX, int cellZ) {
        int baseX = cellX * CELL;
        int baseZ = cellZ * CELL;
        for (int z = 0; z < CELL; z++) {
            int rowOffset = (baseZ + z) * TILE_SIZE + baseX;
            for (int x = 0; x < CELL; x++) {
                if (!isBlackPixel(pixels[rowOffset + x])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static long packRegion(int regionX, int regionZ) {
        return MarketWebMapRegionWatcher.packRegion(regionX, regionZ);
    }

    /** 该世界 region 目录(level-free 路径解析,主线程调用后传入后台,避免后台触碰 level 状态)。 */
    private static Path regionDir(ServerLevel level) {
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("region")
                .normalize();
    }

    /**
     * 纯磁盘扫描:返回 budget 内"有存盘 chunk 却全黑"的瓦片 region 坐标 [regionX, regionZ] 列表。
     * 仅做文件读 + PNG 解码,可后台线程安全运行。
     */
    private static List<int[]> findBlackRegions(MarketWebMapTileCache cache, Path regionDir, int budgetTiles) {
        List<int[]> tiles = cache.listSquareTileCoords(0);
        if (tiles.isEmpty()) {
            return List.of();
        }
        // 扫足够多的 region 覆盖现有瓦片即可(留余量)。
        int regionCap = Math.max(64, tiles.size() + 64);
        Set<Long> saved = new HashSet<>();
        for (MarketWebMapRegionScanService.RegionFile region
                : MarketWebMapRegionScanService.scanRegionFilesByDir(regionDir, regionCap)) {
            saved.add(packRegion(region.regionX(), region.regionZ()));
        }
        List<int[]> holes = new ArrayList<>();
        int examined = 0;
        for (int[] tile : tiles) {
            if (budgetTiles > 0 && examined >= budgetTiles) {
                break;
            }
            examined++;
            int regionX = tile[0]; // zoom-0 tileX == regionX
            int regionZ = tile[1];
            if (!saved.contains(packRegion(regionX, regionZ))) {
                continue; // 未生成区域 → 黑是对的,跳过(否则会永远重渲)
            }
            int[] pixels = cache.readSquareTilePixels(MarketWebMapConstants.OVERWORLD, 0, regionX, regionZ);
            if (hasBlackChunkCell(pixels)) {
                holes.add(new int[]{regionX, regionZ});
            }
        }
        return holes;
    }

    /** 对一批黑洞 region 触发离线增量重渲。必须在服务器主线程调用(startAreaRender 触碰 renderManager/level)。 */
    private static int queueRepairs(ServerLevel level, List<int[]> holes) {
        int queued = 0;
        for (int[] region : holes) {
            int regionX = region[0];
            int regionZ = region[1];
            boolean started = MarketWebMapRenderService.global().startAreaRender(
                    level,
                    regionX * MarketWebMapTileCoordinate.BASE_TILE_SIZE,
                    regionZ * MarketWebMapTileCoordinate.BASE_TILE_SIZE,
                    regionX * MarketWebMapTileCoordinate.BASE_TILE_SIZE + MarketWebMapTileCoordinate.BASE_TILE_SIZE - 1,
                    regionZ * MarketWebMapTileCoordinate.BASE_TILE_SIZE + MarketWebMapTileCoordinate.BASE_TILE_SIZE - 1);
            if (started) {
                queued++;
            }
        }
        return queued;
    }

    /**
     * 同步扫描 + 修复(供手动指令用,调用方已在服务器主线程)。返回入队重渲的黑洞 region 数。
     * budgetTiles &lt;= 0 表示全量。
     */
    public static int scanAndRepair(MinecraftServer server, ServerLevel level, int budgetTiles) {
        if (server == null || level == null) {
            return 0;
        }
        MarketWebMapTileCache cache = MarketWebMapTileCache.forServer(server);
        List<int[]> holes = findBlackRegions(cache, regionDir(level), budgetTiles);
        return queueRepairs(level, holes);
    }

    /**
     * 异步扫描 + 修复(供 tick() 自动调用)。磁盘解码在守护线程,startAreaRender 回主线程。
     * 同一时刻只跑一个扫描(SCANNING 互斥),已在跑则本次跳过。
     */
    public static void scanAndRepairAsync(MinecraftServer server, ServerLevel level, int budgetTiles) {
        if (server == null || level == null) {
            return;
        }
        if (!SCANNING.compareAndSet(false, true)) {
            return;
        }
        final MarketWebMapTileCache cache = MarketWebMapTileCache.forServer(server);
        final Path regionDir = regionDir(level); // 主线程预取路径,后台不再触碰 level
        SCAN_POOL.submit(() -> {
            try {
                List<int[]> holes = findBlackRegions(cache, regionDir, budgetTiles);
                if (!holes.isEmpty()) {
                    server.execute(() -> {
                        int queued = queueRepairs(level, holes);
                        if (queued > 0) {
                            LOGGER.info("Market web map black-tile repair queued {} region(s) for re-render", queued);
                        }
                    });
                }
            } catch (RuntimeException exception) {
                LOGGER.warn("Market web map black-tile repair scan failed", exception);
            } finally {
                SCANNING.set(false);
            }
        });
    }
}
