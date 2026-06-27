package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.ModConfig;
import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.IntSupplier;

public final class MarketWebMapRenderService {
    private static final int MAX_QUEUE_TASKS = 4096;
    // 离线异步读模式:每 tick 取出的任务多数会 DEFER(异步读盘未完成下 tick 再来),
    // 旧值 4 会把在途异步读卡在 ~4 个,远低于 PROBE_STARTS_PER_TICK 的读盘能力 → 渲染显著变慢。
    // 放大到 96 让管线喂满到读盘能力上限(异步读不阻塞 tick;解码有 wall-clock 闸兜底)。
    private static final int DEFAULT_SNAPSHOTS_PER_TICK = 96;
    private static final int DEFAULT_SAME_TILE_BURST_LIMIT = 8;
    private static final int REGION_CHUNK_CHECKS_PER_TICK = 128;
    private static final int PLAYER_SCAN_INTERVAL_TICKS = 80;
    private static final int POINT_SCAN_INTERVAL_TICKS = 400;
    private static final int REGION_SCAN_INTERVAL_TICKS = 1200;
    private static final int PLAYER_SCAN_RADIUS_CHUNKS = 3;
    private static final int POINT_SCAN_RADIUS_CHUNKS = 1;
    private static final int REGION_SCAN_REGIONS_PER_PASS = 4;
    private static final int TILE_MISS_MAX_CHUNK_CHECKS = 256;
    private static final int TILE_MISS_MAX_QUEUED_CHUNKS = 64;
    private static final int SQUARE_TILE_CURSOR_CHUNKS_PER_TICK = 128;
    private static final int MAX_SQUARE_TILE_CURSORS = 128;
    private static final int CHUNKS_PER_REGION_AXIS = 32;
    private static final int MAX_REGION_CURSORS = 512;
    // 黑块自动修复:~1 分钟扫一次,每次全量扫盘(budget=0)。扫描只读 PNG 在守护线程,不卡 tick;
    // 命中的黑块 region 走 enqueueRegionsForRepair 一次性全入队(非独占),后台持续并行消费,不再一轮只修 1 个。
    private static final int BLACK_TILE_SCAN_INTERVAL_TICKS = 1200;
    private static final int BLACK_TILE_SCAN_BUDGET = 0;

    private static final MarketWebMapRenderService GLOBAL = new MarketWebMapRenderService(
            new MarketWebMapRenderQueue(MAX_QUEUE_TASKS));

    private final MarketWebMapRenderQueue queue;
    private final MarketWebMapRegionScanService regionScanner;
    private final MarketWebMapRegionWatcher regionWatcher = new MarketWebMapRegionWatcher();
    private final LinkedHashMap<Long, RegionCursor> regionCursors = new LinkedHashMap<>();
    private final LinkedHashMap<TileCursorKey, TileCursor> squareTileCursors = new LinkedHashMap<>();
    private MarketWebMapRenderManager renderManager = new MarketWebMapRenderManager();
    private MarketWebSquareMapRenderer squareRenderer = new MarketWebSquareMapRenderer();
    private int tickCounter;

    public MarketWebMapRenderService(MarketWebMapRenderQueue queue) {
        this.queue = queue == null ? new MarketWebMapRenderQueue(MAX_QUEUE_TASKS) : queue;
        this.regionScanner = new MarketWebMapRegionScanService(this.queue);
    }

    public static MarketWebMapRenderService global() {
        return GLOBAL;
    }

    public void startRegionWatcher(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level != null) {
            renderManager.loadDirtyChunks(level);
            regionWatcher.start(level);
        }
    }

    public void stopRegionWatcher(MinecraftServer server) {
        ServerLevel level = server == null ? null : server.getLevel(Level.OVERWORLD);
        if (level != null) {
            renderManager.saveDirtyChunks(level);
        }
        regionWatcher.stop();
        synchronized (regionCursors) {
            regionCursors.clear();
        }
        synchronized (squareTileCursors) {
            squareTileCursors.clear();
        }
        squareRenderer.shutdown();
        squareRenderer = new MarketWebSquareMapRenderer();
        renderManager.shutdown();
        renderManager = new MarketWebMapRenderManager();
    }

    public void enqueueTileRequest(MinecraftServer server, String lod, int tileX, int tileZ) {
        if (server == null || !"lod_1".equals(lod)) {
            return;
        }
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }
        enqueueTileChunksForTest(
                queue,
                MarketWebMapConstants.OVERWORLD,
                tileX,
                tileZ,
                System.currentTimeMillis(),
                (chunkX, chunkZ) -> isChunkLoaded(level, chunkX, chunkZ));
    }

    public void enqueueSquareTileRequest(MinecraftServer server, String dimensionId, int zoom, int tileX, int tileZ) {
        if (server == null
                || !MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || zoom < 0
                || zoom > MarketWebMapPyramidWriter.MAX_ZOOM) {
            return;
        }
        // 2026-06 修复看门狗卡死(见 marketweb_snapshot_deadlock):web 瓦片请求经 callOnServerThread 在【主线程】
        // 同步执行。原实现当场遍历整片瓦片(zoom 大时几百~上千区块)逐个 getChunk 探测,一个 tick task 里全部跑完 →
        // 配合 Chunky 预生成抢占主线程,单 tick 耗时爆 60s → ServerHangWatchdog 杀服。
        // 改为 O(1) 登记一个游标即返回,真正的区块扫描/登记交给 tick() 的 processSquareTileCursors 按
        // SQUARE_TILE_CURSOR_CHUNKS_PER_TICK 限额节流后台处理,绝不卡主线程。瓦片 PNG 本就由 readSquareTile
        // 读已渲染缓存返回,此处不需要也不应该同步现扫区块。
        addSquareTileCursor(dimensionId, zoom, tileX, tileZ);
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
        // fullrender(或任何 activeJob)进行中时,跳过黑块修复 / region 修复扫描:fullrender 本身就在全量重画,
        // 再叠加黑块修复会抢资源、互相干扰(还会把 fullrender 刚渲好的区域当黑块重排队),且 imageIO 已积压。
        boolean rendering = renderManager.hasActiveJob();
        if (!rendering && tickCounter % REGION_SCAN_INTERVAL_TICKS == 0) {
            enqueueRegionRepairScan(level, REGION_SCAN_REGIONS_PER_PASS);
        }
        if (!rendering
                && configuredBoolean(ModConfig::marketWebBlackTileAutoRepairEnabled, true)
                && tickCounter % BLACK_TILE_SCAN_INTERVAL_TICKS == 0) {
            MarketWebMapBlackTileRepair.scanAndRepairAsync(server, level, BLACK_TILE_SCAN_BUDGET);
        }
        renderManager.tickBackground(level, queue, now);
        drainDirtyRegions();
        processSquareTileCursors(now);
        processRegionCursors(level, now);
        processSnapshotBudget(level, now);
    }

    public int queueSize() {
        return scheduledQueueSize() + renderManager.pendingTasks() + squareRenderer.pendingTasks();
    }

    public String renderDiagLine() {
        return renderManager.diagLine();
    }

    public MarketWebMapRenderManager.RenderStatus renderStatus() {
        return renderManager.status(scheduledQueueSize());
    }

    public boolean startFullRender(ServerLevel level) {
        return renderManager.startFullRender(level);
    }

    public boolean startRadiusRender(ServerLevel level, int centerBlockX, int centerBlockZ, int radiusBlocks) {
        return renderManager.startRadiusRender(level, centerBlockX, centerBlockZ, radiusBlocks);
    }

    public boolean startAreaRender(ServerLevel level, int x1, int z1, int x2, int z2) {
        return renderManager.startAreaRender(level, x1, z1, x2, z2);
    }

    /** 黑块修复专用:把多个黑块 region 一次性标脏入队(非独占,不互相丢弃),由后台持续消费。返回入队 region 数。 */
    public int enqueueRegionsForRepair(ServerLevel level, java.util.List<int[]> regions) {
        return renderManager.enqueueRegionsForRepair(level, regions);
    }

    public boolean startWorldBorderRender(ServerLevel level) {
        return renderManager.startWorldBorderRender(level);
    }

    public boolean pauseRender() {
        return renderManager.pause();
    }

    public boolean resumeRender() {
        return renderManager.resume();
    }

    public boolean cancelRender(ServerLevel level) {
        return renderManager.cancel(level);
    }

    /** clearall 专用:彻底静止渲染(停 job、清 dirty/queue/在途/游标),让磁盘清理不与后台写盘并发。 */
    public void haltAndClearRenderState(ServerLevel level) {
        renderManager.clearAllRenderState(level);
        queue.clear();
        synchronized (squareTileCursors) {
            squareTileCursors.clear();
        }
        synchronized (regionCursors) {
            regionCursors.clear();
        }
    }

    private int scheduledQueueSize() {
        synchronized (squareTileCursors) {
            synchronized (regionCursors) {
                return queue.size()
                        + squareTileCursors.size()
                        + regionCursors.size();
            }
        }
    }

    public int enqueueRegionRepairScan(ServerLevel level, int maxRegions) {
        int added = 0;
        for (MarketWebMapRegionScanService.RegionFile region : regionScanner.scanRegions(level, maxRegions)) {
            if (addRegionCursor(region.regionX(), region.regionZ())) {
                added++;
            }
        }
        return added;
    }

    private void drainDirtyRegions() {
        for (long packedRegion : regionWatcher.drainDirtyRegions()) {
            int regionX = MarketWebMapRegionWatcher.unpackRegionX(packedRegion);
            int regionZ = MarketWebMapRegionWatcher.unpackRegionZ(packedRegion);
            renderManager.markRegionDirty(regionX, regionZ);
        }
    }

    private void processSquareTileCursors(long nowMillis) {
        int checked = 0;
        while (checked < SQUARE_TILE_CURSOR_CHUNKS_PER_TICK) {
            TileCursorKey key;
            TileCursor cursor;
            synchronized (squareTileCursors) {
                Iterator<TileCursorKey> keys = squareTileCursors.keySet().iterator();
                if (!keys.hasNext()) {
                    return;
                }
                key = keys.next();
                cursor = squareTileCursors.remove(key);
            }
            int advanced = cursor.enqueue(queue, nowMillis, SQUARE_TILE_CURSOR_CHUNKS_PER_TICK - checked);
            checked += advanced;
            if (!cursor.done()) {
                synchronized (squareTileCursors) {
                    squareTileCursors.put(key, cursor);
                }
            }
            if (advanced <= 0) {
                return;
            }
        }
    }

    private void processRegionCursors(ServerLevel level, long nowMillis) {
        int checked = 0;
        while (checked < REGION_CHUNK_CHECKS_PER_TICK) {
            RegionCursor cursor;
            long key;
            synchronized (regionCursors) {
                Iterator<Long> keys = regionCursors.keySet().iterator();
                if (!keys.hasNext()) {
                    return;
                }
                key = keys.next();
                cursor = regionCursors.remove(key);
            }
            while (checked < REGION_CHUNK_CHECKS_PER_TICK && !cursor.done()) {
                int chunkX = cursor.chunkX();
                int chunkZ = cursor.chunkZ();
                queue.enqueue(
                        MarketWebMapConstants.OVERWORLD,
                        chunkX,
                        chunkZ,
                        nowMillis,
                        MarketWebMapTileQuality.SERVER_REGION_SCAN);
                cursor.advance();
                checked++;
            }
            if (!cursor.done()) {
                synchronized (regionCursors) {
                    regionCursors.put(key, cursor);
                }
            }
        }
    }

    private void processSnapshotBudget(ServerLevel level, long nowMillis) {
        MarketWebMapTileCache cache = MarketWebMapTileCache.forServer(level.getServer());
        int snapshotsPerTick = configuredInt(ModConfig::marketWebSnapshotTasksPerTick, DEFAULT_SNAPSHOTS_PER_TICK);
        int sameTileBurstLimit = configuredInt(ModConfig::marketWebSameTileBurstLimit, DEFAULT_SAME_TILE_BURST_LIMIT);
        // 每 tick 重置 render manager 的 probe 发起 / force-load 采样配额(主线程节流的起点)。
        renderManager.beginSnapshotTick();
        // 关键:先排干已完成的在途 probe(解码/判定、腾槽位),不依赖该 chunk 的 task 在数十万长队列里再次被 poll。
        // 否则 done 的 256 个 probe 长期占满 inflight → 新 probe 发不出 → 管线冻结(defer 疯涨、progress 停)。
        renderManager.drainCompletedProbes(level, cache, nowMillis);
        // fullrender 活跃时,当前 region 的读取由 tickBackground 的专用读取器全权负责(绕开 queue/defer)。此处不再
        // poll queue 抢主线程预算 —— 否则双方共用 GENERATED_SAMPLES/wall-clock 闸会互相饿死。queue 里只剩非 fullrender
        // 的零散 task(玩家区域等),它们不急,fullrender 结束后再消费。drainCompletedProbes 仍跑(清非 fullrender 残留)。
        if (renderManager.hasActiveFullRender()) {
            return;
        }
        for (MarketWebMapRenderQueue.Task task : queue.pollCoalesced(snapshotsPerTick, sameTileBurstLimit, nowMillis)) {
            if (!MarketWebMapConstants.OVERWORLD.equals(task.dimensionId())) {
                continue;
            }
            // submitSnapshot 全程主线程:已加载直接采样;未加载经 probe 判已生成才 force-load 采样(配额内),
            // 未生成留空。probe 未决 / force-load 配额用尽 → DEFER:把该 chunk 同质量重新入队,下 tick 重试。
            MarketWebMapRenderManager.SubmitResult result = renderManager.submitSnapshot(level, cache, task, nowMillis);
            renderManager.recordSubmitResult(result);
            if (result == MarketWebMapRenderManager.SubmitResult.DEFER) {
                queue.enqueue(task.dimensionId(), task.chunkX(), task.chunkZ(), nowMillis, task.quality());
            }
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

    static int enqueueSquareTileChunksForTest(MarketWebMapRenderQueue queue,
                                              String dimensionId,
                                              int zoom,
                                              int tileX,
                                              int tileZ,
                                              long nowMillis,
                                              ChunkLoadedPredicate loadedPredicate) {
        if (queue == null
                || loadedPredicate == null
                || !MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || zoom < 0
                || zoom > MarketWebMapPyramidWriter.MAX_ZOOM) {
            return 0;
        }
        int scale = 1 << zoom;
        int chunksPerAxis = MarketWebMapTileCoordinate.CHUNKS_PER_BASE_TILE_AXIS * scale;
        int baseChunkX = tileX * chunksPerAxis;
        int baseChunkZ = tileZ * chunksPerAxis;
        int queued = 0;
        int checked = 0;
        for (int localZ = 0; localZ < chunksPerAxis && checked < TILE_MISS_MAX_CHUNK_CHECKS; localZ++) {
            for (int localX = 0; localX < chunksPerAxis && checked < TILE_MISS_MAX_CHUNK_CHECKS; localX++) {
                int chunkX = baseChunkX + localX;
                int chunkZ = baseChunkZ + localZ;
                checked++;
                if (loadedPredicate.isLoaded(chunkX, chunkZ)
                        && queue.enqueue(dimensionId, chunkX, chunkZ, nowMillis, MarketWebMapTileQuality.SERVER_LOADED_CHUNK)) {
                    queued++;
                    if (queued >= TILE_MISS_MAX_QUEUED_CHUNKS) {
                        return queued;
                    }
                }
            }
        }
        return queued;
    }

    static int enqueueSquareTileRegionScanForTest(MarketWebMapRenderQueue queue,
                                                  String dimensionId,
                                                  int zoom,
                                                  int tileX,
                                                  int tileZ,
                                                  long nowMillis,
                                                  int maxChunks) {
        if (queue == null
                || !MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || zoom < 0
                || zoom > MarketWebMapPyramidWriter.MAX_ZOOM) {
            return 0;
        }
        return 0;
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

    private void addChunkRangeAsRegions(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
        int minRegionX = Math.floorDiv(Math.min(minChunkX, maxChunkX), CHUNKS_PER_REGION_AXIS);
        int maxRegionX = Math.floorDiv(Math.max(minChunkX, maxChunkX), CHUNKS_PER_REGION_AXIS);
        int minRegionZ = Math.floorDiv(Math.min(minChunkZ, maxChunkZ), CHUNKS_PER_REGION_AXIS);
        int maxRegionZ = Math.floorDiv(Math.max(minChunkZ, maxChunkZ), CHUNKS_PER_REGION_AXIS);
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                addRegionCursor(regionX, regionZ);
            }
        }
    }

    private boolean addSquareTileCursor(String dimensionId, int zoom, int tileX, int tileZ) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || zoom < 0
                || zoom > MarketWebMapPyramidWriter.MAX_ZOOM) {
            return false;
        }
        TileCursorKey key = new TileCursorKey(dimensionId, zoom, tileX, tileZ);
        synchronized (squareTileCursors) {
            if (squareTileCursors.containsKey(key)) {
                return false;
            }
            squareTileCursors.put(key, new TileCursor(dimensionId, zoom, tileX, tileZ));
            while (squareTileCursors.size() > MAX_SQUARE_TILE_CURSORS) {
                Iterator<TileCursorKey> keys = squareTileCursors.keySet().iterator();
                if (!keys.hasNext()) {
                    break;
                }
                keys.next();
                keys.remove();
            }
            return true;
        }
    }

    private boolean addRegionCursor(int regionX, int regionZ) {
        long key = MarketWebMapRegionWatcher.packRegion(regionX, regionZ);
        synchronized (regionCursors) {
            if (regionCursors.containsKey(key)) {
                return false;
            }
            regionCursors.put(key, new RegionCursor(regionX, regionZ));
            while (regionCursors.size() > MAX_REGION_CURSORS) {
                Iterator<Long> keys = regionCursors.keySet().iterator();
                if (!keys.hasNext()) {
                    break;
                }
                keys.next();
                keys.remove();
            }
            return true;
        }
    }

    private record TileCursorKey(String dimensionId, int zoom, int tileX, int tileZ) {
    }

    private static final class TileCursor {
        private final String dimensionId;
        private final int baseChunkX;
        private final int baseChunkZ;
        private final int chunksPerAxis;
        private int nextLocal;

        private TileCursor(String dimensionId, int zoom, int tileX, int tileZ) {
            this.dimensionId = dimensionId;
            int scale = 1 << Math.max(0, Math.min(MarketWebMapPyramidWriter.MAX_ZOOM, zoom));
            this.chunksPerAxis = MarketWebMapTileCoordinate.CHUNKS_PER_BASE_TILE_AXIS * scale;
            this.baseChunkX = tileX * chunksPerAxis;
            this.baseChunkZ = tileZ * chunksPerAxis;
        }

        private int enqueue(MarketWebMapRenderQueue queue, long nowMillis, int budget) {
            if (queue == null || budget <= 0 || done()) {
                return 0;
            }
            int advanced = 0;
            while (advanced < budget && !done()) {
                int localX = Math.floorMod(nextLocal, chunksPerAxis);
                int localZ = Math.floorDiv(nextLocal, chunksPerAxis);
                queue.enqueue(
                        dimensionId,
                        baseChunkX + localX,
                        baseChunkZ + localZ,
                        nowMillis,
                        MarketWebMapTileQuality.SERVER_REGION_SCAN);
                nextLocal++;
                advanced++;
            }
            return advanced;
        }

        private boolean done() {
            return nextLocal >= chunksPerAxis * chunksPerAxis;
        }
    }

    private static boolean isChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
        try {
            return level != null && level.getChunkSource().getChunk(chunkX, chunkZ, false) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int configuredInt(IntSupplier supplier, int fallback) {
        try {
            return supplier.getAsInt();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    private static boolean configuredBoolean(java.util.function.BooleanSupplier supplier, boolean fallback) {
        try {
            return supplier.getAsBoolean();
        } catch (IllegalStateException exception) {
            return fallback; // 配置未加载(早期 tick)时退回默认值
        }
    }

    @FunctionalInterface
    interface ChunkLoadedPredicate {
        boolean isLoaded(int chunkX, int chunkZ);
    }

    private static final class RegionCursor {
        private final int regionX;
        private final int regionZ;
        private int nextLocal;

        private RegionCursor(int regionX, int regionZ) {
            this.regionX = regionX;
            this.regionZ = regionZ;
        }

        private int chunkX() {
            return regionX * CHUNKS_PER_REGION_AXIS + Math.floorMod(nextLocal, CHUNKS_PER_REGION_AXIS);
        }

        private int chunkZ() {
            return regionZ * CHUNKS_PER_REGION_AXIS + Math.floorDiv(nextLocal, CHUNKS_PER_REGION_AXIS);
        }

        private void advance() {
            nextLocal++;
        }

        private boolean done() {
            return nextLocal >= CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS;
        }
    }
}
