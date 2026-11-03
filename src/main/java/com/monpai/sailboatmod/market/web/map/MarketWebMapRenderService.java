package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;

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

public final class MarketWebMapRenderService {
    private static final int MAX_QUEUE_TASKS = 4096;
    private static final int REGION_CHUNK_CHECKS_PER_TICK = 128;
    private static final int DIRTY_REGIONS_PER_TICK = 32;
    private static final int PLAYER_SCAN_INTERVAL_TICKS = 80;
    private static final int POINT_SCAN_INTERVAL_TICKS = 400;
    private static final int REGION_SCAN_INTERVAL_TICKS = 1200;
    private static final int PLAYER_SCAN_RADIUS_CHUNKS = 3;
    private static final int POINT_SCAN_RADIUS_CHUNKS = 1;
    private static final int REGION_SCAN_REGIONS_PER_PASS = 4;
    private static final int TILE_MISS_MAX_CHUNK_CHECKS = 256;
    private static final int TILE_MISS_MAX_QUEUED_CHUNKS = 64;
    private static final int SQUARE_TILE_CURSOR_CHUNKS_PER_TICK = 128;
    private static final int DISCOVERY_CANDIDATES_PER_REQUEST = 256;
    private static final int MAX_SQUARE_TILE_CURSORS = 128;
    private static final int CHUNKS_PER_REGION_AXIS = 32;
    private static final int MAX_REGION_CURSORS = 512;
    // Automatic repair scans a rotating bounded PNG batch; explicit manual repair can scan all.
    private static final int BLACK_TILE_SCAN_INTERVAL_TICKS = 1200;
    private static final int BLACK_TILE_SCAN_BUDGET = 128;

    private static final MarketWebMapRenderService GLOBAL = new MarketWebMapRenderService(
            new MarketWebMapRenderQueue(MAX_QUEUE_TASKS));

    private final MarketWebMapRenderQueue queue;
    private final MarketWebMapRegionScanService regionScanner;
    private final MarketWebMapRegionWatcher regionWatcher = new MarketWebMapRegionWatcher();
    private final LinkedHashMap<Long, RegionCursor> regionCursors = new LinkedHashMap<>();
    private final LinkedHashMap<TileCursorKey, TileCursor> squareTileCursors = new LinkedHashMap<>();
    private final LinkedHashMap<TileCursorKey, Long> discoveryCooldowns = new LinkedHashMap<>();
    private static final int MAX_DISCOVERY_COOLDOWNS = 1024;
    private MarketWebMapRenderManager renderManager = new MarketWebMapRenderManager();
    private MarketWebSquareMapRenderer squareRenderer = new MarketWebSquareMapRenderer();
    private int tickCounter;

    public MarketWebMapRenderService(MarketWebMapRenderQueue queue) {
        this.queue = queue == null ? new MarketWebMapRenderQueue(MAX_QUEUE_TASKS) : queue;
        this.regionScanner = new MarketWebMapRegionScanService(this.queue);
        this.renderManager.bindRenderQueue(this.queue);
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
        for (var region : regionScanner.invalidateAndDrainRepairResults()) {
            renderManager.retainRegionForRecovery(region.regionX(), region.regionZ());
        }
        for (long region : regionWatcher.stopAndDrain()) {
            renderManager.retainRegionForRecovery(MarketWebMapRegionWatcher.unpackRegionX(region),
                    MarketWebMapRegionWatcher.unpackRegionZ(region));
        }
        synchronized (regionCursors) {
            for (long region : regionCursors.keySet()) {
                renderManager.retainRegionForRecovery(MarketWebMapRegionWatcher.unpackRegionX(region),
                        MarketWebMapRegionWatcher.unpackRegionZ(region));
            }
            regionCursors.clear();
        }
        if (level != null) {
            renderManager.savePendingWork(level, queue);
        }
        queue.clear();
        synchronized (squareTileCursors) {
            squareTileCursors.clear();
            discoveryCooldowns.clear();
        }
        squareRenderer.shutdown();
        squareRenderer = new MarketWebSquareMapRenderer();
        renderManager.shutdown();
        renderManager = new MarketWebMapRenderManager();
        renderManager.bindRenderQueue(queue);
    }

    public void enqueueTileRequest(MinecraftServer server, String lod, int tileX, int tileZ) {
        if (server == null || !"lod_1".equals(lod) || !MarketWebMapSettings.updatesEnabled()) {
            return;
        }
        // HTTP handlers register only bounded work; they never inspect ServerLevel off-thread.
        addBrowserCursor(new TileCursorKey(MarketWebMapConstants.OVERWORLD, -1, tileX, tileZ),
                MarketWebMapConstants.CHUNKS_PER_TILE_AXIS, tileX, tileZ);
    }

    public void enqueueSquareTileRequest(MinecraftServer server, String dimensionId, int zoom, int tileX, int tileZ) {
        if (server == null || !MarketWebMapSettings.updatesEnabled()
                || !MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || zoom < 0 || zoom > MarketWebMapPyramidWriter.MAX_ZOOM) {
            return;
        }
        addSquareTileCursor(dimensionId, zoom, tileX, tileZ);
    }

    public void tick(MinecraftServer server) {
        if (server == null || !MarketWebMapSettings.updatesEnabled() || renderManager.isPaused()) {
            return;
        }
        ServerLevel level = server.getLevel(Level.OVERWORLD);
        if (level == null) {
            return;
        }
        renderManager.beginSnapshotTick(server.getTickCount());
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
        if (!rendering) {
            drainRepairScanResults();
            if (tickCounter % REGION_SCAN_INTERVAL_TICKS == 0 && renderManager.hasSamplingTime()) {
                regionScanner.requestRepairScan(regionScanner.captureRepairScan(level, REGION_SCAN_REGIONS_PER_PASS));
            }
        }
        if (!rendering
                && configuredBoolean(ModConfig::marketWebBlackTileAutoRepairEnabled, true)
                && tickCounter % BLACK_TILE_SCAN_INTERVAL_TICKS == 0) {
            MarketWebMapBlackTileRepair.scanAndRepairAsync(server, level, BLACK_TILE_SCAN_BUDGET);
        }
        renderManager.tickBackground(level, queue, now);
        drainDirtyRegions();
        processSquareTileCursors(level, now);
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
        return MarketWebMapSettings.updatesEnabled() ? renderManager.enqueueRegionsForRepair(level, regions) : 0;
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
        regionScanner.invalidateAndDrainRepairResults();
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
                        + regionCursors.size() + regionScanner.pendingRepairWork();
            }
        }
    }

    public int enqueueRegionRepairScan(ServerLevel level, int maxRegions) {
        if (!MarketWebMapSettings.updatesEnabled()) {
            return 0;
        }
        int added = 0;
        for (MarketWebMapRegionScanService.RegionFile region : regionScanner.scanRegions(level, maxRegions)) {
            if (addRegionCursor(region.regionX(), region.regionZ())) {
                added++;
            }
        }
        return added;
    }

    private void drainRepairScanResults() {
        while (renderManager.hasSamplingTime()) {
            var region = regionScanner.peekRepairRegion();
            if (region == null) { return; }
            synchronized (regionCursors) {
                long key = MarketWebMapRegionWatcher.packRegion(region.regionX(), region.regionZ());
                if (!regionCursors.containsKey(key) && !addRegionCursor(region.regionX(), region.regionZ())) {
                    return; // Keep completed output until a cursor slot is available.
                }
                regionScanner.acceptRepairRegion(region);
            }
        }
    }

    private void drainDirtyRegions() {
        if (!renderManager.hasSamplingTime()) {
            return;
        }
        int capacity = Math.min(DIRTY_REGIONS_PER_TICK, renderManager.remainingDirtyRegionCapacity());
        for (long packedRegion : regionWatcher.drainDirtyRegions(capacity)) {
            int regionX = MarketWebMapRegionWatcher.unpackRegionX(packedRegion);
            int regionZ = MarketWebMapRegionWatcher.unpackRegionZ(packedRegion);
            renderManager.markRegionDirty(regionX, regionZ);
        }
    }

    private void processSquareTileCursors(ServerLevel level, long nowMillis) {
        int checked = 0;
        while (checked < SQUARE_TILE_CURSOR_CHUNKS_PER_TICK && renderManager.hasSamplingTime()) {
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
            int advanced = cursor.enqueue(queue, nowMillis, SQUARE_TILE_CURSOR_CHUNKS_PER_TICK - checked,
                    (chunkX, chunkZ) -> isChunkLoaded(level, chunkX, chunkZ), renderManager::hasSamplingTime);
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
        while (checked < REGION_CHUNK_CHECKS_PER_TICK && renderManager.hasSamplingTime()) {
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
            while (checked < REGION_CHUNK_CHECKS_PER_TICK && !cursor.done() && renderManager.hasSamplingTime()) {
                int chunkX = cursor.chunkX();
                int chunkZ = cursor.chunkZ();
                boolean accepted = queue.enqueue(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ,
                        nowMillis, MarketWebMapTileQuality.SERVER_REGION_SCAN)
                        || queue.contains(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ);
                if (!accepted) {
                    break;
                }
                cursor.advance();
                checked++;
            }
            if (!cursor.done()) {
                synchronized (regionCursors) {
                    regionCursors.put(key, cursor);
                }
                if (queue.remainingCapacity() == 0) {
                    return;
                }
            }
        }
    }

    private void processSnapshotBudget(ServerLevel level, long nowMillis) {
        MarketWebMapTileCache cache = MarketWebMapTileCache.forServer(level.getServer());
        // Idempotent safety for direct callers: this does not reset the fullreader's budget.
        renderManager.beginSnapshotTick(level.getServer().getTickCount());
        renderManager.drainCompletedProbes(level, cache, nowMillis);
        if (renderManager.hasActiveFullRender()) {
            return;
        }
        int taskChecks = MarketWebMapSettings.maxSnapshotsPerTick() + MarketWebMapSettings.maxProbeStartsPerTick();
        for (int checked = 0; checked < taskChecks && renderManager.hasSamplingTime(); checked++) {
            List<MarketWebMapRenderQueue.Task> tasks = queue.pollTracked(1, nowMillis);
            if (tasks.isEmpty()) {
                break;
            }
            MarketWebMapRenderQueue.Task task = tasks.getFirst();
            try {
                MarketWebMapRenderManager.SubmitResult result = renderManager.submitSnapshot(level, cache, task, nowMillis);
                renderManager.recordSubmitResult(result);
                if (result == MarketWebMapRenderManager.SubmitResult.DEFER) {
                    queue.enqueue(task.dimensionId(), task.chunkX(), task.chunkZ(), nowMillis, task.quality());
                }
            } catch (RuntimeException exception) {
                queue.enqueue(task.dimensionId(), task.chunkX(), task.chunkZ(), nowMillis, task.quality());
                throw exception;
            } finally {
                // Snapshot either remains queued or is registered in a durable-recovery source.
                queue.acknowledge(task);
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
            if (!renderManager.hasSamplingTime()) { return; }
            int centerChunkX = player.blockPosition().getX() >> 4;
            int centerChunkZ = player.blockPosition().getZ() >> 4;
            enqueueLoadedChunkRadius(level, centerChunkX, centerChunkZ, PLAYER_SCAN_RADIUS_CHUNKS, nowMillis);
        }
    }

    private void enqueueImportantPoints(ServerLevel level, long nowMillis) {
        for (MarketTerminalSavedData.MarketTerminalEntry entry : MarketTerminalSavedData.get(level).entries()) {
            if (!renderManager.hasSamplingTime()) { return; }
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
                if (!renderManager.hasSamplingTime()) { return; }
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
                || zoom < 0 || zoom > MarketWebMapPyramidWriter.MAX_ZOOM) {
            return false;
        }
        return addBrowserCursor(new TileCursorKey(dimensionId, zoom, tileX, tileZ),
                MarketWebMapTileCoordinate.CHUNKS_PER_BASE_TILE_AXIS * (1 << zoom), tileX, tileZ);
    }

    private boolean addBrowserCursor(TileCursorKey key, int chunksPerAxis, int tileX, int tileZ) {
        long now = System.currentTimeMillis();
        synchronized (squareTileCursors) {
            Long previous = discoveryCooldowns.get(key);
            if (!MarketWebMapSettings.updatesEnabled() || squareTileCursors.containsKey(key)
                    || squareTileCursors.size() >= MAX_SQUARE_TILE_CURSORS
                    || (previous != null && now >= previous && now - previous < MarketWebMapSettings.browserDiscoveryCooldownMillis())) {
                return false;
            }
            squareTileCursors.put(key, new TileCursor(key.dimensionId(), chunksPerAxis, tileX, tileZ));
            discoveryCooldowns.remove(key);
            discoveryCooldowns.put(key, now);
            while (discoveryCooldowns.size() > MAX_DISCOVERY_COOLDOWNS) {
                var iterator = discoveryCooldowns.keySet().iterator();
                iterator.next();
                iterator.remove();
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
            if (regionCursors.size() >= MAX_REGION_CURSORS) {
                return false;
            }
            regionCursors.put(key, new RegionCursor(regionX, regionZ));
            return true;
        }
    }

    private record TileCursorKey(String dimensionId, int zoom, int tileX, int tileZ) {
    }

    static final class TileCursor {
        private final String dimensionId;
        private final int baseChunkX;
        private final int baseChunkZ;
        private final int chunksPerAxis;
        private final int maxChecks;
        private int checked;
        private int queued;

        TileCursor(String dimensionId, int chunksPerAxis, int tileX, int tileZ) {
            this.dimensionId = dimensionId;
            this.chunksPerAxis = chunksPerAxis;
            this.baseChunkX = tileX * chunksPerAxis;
            this.baseChunkZ = tileZ * chunksPerAxis;
            this.maxChecks = Math.min(DISCOVERY_CANDIDATES_PER_REQUEST, chunksPerAxis * chunksPerAxis);
        }

        int enqueue(MarketWebMapRenderQueue queue, long nowMillis, int budget,
                    ChunkLoadedPredicate loaded, java.util.function.BooleanSupplier hasTime) {
            int advanced = 0;
            while (advanced < budget && !done() && hasTime.getAsBoolean()) {
                // A request has an absolute cap, even for the 262144-chunk overview tile.
                // Spread checks across its area, rather than expanding a persistent whole-tile cursor.
                int cells = (int) Math.ceil(Math.sqrt(maxChecks));
                int localX = Math.min(chunksPerAxis - 1, (checked % cells) * chunksPerAxis / cells);
                int localZ = Math.min(chunksPerAxis - 1, (checked / cells) * chunksPerAxis / cells);
                int chunkX = baseChunkX + localX;
                int chunkZ = baseChunkZ + localZ;
                if (loaded.isLoaded(chunkX, chunkZ)) {
                    boolean added = queue.enqueue(dimensionId, chunkX, chunkZ, nowMillis, MarketWebMapTileQuality.SERVER_LOADED_CHUNK);
                    if (!added && !queue.contains(dimensionId, chunkX, chunkZ)) {
                        break;
                    }
                    if (added) { queued++; }
                }
                checked++;
                advanced++;
            }
            return advanced;
        }

        boolean done() {
            return checked >= maxChecks || queued >= TILE_MISS_MAX_QUEUED_CHUNKS;
        }
    }

    private static boolean isChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
        try {
            return level != null && level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        } catch (RuntimeException ignored) {
            return false;
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
