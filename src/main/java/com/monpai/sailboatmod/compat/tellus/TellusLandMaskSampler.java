package com.monpai.sailboatmod.compat.tellus;

import com.monpai.sailboatmod.route.terrain.LandMaskTiling;
import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import com.yucareux.tellus.world.data.mask.TellusLandMaskSource;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.TellusWorldgenSources;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 远航寻路专用的 Tellus 海陆取样:只读 Tellus 自己的陆地遮罩({@code samplePreloadTerrain} 判陆/海用的同一份资料),
 * 不碰高程/土地覆盖/完整解析度地形预取。按取样间距选遮罩层级({@link LandMaskTiling}),并在后台并行预取
 * 相邻瓦片(Tellus 本身逐瓦片同步下载,冷缓存时顺序下载是远航的主要等待)。只在后台线程调用。
 */
public final class TellusLandMaskSampler implements TerrainSurfaceSampler {
    private static final int PREFETCH_THREADS = 6;
    private static final int PREFETCH_QUEUE = 512;
    private static final ExecutorService PREFETCH = new ThreadPoolExecutor(PREFETCH_THREADS, PREFETCH_THREADS,
            30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(PREFETCH_QUEUE), runnable -> {
                Thread thread = new Thread(runnable, "sailboat-landmask-prefetch");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardPolicy());

    static {
        ((ThreadPoolExecutor) PREFETCH).allowCoreThreadTimeOut(true);
    }

    private final TellusLandMaskSource mask;
    private final double worldScale;
    private final int seaLevel;
    private final Set<Long> requestedTiles = ConcurrentHashMap.newKeySet();

    public TellusLandMaskSampler(ServerLevel level) {
        this.mask = TellusWorldgenSources.landMask();
        this.worldScale = ((EarthChunkGenerator) level.getChunkSource().getGenerator()).settings().worldScale();
        this.seaLevel = level.getSeaLevel();
    }

    /** 不触发下载,只用反射确认用到的 Tellus API 都还在。 */
    public static void checkApi() throws ReflectiveOperationException {
        TellusWorldgenSources.class.getMethod("landMask");
        TellusLandMaskSource.class.getMethod("sampleLandMask", double.class, double.class, double.class);
        TellusLandMaskSource.LandMaskSample.class.getMethod("known");
        TellusLandMaskSource.LandMaskSample.class.getMethod("land");
        TellusSurfaceSampler.checkApi();
    }

    @Override
    public Optional<SurfaceSample> sample(int x, int z, int blocksPerSample) {
        int k = LandMaskTiling.coarsening(blocksPerSample);
        prefetchAround(x, z, k);
        Boolean land = land(x, z, k);
        if (land == null) return Optional.empty();
        return Optional.of(land
                ? new SurfaceSample(seaLevel + 1, Blocks.STONE.defaultBlockState(), false, 0)
                : new SurfaceSample(seaLevel, Blocks.WATER.defaultBlockState(), true, 1));
    }

    private Boolean land(double x, double z, int k) {
        try {
            TellusLandMaskSource.LandMaskSample sample = mask.sampleLandMask((x + 0.5D) / k, (z + 0.5D) / k, worldScale * k);
            return sample.known() ? sample.land() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 第一次碰到某瓦片时,把它周围 8 张瓦片交给后台并行下载(进 Tellus 自己的磁盘/内存缓存)。 */
    private void prefetchAround(int x, int z, int k) {
        double tile = LandMaskTiling.tileBlocks(worldScale, LandMaskTiling.zoomFor(worldScale, k));
        long tx = (long) Math.floor(x / tile), tz = (long) Math.floor(z / tile);
        if (!requestedTiles.add(tileKey(k, tx, tz))) return;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                long nx = tx + dx, nz = tz + dz;
                if (!requestedTiles.add(tileKey(k, nx, nz))) continue;
                double cx = (nx + 0.5D) * tile, cz = (nz + 0.5D) * tile;
                PREFETCH.execute(() -> land(cx, cz, k)); // 丢弃满队列的请求无妨:真正用到时会同步下载
            }
        }
    }

    private static long tileKey(int k, long tx, long tz) {
        return ((long) Integer.numberOfTrailingZeros(Integer.highestOneBit(k)) << 56) ^ ((tx & 0xFFFFFFFL) << 28) ^ (tz & 0xFFFFFFFL);
    }
}
