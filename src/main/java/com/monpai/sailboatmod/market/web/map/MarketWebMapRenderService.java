package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class MarketWebMapRenderService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_QUEUE_TASKS = 4096;
    private static final int CHUNKS_PER_TICK = 3;
    private static final int PLAYER_SCAN_INTERVAL_TICKS = 80;
    private static final int POINT_SCAN_INTERVAL_TICKS = 400;
    private static final int REGION_SCAN_INTERVAL_TICKS = 1200;
    private static final int PLAYER_SCAN_RADIUS_CHUNKS = 3;
    private static final int POINT_SCAN_RADIUS_CHUNKS = 1;
    private static final int REGION_SCAN_REGIONS_PER_PASS = 4;
    private static final int CHUNKS_PER_REGION_AXIS = 32;
    /** 每 tick 最多强制加载多少个脏区块（与 RoadPlannerMapPreloadService 一致）。 */
    private static final int MAX_FORCE_CHUNKS_PER_TICK = 8;
    /** 待强制加载的脏区块上限，超出丢弃最旧的，避免无界增长。 */
    private static final int MAX_DIRTY_CHUNKS = 65536;
    private static final MarketWebMapRenderService GLOBAL = new MarketWebMapRenderService(
            new MarketWebMapRenderQueue(MAX_QUEUE_TASKS),
            new MarketWebLoadedChunkTileRenderer());

    private final MarketWebMapRenderQueue queue;
    private final MarketWebLoadedChunkTileRenderer renderer;
    private final MarketWebMapRegionScanService regionScanner;
    private final MarketWebMapRegionWatcher regionWatcher = new MarketWebMapRegionWatcher();
    /** 等待强制加载的脏区块（来自磁盘 region 监听），打包为 ChunkPos.asLong。 */
    private final LinkedHashSet<Long> dirtyChunks = new LinkedHashSet<>();
    /** 已经 forceChunk(true)、等待加载完成后采样并释放的区块。 */
    private final LinkedHashSet<Long> forcedChunks = new LinkedHashSet<>();
    private int tickCounter;

    public MarketWebMapRenderService(MarketWebMapRenderQueue queue, MarketWebLoadedChunkTileRenderer renderer) {
        this.queue = queue == null ? new MarketWebMapRenderQueue(MAX_QUEUE_TASKS) : queue;
        this.renderer = renderer == null ? new MarketWebLoadedChunkTileRenderer() : renderer;
        this.regionScanner = new MarketWebMapRegionScanService(this.queue);
    }

    public static MarketWebMapRenderService global() {
        return GLOBAL;
    }

    /** 服务器启动时调用：启动磁盘 region 文件监听。 */
    public void startRegionWatcher(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level != null) {
            regionWatcher.start(level);
        }
    }

    /** 服务器停止时调用：停止监听并释放仍被强制加载的区块。 */
    public void stopRegionWatcher(MinecraftServer server) {
        regionWatcher.stop();
        synchronized (dirtyChunks) {
            dirtyChunks.clear();
        }
        if (server != null) {
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            if (level != null) {
                releaseAllForcedChunks(level);
            }
        }
        forcedChunks.clear();
    }

    public void enqueueTileRequest(MinecraftServer server, String lod, int tileX, int tileZ) {
        if (server == null || !"lod_1".equals(lod)) {
            return;
        }
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }
        enqueueLoadedChunksInTile(level, tileX, tileZ, System.currentTimeMillis());
    }

    public void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }
        long now = System.currentTimeMillis();
        tickCounter++;
        if (tickCounter % PLAYER_SCAN_INTERVAL_TICKS == 0) {
            enqueuePlayerAreas(level, now);
        }
        if (tickCounter % POINT_SCAN_INTERVAL_TICKS == 0) {
            enqueueImportantPoints(level, now);
        }
        if (tickCounter % REGION_SCAN_INTERVAL_TICKS == 0) {
            regionScanner.enqueueRepairScan(level, REGION_SCAN_REGIONS_PER_PASS, now);
        }
        intakeDirtyRegions();
        processForcedChunks(level, now);
        processBudgeted(level, now);
    }

    public int queueSize() {
        return queue.size();
    }

    public int enqueueRegionRepairScan(ServerLevel level, int maxRegions) {
        return regionScanner.enqueueRepairScan(level, maxRegions, System.currentTimeMillis());
    }

    /** 取出监听器累积的脏 region，把其 32×32 区块塞入待强制加载队列。 */
    private void intakeDirtyRegions() {
        Set<Long> regions = regionWatcher.drainDirtyRegions();
        if (regions.isEmpty()) {
            return;
        }
        synchronized (dirtyChunks) {
            for (long packedRegion : regions) {
                int baseChunkX = MarketWebMapRegionWatcher.unpackRegionX(packedRegion) * CHUNKS_PER_REGION_AXIS;
                int baseChunkZ = MarketWebMapRegionWatcher.unpackRegionZ(packedRegion) * CHUNKS_PER_REGION_AXIS;
                for (int dz = 0; dz < CHUNKS_PER_REGION_AXIS; dz++) {
                    for (int dx = 0; dx < CHUNKS_PER_REGION_AXIS; dx++) {
                        dirtyChunks.add(ChunkPos.asLong(baseChunkX + dx, baseChunkZ + dz));
                    }
                }
            }
            while (dirtyChunks.size() > MAX_DIRTY_CHUNKS) {
                Iterator<Long> it = dirtyChunks.iterator();
                if (!it.hasNext()) {
                    break;
                }
                it.next();
                it.remove();
            }
        }
    }

    /**
     * 处理脏区块：已加载完成的强制加载区块 → 用 region-scan 质量重渲（可覆盖旧 client_upload/legacy 红水）→ 释放；
     * 仍未加载的脏区块 → forceChunk 触发加载（预算化，每 tick 限量）。
     */
    private void processForcedChunks(ServerLevel level, long nowMillis) {
        BlockPos owner = forceOwner(level);
        // 1. 已加载的强制区块：重渲并释放
        for (Iterator<Long> it = forcedChunks.iterator(); it.hasNext(); ) {
            long chunkKey = it.next();
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            if (!isChunkLoaded(level, chunkX, chunkZ)) {
                continue;
            }
            try {
                renderer.renderLoadedChunk(level, chunkX, chunkZ).ifPresent(pixels ->
                        MarketWebMapTileCache.forServer(level.getServer()).mergeChunkArgb(
                                MarketWebMapConstants.OVERWORLD,
                                chunkX, chunkZ, pixels,
                                MarketWebMapTileQuality.SERVER_REGION_SCAN,
                                nowMillis));
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to repaint dirty market web map chunk {},{}", chunkX, chunkZ, exception);
            }
            ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, chunkX, chunkZ, false, false);
            it.remove();
        }
        // 2. 从脏队列补充新的强制加载（预算化）
        int started = 0;
        synchronized (dirtyChunks) {
            Iterator<Long> it = dirtyChunks.iterator();
            while (it.hasNext() && started < MAX_FORCE_CHUNKS_PER_TICK) {
                long chunkKey = it.next();
                int chunkX = ChunkPos.getX(chunkKey);
                int chunkZ = ChunkPos.getZ(chunkKey);
                it.remove();
                if (isChunkLoaded(level, chunkX, chunkZ)) {
                    // 已加载：直接走常规队列即可，无需强制
                    queue.enqueue(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, nowMillis);
                    continue;
                }
                if (forcedChunks.contains(chunkKey)) {
                    continue;
                }
                if (ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, chunkX, chunkZ, true, false)) {
                    forcedChunks.add(chunkKey);
                    started++;
                }
            }
        }
    }

    private void releaseAllForcedChunks(ServerLevel level) {
        BlockPos owner = forceOwner(level);
        for (long chunkKey : forcedChunks) {
            ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner,
                    ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey), false, false);
        }
    }

    static int enqueueTileChunksForTest(MarketWebMapRenderQueue queue,
                                        String dimensionId,
                                        int tileX,
                                        int tileZ,
                                        long nowMillis,
                                        ChunkLoadedPredicate loadedPredicate) {
        if (queue == null || loadedPredicate == null || !MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return 0;
        }
        int queued = 0;
        int baseChunkX = tileX * MarketWebMapConstants.CHUNKS_PER_TILE_AXIS;
        int baseChunkZ = tileZ * MarketWebMapConstants.CHUNKS_PER_TILE_AXIS;
        for (int localZ = 0; localZ < MarketWebMapConstants.CHUNKS_PER_TILE_AXIS; localZ++) {
            for (int localX = 0; localX < MarketWebMapConstants.CHUNKS_PER_TILE_AXIS; localX++) {
                int chunkX = baseChunkX + localX;
                int chunkZ = baseChunkZ + localZ;
                if (loadedPredicate.isLoaded(chunkX, chunkZ)
                        && queue.enqueue(dimensionId, chunkX, chunkZ, nowMillis)) {
                    queued++;
                }
            }
        }
        return queued;
    }

    private void enqueueLoadedChunksInTile(ServerLevel level, int tileX, int tileZ, long nowMillis) {
        enqueueTileChunksForTest(
                queue,
                MarketWebMapConstants.OVERWORLD,
                tileX,
                tileZ,
                nowMillis,
                (chunkX, chunkZ) -> isChunkLoaded(level, chunkX, chunkZ));
    }

    private void enqueuePlayerAreas(ServerLevel level, long nowMillis) {
        List<ServerPlayer> players = level.players();
        for (ServerPlayer player : players) {
            int centerChunkX = player.blockPosition().getX() >> 4;
            int centerChunkZ = player.blockPosition().getZ() >> 4;
            enqueueLoadedChunkRadius(level, centerChunkX, centerChunkZ, PLAYER_SCAN_RADIUS_CHUNKS, nowMillis);
        }
    }

    private void enqueueImportantPoints(ServerLevel level, long nowMillis) {
        for (MarketTerminalSavedData.MarketTerminalEntry entry : MarketTerminalSavedData.get(level).entries()) {
            if (!MarketWebMapConstants.OVERWORLD.equals(entry.dimensionId())) {
                continue;
            }
            BlockPos pos = entry.marketPos();
            enqueueLoadedChunkRadius(level, pos.getX() >> 4, pos.getZ() >> 4, POINT_SCAN_RADIUS_CHUNKS, nowMillis);
        }
    }

    private void enqueueLoadedChunkRadius(ServerLevel level, int centerChunkX, int centerChunkZ, int radius, long nowMillis) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                if (isChunkLoaded(level, chunkX, chunkZ)) {
                    queue.enqueue(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, nowMillis);
                }
            }
        }
    }

    private void processBudgeted(ServerLevel level, long nowMillis) {
        for (MarketWebMapRenderQueue.Task task : queue.poll(CHUNKS_PER_TICK, nowMillis)) {
            if (!MarketWebMapConstants.OVERWORLD.equals(task.dimensionId())) {
                continue;
            }
            try {
                renderer.renderLoadedChunk(level, task.chunkX(), task.chunkZ()).ifPresent(pixels ->
                        MarketWebMapTileCache.forServer(level.getServer()).mergeChunkArgb(
                                task.dimensionId(),
                                task.chunkX(),
                                task.chunkZ(),
                                pixels,
                                MarketWebMapTileQuality.SERVER_LOADED_CHUNK,
                                nowMillis));
            } catch (RuntimeException exception) {
                LOGGER.warn("Failed to render market web map chunk {},{}", task.chunkX(), task.chunkZ(), exception);
            }
        }
    }

    private static BlockPos forceOwner(ServerLevel level) {
        return new BlockPos(0, level == null ? 0 : level.getMinBuildHeight(), 0);
    }

    private static boolean isChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
        try {
            return level != null && level.getChunkSource().getChunk(chunkX, chunkZ, false) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    @FunctionalInterface
    interface ChunkLoadedPredicate {
        boolean isLoaded(int chunkX, int chunkZ);
    }
}
