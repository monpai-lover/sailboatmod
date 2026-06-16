package com.monpai.sailboatmod.market.web.map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.ModConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/**
 * Squaremap-style render lifecycle manager for the market web map.
 */
public final class MarketWebMapRenderManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final String PROGRESS_FILE = "render_progress.json";
    private static final String DIRTY_CHUNKS_FILE = "dirty_chunks.json";
    private static final String DIRTY_REGIONS_FILE = "dirty_regions.json";
    private static final int CHUNKS_PER_REGION_AXIS = MarketWebMapRegionImage.CHUNKS_PER_REGION_AXIS;
    private static final int CHUNKS_PER_REGION = CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS;

    private final MarketWebMapRegionRenderer regionRenderer = new MarketWebMapRegionRenderer();
    private final MarketWebSquareMapImageIOExecutor imageIO = new MarketWebSquareMapImageIOExecutor();
    private final MarketWebMapDirtyChunkQueue dirtyChunks = new MarketWebMapDirtyChunkQueue();
    private final MarketWebMapDirtyRegionQueue dirtyRegions = new MarketWebMapDirtyRegionQueue();
    private final Map<Long, RegionState> regionStates = new LinkedHashMap<>();
    private final Map<Long, List<Integer>> expectedRegionChunks = new LinkedHashMap<>();
    private final LinkedHashMap<Long, int[][]> bottomRowCache = new LinkedHashMap<>();
    private final AtomicInteger renderingRegions = new AtomicInteger();
    private final AtomicInteger failedChunks = new AtomicInteger();
    private final ExecutorService renderExecutor;
    private MarketWebMapSnapshotManager snapshotManager;
    private ServerLevel snapshotLevel;
    private RenderJob activeJob;
    private boolean paused;
    private int tickCounter;

    public MarketWebMapRenderManager() {
        int threads = configuredRenderThreads();
        this.renderExecutor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "SailboatMarketWebMap-RegionRender");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void tickBackground(ServerLevel level,
                               MarketWebMapRenderQueue queue,
                               long nowMillis) {
        if (level == null || queue == null) {
            return;
        }
        RenderJob job;
        synchronized (this) {
            job = activeJob;
            if (paused) {
                return;
            }
            tickCounter++;
            int interval = Math.max(1, configuredInt(ModConfig::marketWebBackgroundIntervalTicks, 200));
            if (tickCounter % interval != 0) {
                return;
            }
        }
        int budget = Math.max(1, configuredInt(ModConfig::marketWebBackgroundMaxChunksPerInterval, 512));
        synchronized (this) {
            if (job != null) {
                int attempted = 0;
                while (attempted < budget && activeJob == job && !job.done()) {
                    MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk = job.chunk();
                    queue.enqueue(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ(), nowMillis, MarketWebMapTileQuality.SERVER_REGION_SCAN);
                    job.advance();
                    job.processedChunks = job.completedChunkCount();
                    attempted++;
                }
                if (attempted > 0) {
                    job.processedRegions = job.completedRegionCount();
                    int saveInterval = Math.max(1, configuredInt(ModConfig::marketWebProgressSaveIntervalChunks, 512));
                    if (job.done() || job.processedChunks - job.lastSavedChunkIndex >= saveInterval) {
                        job.lastSavedChunkIndex = job.processedChunks;
                        saveProgress(level, job);
                    }
                }
                if (job.done()) {
                    clearProgress(level);
                    activeJob = null;
                    expectedRegionChunks.clear();
                    paused = false;
                }
            } else {
                expandDirtyRegions(level);
                List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> dirty = dirtyChunks.poll(budget);
                for (MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk : dirty) {
                    queue.enqueue(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ(), nowMillis, MarketWebMapTileQuality.SERVER_REGION_SCAN);
                }
                if (!dirty.isEmpty()) {
                    saveDirtyChunks(level);
                }
            }
        }
    }

    public void submitSnapshot(ServerLevel level,
                               MarketWebMapTileCache cache,
                               MarketWebMapRenderQueue.Task task,
                               long nowMillis) {
        if (level == null || cache == null || task == null || !MarketWebMapConstants.OVERWORLD.equals(task.dimensionId())) {
            return;
        }
        // 主线程旁路:已加载的 chunk 直接在主线程 capture(读 ServerLevel 在主线程是安全的),
        // 完全绕开 SnapshotManager 与 worker 线程。只有未加载的 chunk 才进 SnapshotManager 走异步 NBT 读盘。
        // 这样 worker 线程永不需要读 ServerLevel,从根上消除「worker join 主线程」的死锁边。
        if (submitLoadedSnapshotOnMainThread(level, cache, task, nowMillis)) {
            return;
        }
        MarketWebMapSnapshotManager manager = snapshotManager(level);
        manager.snapshotDirect(task.dimensionId(), task.chunkX(), task.chunkZ() - 1, task.quality())
                .thenAccept(optional -> optional.ifPresent(this::cacheBottomRow));
        CompletableFuture<Optional<MarketWebMapChunkSnapshot>> future = manager.snapshotWithVerticalNeighbors(
                task.dimensionId(),
                task.chunkX(),
                task.chunkZ(),
                task.quality());
        future.whenComplete((optional, failure) -> {
            if (failure != null || optional == null || optional.isEmpty()) {
                if (failure != null) {
                    failedChunks.incrementAndGet();
                }
                acceptMissingSnapshot(cache, task.dimensionId(), task.chunkX(), task.chunkZ(), task.quality(), nowMillis);
                return;
            }
            acceptSnapshot(cache, optional.get(), task.quality(), nowMillis);
        });
    }

    /**
     * 主线程旁路:若目标 chunk 已加载,在主线程直接 capture 并消费,返回 true。
     * 同时趁机 capture 北邻(chunkZ-1)喂 bottomRow 缓存,与异步路径的 cacheBottomRow 语义一致。
     * 目标 chunk 未加载时返回 false,交给异步 NBT 路径。
     */
    private boolean submitLoadedSnapshotOnMainThread(ServerLevel level,
                                                     MarketWebMapTileCache cache,
                                                     MarketWebMapRenderQueue.Task task,
                                                     long nowMillis) {
        Optional<MarketWebMapChunkSnapshot> target = MarketWebMapChunkSnapshot.capture(level, task.chunkX(), task.chunkZ());
        if (target.isEmpty()) {
            return false;
        }
        // 北邻已加载则缓存其底行(供 height shading 接缝)。北邻未加载就跳过——异步路径后续也会补。
        MarketWebMapChunkSnapshot.capture(level, task.chunkX(), task.chunkZ() - 1).ifPresent(this::cacheBottomRow);
        acceptSnapshot(cache, target.get(), task.quality(), nowMillis);
        return true;
    }

    public synchronized boolean startFullRender(ServerLevel level) {
        if (level == null || activeJob != null) {
            return false;
        }
        List<MarketWebMapRegionScanService.RegionFile> regions = new MarketWebMapRegionScanService(null)
                .scanRegions(level, Integer.MAX_VALUE);
        if (regions.isEmpty()) {
            return false;
        }
        activeJob = RenderJob.full(regions);
        expectedRegionChunks.clear();
        for (MarketWebMapRegionScanService.RegionFile region : regions) {
            expectedRegionChunks.put(packRegion(region.regionX(), region.regionZ()), region.localChunks());
        }
        restoreProgress(level, activeJob);
        paused = false;
        tickCounter = 0;
        activeJob.lastSavedChunkIndex = activeJob.processedChunks;
        saveProgress(level, activeJob);
        return true;
    }

    public synchronized boolean startRadiusRender(ServerLevel level, int centerBlockX, int centerBlockZ, int radiusBlocks) {
        if (level == null || activeJob != null || radiusBlocks <= 0) {
            return false;
        }
        int minChunkX = Math.floorDiv(centerBlockX - radiusBlocks, MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(centerBlockX + radiusBlocks, MarketWebMapConstants.CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(centerBlockZ - radiusBlocks, MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(centerBlockZ + radiusBlocks, MarketWebMapConstants.CHUNK_SIZE);
        int centerChunkX = Math.floorDiv(centerBlockX, MarketWebMapConstants.CHUNK_SIZE);
        int centerChunkZ = Math.floorDiv(centerBlockZ, MarketWebMapConstants.CHUNK_SIZE);
        int radiusChunks = Math.max(
                Math.abs(minChunkX - centerChunkX),
                Math.max(Math.abs(maxChunkX - centerChunkX),
                        Math.max(Math.abs(minChunkZ - centerChunkZ), Math.abs(maxChunkZ - centerChunkZ))));
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks =
                MarketWebMapSpiralChunkIterator.squareSpiral(MarketWebMapConstants.OVERWORLD, centerChunkX, centerChunkZ, radiusChunks);
        if (chunks.isEmpty()) {
            return false;
        }
        activeJob = RenderJob.radius(chunks, radiusBlocks);
        expectedRegionChunks.clear();
        paused = false;
        tickCounter = 0;
        saveProgress(level, activeJob);
        return true;
    }

    public synchronized boolean startAreaRender(ServerLevel level, int x1, int z1, int x2, int z2) {
        if (level == null || activeJob != null) {
            return false;
        }
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks = areaChunks(x1, z1, x2, z2);
        if (chunks.isEmpty()) {
            return false;
        }
        activeJob = RenderJob.area(chunks);
        prepareExpectedRegionChunks(chunks);
        paused = false;
        tickCounter = 0;
        saveProgress(level, activeJob);
        return true;
    }

    public synchronized boolean startWorldBorderRender(ServerLevel level) {
        if (level == null || activeJob != null) {
            return false;
        }
        WorldBorder border = level.getWorldBorder();
        if (border == null) {
            return false;
        }
        BlockBounds bounds = blockBoundsFromWorldBorder(border);
        List<MarketWebMapRegionScanService.RegionFile> regions = filterRegionFilesToBlockBounds(
                new MarketWebMapRegionScanService(null).scanRegions(level, Integer.MAX_VALUE),
                bounds.minX(),
                bounds.minZ(),
                bounds.maxX(),
                bounds.maxZ());
        if (regions.isEmpty()) {
            return false;
        }
        activeJob = RenderJob.border(regions);
        expectedRegionChunks.clear();
        for (MarketWebMapRegionScanService.RegionFile region : regions) {
            expectedRegionChunks.put(packRegion(region.regionX(), region.regionZ()), region.localChunks());
        }
        restoreProgress(level, activeJob);
        paused = false;
        tickCounter = 0;
        activeJob.lastSavedChunkIndex = activeJob.processedChunks;
        saveProgress(level, activeJob);
        return true;
    }

    public synchronized boolean pause() {
        if (activeJob == null) {
            return false;
        }
        paused = true;
        return true;
    }

    public synchronized boolean resume() {
        if (activeJob == null) {
            return false;
        }
        paused = false;
        return true;
    }

    public synchronized boolean cancel(ServerLevel level) {
        if (activeJob == null) {
            return false;
        }
        activeJob = null;
        expectedRegionChunks.clear();
        paused = false;
        if (level != null) {
            clearProgress(level);
        }
        return true;
    }

    public synchronized RenderStatus status(int queueSize) {
        RenderJob job = activeJob;
        MarketWebMapSnapshotManager manager = snapshotManager;
        return new RenderStatus(
                job == null ? "idle" : job.type,
                paused,
                queueSize,
                manager == null ? 0 : manager.activeRequests(),
                manager == null ? 0 : manager.pendingRequests(),
                manager == null ? 0 : manager.cachedSnapshots(),
                dirtyRegions.size(),
                dirtyChunks.size(),
                regionStates.size(),
                renderingRegions.get(),
                imageIO.pendingTasks(),
                failedChunks.get(),
                job == null ? 0 : job.currentRegionX(),
                job == null ? 0 : job.currentRegionZ(),
                job == null ? 0 : job.currentLocalChunk(),
                job == null ? 0 : job.processedChunks,
                job == null ? 0 : job.totalChunks(),
                job == null ? 0 : job.processedRegions,
                job == null ? 0 : job.totalRegions());
    }

    public int pendingTasks() {
        MarketWebMapSnapshotManager manager = snapshotManager;
        return (manager == null ? 0 : manager.activeRequests() + manager.pendingRequests())
                + dirtyRegions.size()
                + dirtyChunks.size()
                + renderingRegions.get()
                + imageIO.pendingTasks();
    }

    public synchronized void loadDirtyChunks(ServerLevel level) {
        dirtyChunks.load(dirtyChunksFile(level));
        dirtyRegions.load(dirtyRegionsFile(level));
    }

    public synchronized void saveDirtyChunks(ServerLevel level) {
        dirtyChunks.save(dirtyChunksFile(level));
        dirtyRegions.save(dirtyRegionsFile(level));
    }

    public synchronized int markRegionDirty(int regionX, int regionZ) {
        boolean marked = dirtyRegions.markDirty(MarketWebMapConstants.OVERWORLD, regionX, regionZ);
        dirtyRegions.trimToMaxRegions(configuredInt(ModConfig::marketWebMaxDirtyChunks, 200000) / CHUNKS_PER_REGION);
        return marked ? 1 : 0;
    }

    public void shutdown() {
        renderExecutor.shutdownNow();
        imageIO.shutdown();
    }

    private void acceptSnapshot(MarketWebMapTileCache cache,
                                MarketWebMapChunkSnapshot snapshot,
                                MarketWebMapTileQuality quality,
                                long nowMillis) {
        List<RegionState> readyStates = new ArrayList<>();
        synchronized (this) {
            long key = packRegion(regionX(snapshot.chunkX()), regionZ(snapshot.chunkZ()));
            RegionState state = regionStates.computeIfAbsent(key, ignored -> newRegionState(
                    snapshot.dimensionId(),
                    regionX(snapshot.chunkX()),
                    regionZ(snapshot.chunkZ())));
            state.put(snapshot, quality == null ? MarketWebMapTileQuality.SERVER_REGION_SCAN : quality);
            int partialFlushChunks = effectivePartialRegionFlushChunks(
                    state.bestQuality(),
                    configuredInt(ModConfig::marketWebPartialRegionFlushChunks, 32));
            if ((state.complete() || state.count() >= partialFlushChunks) && !state.renderScheduled) {
                state.renderScheduled = true;
                readyStates.add(state);
                regionStates.remove(key);
            }
            collectOverflowRegionStates(readyStates);
        }
        for (RegionState ready : readyStates) {
            scheduleRegionRender(cache, ready, nowMillis);
        }
    }

    private void acceptMissingSnapshot(MarketWebMapTileCache cache,
                                       String dimensionId,
                                       int chunkX,
                                       int chunkZ,
                                       MarketWebMapTileQuality quality,
                                       long nowMillis) {
        List<RegionState> readyStates = new ArrayList<>();
        synchronized (this) {
            long key = packRegion(regionX(chunkX), regionZ(chunkZ));
            RegionState state = regionStates.computeIfAbsent(key, ignored -> newRegionState(
                    dimensionId,
                    regionX(chunkX),
                    regionZ(chunkZ)));
            state.missing(chunkX, chunkZ, quality == null ? MarketWebMapTileQuality.SERVER_REGION_SCAN : quality);
            int partialFlushChunks = effectivePartialRegionFlushChunks(
                    state.bestQuality(),
                    configuredInt(ModConfig::marketWebPartialRegionFlushChunks, 32));
            if ((state.complete() || state.count() >= partialFlushChunks) && !state.renderScheduled) {
                state.renderScheduled = true;
                readyStates.add(state);
                regionStates.remove(key);
            }
            collectOverflowRegionStates(readyStates);
        }
        for (RegionState ready : readyStates) {
            scheduleRegionRender(cache, ready, nowMillis);
        }
    }

    private void collectOverflowRegionStates(List<RegionState> readyStates) {
        int maxTracked = Math.max(1, configuredInt(ModConfig::marketWebMaxTrackedRegionStates, 512));
        while (regionStates.size() > maxTracked) {
            Iterator<Map.Entry<Long, RegionState>> iterator = regionStates.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            Map.Entry<Long, RegionState> eldest = iterator.next();
            iterator.remove();
            RegionState state = eldest.getValue();
            if (state != null && state.complete() && !state.renderScheduled) {
                state.renderScheduled = true;
                readyStates.add(state);
            }
        }
    }

    private void scheduleRegionRender(MarketWebMapTileCache cache, RegionState state, long nowMillis) {
        renderingRegions.incrementAndGet();
        renderExecutor.execute(() -> {
            try {
                MarketWebMapRegionImage image = new MarketWebMapRegionImage(state.dimensionId, state.regionX, state.regionZ);
                for (int localX = 0; localX < CHUNKS_PER_REGION_AXIS; localX++) {
                    int[] lastY = seedLastY(state.regionX, state.regionZ, localX);
                    for (int localZ = 0; localZ < CHUNKS_PER_REGION_AXIS; localZ++) {
                        MarketWebMapChunkSnapshot snapshot = state.snapshotOrNull(localX, localZ);
                        if (snapshot == null) {
                            if (state.accounted(localX, localZ) || !state.expected(localX, localZ)) {
                                image.markChunkSkipped(
                                        state.regionX * CHUNKS_PER_REGION_AXIS + localX,
                                        state.regionZ * CHUNKS_PER_REGION_AXIS + localZ);
                            }
                            continue;
                        }
                        image.putChunkPixels(snapshot.chunkX(), snapshot.chunkZ(), regionRenderer.renderChunk(snapshot, lastY));
                    }
                }
                cacheBottomRows(state);
                imageIO.submit(() -> {
                    image.writeDirty(cache, state.bestQuality(), nowMillis);
                });
            } catch (RuntimeException exception) {
                LOGGER.warn("Market web map region render failed for {},{}", state.regionX, state.regionZ, exception);
            } finally {
                renderingRegions.decrementAndGet();
            }
        });
    }

    private synchronized RegionState newRegionState(String dimensionId, int regionX, int regionZ) {
        return new RegionState(
                dimensionId,
                regionX,
                regionZ,
                expectedRegionChunks.get(packRegion(regionX, regionZ)));
    }

    private int[] seedLastY(int regionX, int regionZ, int localX) {
        synchronized (this) {
            int[][] cachedRows = bottomRowCache.get(packRegion(regionX, regionZ - 1));
            if (cachedRows != null && localX >= 0 && localX < cachedRows.length && cachedRows[localX] != null) {
                return java.util.Arrays.copyOf(cachedRows[localX], cachedRows[localX].length);
            }
            RegionState north = regionStates.get(packRegion(regionX, regionZ - 1));
            if (north != null) {
                MarketWebMapChunkSnapshot snapshot = north.snapshotOrNull(localX, CHUNKS_PER_REGION_AXIS - 1);
                if (snapshot != null) {
                    return regionRenderer.lastYFromBottomRow(snapshot);
                }
            }
        }
        return regionRenderer.unknownLastY();
    }

    private void cacheBottomRow(MarketWebMapChunkSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        int localZ = Math.floorMod(snapshot.chunkZ(), CHUNKS_PER_REGION_AXIS);
        if (localZ != CHUNKS_PER_REGION_AXIS - 1) {
            return;
        }
        synchronized (this) {
            int[][] rows = bottomRowCache.computeIfAbsent(
                    packRegion(regionX(snapshot.chunkX()), regionZ(snapshot.chunkZ())),
                    ignored -> new int[CHUNKS_PER_REGION_AXIS][]);
            rows[Math.floorMod(snapshot.chunkX(), CHUNKS_PER_REGION_AXIS)] = regionRenderer.lastYFromBottomRow(snapshot);
            while (bottomRowCache.size() > 512) {
                Iterator<Long> iterator = bottomRowCache.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    private void cacheBottomRows(RegionState state) {
        if (state == null) {
            return;
        }
        synchronized (this) {
            int[][] rows = bottomRowCache.computeIfAbsent(
                    packRegion(state.regionX, state.regionZ),
                    ignored -> new int[CHUNKS_PER_REGION_AXIS][]);
            for (int localX = 0; localX < CHUNKS_PER_REGION_AXIS; localX++) {
                MarketWebMapChunkSnapshot snapshot = state.snapshotOrNull(localX, CHUNKS_PER_REGION_AXIS - 1);
                if (snapshot != null) {
                    rows[localX] = regionRenderer.lastYFromBottomRow(snapshot);
                }
            }
            while (bottomRowCache.size() > 512) {
                Iterator<Long> iterator = bottomRowCache.keySet().iterator();
                if (!iterator.hasNext()) {
                    break;
                }
                iterator.next();
                iterator.remove();
            }
        }
    }

    private synchronized MarketWebMapSnapshotManager snapshotManager(ServerLevel level) {
        if (snapshotManager != null && snapshotLevel == level) {
            return snapshotManager;
        }
        snapshotLevel = level;
        int renderThreads = configuredRenderThreads();
        int configuredActiveRequests = configuredInt(ModConfig::marketWebMaxActiveSnapshotRequests, 0);
        int activeRequests = configuredActiveRequests <= 0
                ? renderThreads * 48
                : configuredActiveRequests;
        snapshotManager = new MarketWebMapSnapshotManager(
                (dimensionId, chunkX, chunkZ, quality) -> readSnapshot(level, dimensionId, chunkX, chunkZ, quality),
                configuredInt(ModConfig::marketWebSnapshotCacheSize, MarketWebMapSnapshotManager.DEFAULT_CACHE_SIZE),
                activeRequests,
                true);
        return snapshotManager;
    }

    private CompletableFuture<Optional<MarketWebMapChunkSnapshot>> readSnapshot(ServerLevel level,
                                                                               String dimensionId,
                                                                               int chunkX,
                                                                               int chunkZ,
                                                                               MarketWebMapTileQuality quality) {
        if (level == null || !MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // 注意:这里绝不调 MarketWebMapChunkSnapshot.capture / getChunk —— 该 provider 会在 worker 线程
        // (renderExecutor)执行,而 getChunk 在非主线程上会 join 主线程,与 SnapshotManager 锁构成死锁。
        // 已加载的 chunk 由 submitSnapshot 在主线程旁路 capture,根本不会走到这里。
        // 未加载的 chunk 只走纯异步 NBT 读盘(chunkMap.read 返回 future 不 join 主线程)。
        if (quality != MarketWebMapTileQuality.SERVER_REGION_SCAN) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        try {
            int minBuildHeight = level.getMinBuildHeight();
            int maxBuildHeight = level.getMaxBuildHeight();
            return level.getChunkSource()
                    .chunkMap
                    .read(new ChunkPos(chunkX, chunkZ))
                    .thenApplyAsync(tag -> tag.flatMap(value -> MarketWebMapNbtChunkSnapshotReader.capture(
                            dimensionId,
                            chunkX,
                            chunkZ,
                            value,
                            minBuildHeight,
                            maxBuildHeight)), renderExecutor);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private void restoreProgress(ServerLevel level, RenderJob job) {
        Path file = progressFile(level);
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json == null || !job.type.equals(json.has("type") ? json.get("type").getAsString() : "")) {
                return;
            }
            int chunkIndex = Math.max(0, json.has("chunkIndex") ? json.get("chunkIndex").getAsInt() : 0);
            if (job.fullRender()) {
                int regionIndex = Math.max(0, json.has("regionIndex") ? json.get("regionIndex").getAsInt() : Math.floorDiv(chunkIndex, CHUNKS_PER_REGION));
                int localChunkIndex = Math.max(0, json.has("localChunkIndex") ? json.get("localChunkIndex").getAsInt() : Math.floorMod(chunkIndex, CHUNKS_PER_REGION));
                job.regionIndex = Math.min(regionIndex, job.regions.size());
                job.localChunkIndex = Math.min(localChunkIndex, job.currentRegionChunkCount());
                if (job.regionIndex >= job.regions.size()) {
                    job.localChunkIndex = 0;
                } else if (job.localChunkIndex >= job.currentRegionChunkCount()) {
                    job.localChunkIndex = 0;
                    job.regionIndex++;
                }
            } else {
                job.chunkIndex = Math.min(chunkIndex, job.totalChunks());
            }
            job.processedChunks = Math.min(job.completedChunkCount(), Math.max(0,
                    json.has("processedChunks") ? json.get("processedChunks").getAsInt() : job.completedChunkCount()));
            job.processedRegions = job.completedRegionCount();
            job.lastSavedChunkIndex = job.processedChunks;
        } catch (RuntimeException | IOException exception) {
            LOGGER.warn("Failed to read market web map render progress", exception);
        }
    }

    private void saveProgress(ServerLevel level, RenderJob job) {
        Path file = progressFile(level);
        if (file == null || job == null) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("type", job.type);
        json.addProperty("chunkIndex", job.completedChunkCount());
        json.addProperty("regionIndex", job.regionIndex);
        json.addProperty("localChunkIndex", job.localChunkIndex);
        json.addProperty("processedChunks", job.processedChunks);
        json.addProperty("processedRegions", job.processedRegions);
        json.addProperty("totalChunks", job.totalChunks());
        json.addProperty("totalRegions", job.totalRegions());
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            LOGGER.warn("Failed to save market web map render progress", exception);
        }
    }

    private void clearProgress(ServerLevel level) {
        Path file = progressFile(level);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            LOGGER.warn("Failed to clear market web map render progress", exception);
        }
    }

    private Path progressFile(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(MarketWebMapConstants.CACHE_DATA_DIR)
                .resolve(PROGRESS_FILE)
                .normalize();
    }

    private Path dirtyChunksFile(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(MarketWebMapConstants.CACHE_DATA_DIR)
                .resolve(DIRTY_CHUNKS_FILE)
                .normalize();
    }

    private Path dirtyRegionsFile(ServerLevel level) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        return level.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve(MarketWebMapConstants.CACHE_DATA_DIR)
                .resolve(DIRTY_REGIONS_FILE)
                .normalize();
    }

    private void expandDirtyRegions(ServerLevel level) {
        int maxDirtyChunks = Math.max(1, configuredInt(ModConfig::marketWebMaxDirtyChunks, 200000));
        int available = maxDirtyChunks - dirtyChunks.size();
        if (available <= 0) {
            return;
        }
        int budget = Math.min(available, Math.max(1, configuredInt(ModConfig::marketWebDirtyRegionChunksPerInterval, 512)));
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks = dirtyRegions.expandChunks(budget);
        if (chunks.isEmpty()) {
            return;
        }
        for (MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk : chunks) {
            dirtyChunks.markDirty(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ());
        }
        saveDirtyChunks(level);
    }

    private static int configuredRenderThreads() {
        int configured = configuredInt(ModConfig::marketWebRenderThreads, 0);
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
    }

    static int effectivePartialRegionFlushChunks(MarketWebMapTileQuality quality, int configuredValue) {
        return CHUNKS_PER_REGION;
    }

    private static int configuredInt(IntSupplier supplier, int fallback) {
        try {
            return supplier.getAsInt();
        } catch (IllegalStateException exception) {
            return fallback;
        }
    }

    private static int regionX(int chunkX) {
        return Math.floorDiv(chunkX, CHUNKS_PER_REGION_AXIS);
    }

    private static int regionZ(int chunkZ) {
        return Math.floorDiv(chunkZ, CHUNKS_PER_REGION_AXIS);
    }

    private static long packRegion(int regionX, int regionZ) {
        return ((long) regionX & 0xFFFFFFFFL) | (((long) regionZ & 0xFFFFFFFFL) << 32);
    }

    static List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> areaChunksForTest(int x1, int z1, int x2, int z2) {
        return areaChunks(x1, z1, x2, z2);
    }

    private static List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> areaChunks(int x1, int z1, int x2, int z2) {
        int minBlockX = Math.min(x1, x2);
        int maxBlockX = Math.max(x1, x2);
        int minBlockZ = Math.min(z1, z2);
        int maxBlockZ = Math.max(z1, z2);
        int minChunkX = Math.floorDiv(minBlockX, MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(maxBlockX, MarketWebMapConstants.CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(minBlockZ, MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(maxBlockZ, MarketWebMapConstants.CHUNK_SIZE);
        return chunkRectangle(MarketWebMapConstants.OVERWORLD, minChunkX, minChunkZ, maxChunkX, maxChunkZ);
    }

    private static List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunkRectangle(String dimensionId,
                                                                                   int minChunkX,
                                                                                   int minChunkZ,
                                                                                   int maxChunkX,
                                                                                   int maxChunkZ) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || minChunkX > maxChunkX
                || minChunkZ > maxChunkZ) {
            return List.of();
        }
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks = new ArrayList<>();
        int minRegionX = regionX(minChunkX);
        int maxRegionX = regionX(maxChunkX);
        int minRegionZ = regionZ(minChunkZ);
        int maxRegionZ = regionZ(maxChunkZ);
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            int regionMinChunkZ = Math.max(minChunkZ, regionZ * CHUNKS_PER_REGION_AXIS);
            int regionMaxChunkZ = Math.min(maxChunkZ, regionZ * CHUNKS_PER_REGION_AXIS + CHUNKS_PER_REGION_AXIS - 1);
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                int regionMinChunkX = Math.max(minChunkX, regionX * CHUNKS_PER_REGION_AXIS);
                int regionMaxChunkX = Math.min(maxChunkX, regionX * CHUNKS_PER_REGION_AXIS + CHUNKS_PER_REGION_AXIS - 1);
                for (int chunkZ = regionMinChunkZ; chunkZ <= regionMaxChunkZ; chunkZ++) {
                    for (int chunkX = regionMinChunkX; chunkX <= regionMaxChunkX; chunkX++) {
                        chunks.add(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(dimensionId, chunkX, chunkZ));
                    }
                }
            }
        }
        return List.copyOf(chunks);
    }

    private void prepareExpectedRegionChunks(List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks) {
        expectedRegionChunks.clear();
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        Map<Long, List<Integer>> byRegion = new LinkedHashMap<>();
        for (MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk : chunks) {
            if (chunk == null || !MarketWebMapConstants.OVERWORLD.equals(chunk.dimensionId())) {
                continue;
            }
            int regionX = regionX(chunk.chunkX());
            int regionZ = regionZ(chunk.chunkZ());
            int localX = Math.floorMod(chunk.chunkX(), CHUNKS_PER_REGION_AXIS);
            int localZ = Math.floorMod(chunk.chunkZ(), CHUNKS_PER_REGION_AXIS);
            byRegion.computeIfAbsent(packRegion(regionX, regionZ), ignored -> new ArrayList<>())
                    .add(localZ * CHUNKS_PER_REGION_AXIS + localX);
        }
        expectedRegionChunks.putAll(byRegion);
    }

    private static List<MarketWebMapRegionScanService.RegionFile> filterRegionFilesToBlockBounds(
            List<MarketWebMapRegionScanService.RegionFile> regions,
            int minBlockX,
            int minBlockZ,
            int maxBlockX,
            int maxBlockZ) {
        if (regions == null || regions.isEmpty()) {
            return List.of();
        }
        int minChunkX = Math.floorDiv(Math.min(minBlockX, maxBlockX), MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkX = Math.floorDiv(Math.max(minBlockX, maxBlockX), MarketWebMapConstants.CHUNK_SIZE);
        int minChunkZ = Math.floorDiv(Math.min(minBlockZ, maxBlockZ), MarketWebMapConstants.CHUNK_SIZE);
        int maxChunkZ = Math.floorDiv(Math.max(minBlockZ, maxBlockZ), MarketWebMapConstants.CHUNK_SIZE);
        List<MarketWebMapRegionScanService.RegionFile> filtered = new ArrayList<>();
        for (MarketWebMapRegionScanService.RegionFile region : regions) {
            if (region == null || region.localChunks().isEmpty()) {
                continue;
            }
            int baseChunkX = region.regionX() * CHUNKS_PER_REGION_AXIS;
            int baseChunkZ = region.regionZ() * CHUNKS_PER_REGION_AXIS;
            List<Integer> localChunks = new ArrayList<>();
            for (Integer localChunk : region.localChunks()) {
                if (localChunk == null) {
                    continue;
                }
                int localX = Math.floorMod(localChunk, CHUNKS_PER_REGION_AXIS);
                int localZ = Math.floorDiv(localChunk, CHUNKS_PER_REGION_AXIS);
                int chunkX = baseChunkX + localX;
                int chunkZ = baseChunkZ + localZ;
                if (chunkX >= minChunkX && chunkX <= maxChunkX && chunkZ >= minChunkZ && chunkZ <= maxChunkZ) {
                    localChunks.add(localChunk);
                }
            }
            if (!localChunks.isEmpty()) {
                filtered.add(new MarketWebMapRegionScanService.RegionFile(region.regionX(), region.regionZ(), localChunks));
            }
        }
        return List.copyOf(filtered);
    }

    private static BlockBounds blockBoundsFromWorldBorder(WorldBorder border) {
        return new BlockBounds(
                floorToIntClamped(border.getMinX()),
                floorToIntClamped(border.getMinZ()),
                ceilMinusOneToIntClamped(border.getMaxX()),
                ceilMinusOneToIntClamped(border.getMaxZ()));
    }

    private static int floorToIntClamped(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        if (value <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (value >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.floor(value);
    }

    private static int ceilMinusOneToIntClamped(double value) {
        if (Double.isNaN(value)) {
            return 0;
        }
        double block = Math.ceil(value) - 1.0D;
        if (block <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (block >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) block;
    }

    private record BlockBounds(int minX, int minZ, int maxX, int maxZ) {
    }

    public record RenderStatus(String activeJob,
                               boolean paused,
                               int queueSize,
                               int activeSnapshotRequests,
                               int pendingSnapshotRequests,
                               int cachedSnapshots,
                               int dirtyRegions,
                               int dirtyChunks,
                               int trackedRegions,
                               int renderingRegions,
                               int pendingImageIo,
                               int failedChunks,
                               int currentRegionX,
                               int currentRegionZ,
                               int currentLocalChunk,
                               int processedChunks,
                               int totalChunks,
                               int processedRegions,
                               int totalRegions) {
        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("activeJob", activeJob);
            json.addProperty("paused", paused);
            json.addProperty("queueSize", queueSize);
            json.addProperty("activeSnapshotRequests", activeSnapshotRequests);
            json.addProperty("pendingSnapshotRequests", pendingSnapshotRequests);
            json.addProperty("cachedSnapshots", cachedSnapshots);
            json.addProperty("dirtyRegions", dirtyRegions);
            json.addProperty("dirtyChunks", dirtyChunks);
            json.addProperty("trackedRegions", trackedRegions);
            json.addProperty("renderingRegions", renderingRegions);
            json.addProperty("pendingImageIo", pendingImageIo);
            json.addProperty("failedChunks", failedChunks);
            json.addProperty("currentRegionX", currentRegionX);
            json.addProperty("currentRegionZ", currentRegionZ);
            json.addProperty("currentLocalChunk", currentLocalChunk);
            json.addProperty("processedChunks", processedChunks);
            json.addProperty("totalChunks", totalChunks);
            json.addProperty("processedRegions", processedRegions);
            json.addProperty("totalRegions", totalRegions);
            return json;
        }
    }

    private static final class RegionState {
        private final String dimensionId;
        private final int regionX;
        private final int regionZ;
        private final MarketWebMapChunkSnapshot[] snapshots = new MarketWebMapChunkSnapshot[CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS];
        private final boolean[] accountedChunks = new boolean[CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS];
        private final boolean[] expectedChunks;
        private final int expectedCount;
        private MarketWebMapTileQuality bestQuality = MarketWebMapTileQuality.UNKNOWN;
        private int count;
        private boolean renderScheduled;

        private RegionState(String dimensionId, int regionX, int regionZ, List<Integer> expectedLocalChunks) {
            this.dimensionId = dimensionId;
            this.regionX = regionX;
            this.regionZ = regionZ;
            if (expectedLocalChunks == null || expectedLocalChunks.isEmpty()) {
                this.expectedChunks = null;
                this.expectedCount = snapshots.length;
            } else {
                this.expectedChunks = new boolean[snapshots.length];
                int expected = 0;
                for (Integer localChunk : expectedLocalChunks) {
                    if (localChunk == null || localChunk < 0 || localChunk >= snapshots.length) {
                        continue;
                    }
                    if (!this.expectedChunks[localChunk]) {
                        this.expectedChunks[localChunk] = true;
                        expected++;
                    }
                }
                this.expectedCount = expected <= 0 ? snapshots.length : expected;
            }
        }

        private void put(MarketWebMapChunkSnapshot snapshot, MarketWebMapTileQuality quality) {
            int localX = snapshot.chunkX() - regionX * CHUNKS_PER_REGION_AXIS;
            int localZ = snapshot.chunkZ() - regionZ * CHUNKS_PER_REGION_AXIS;
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return;
            }
            int index = localZ * CHUNKS_PER_REGION_AXIS + localX;
            if (!accountedChunks[index]) {
                count++;
                accountedChunks[index] = true;
            }
            snapshots[index] = snapshot;
            if (quality != null && quality.priority() > bestQuality.priority()) {
                bestQuality = quality;
            }
        }

        private void missing(int chunkX, int chunkZ, MarketWebMapTileQuality quality) {
            int localX = chunkX - regionX * CHUNKS_PER_REGION_AXIS;
            int localZ = chunkZ - regionZ * CHUNKS_PER_REGION_AXIS;
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return;
            }
            int index = localZ * CHUNKS_PER_REGION_AXIS + localX;
            if (!accountedChunks[index]) {
                accountedChunks[index] = true;
                count++;
            }
            if (quality != null && quality.priority() > bestQuality.priority()) {
                bestQuality = quality;
            }
        }

        private boolean complete() {
            return count >= expectedCount;
        }

        private int count() {
            return count;
        }

        private MarketWebMapChunkSnapshot snapshot(int localX, int localZ) {
            MarketWebMapChunkSnapshot snapshot = snapshotOrNull(localX, localZ);
            if (snapshot == null) {
                throw new IllegalStateException("Region is missing chunk " + localX + "," + localZ);
            }
            return snapshot;
        }

        private MarketWebMapChunkSnapshot snapshotOrNull(int localX, int localZ) {
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return null;
            }
            return snapshots[localZ * CHUNKS_PER_REGION_AXIS + localX];
        }

        private boolean accounted(int localX, int localZ) {
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return false;
            }
            return accountedChunks[localZ * CHUNKS_PER_REGION_AXIS + localX];
        }

        private boolean expected(int localX, int localZ) {
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return false;
            }
            return expectedChunks == null || expectedChunks[localZ * CHUNKS_PER_REGION_AXIS + localX];
        }

        private MarketWebMapTileQuality bestQuality() {
            return bestQuality == MarketWebMapTileQuality.UNKNOWN ? MarketWebMapTileQuality.SERVER_REGION_SCAN : bestQuality;
        }
    }

    private static final class RenderJob {
        private final String type;
        private final List<MarketWebMapRegionScanService.RegionFile> regions;
        private final List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks;
        private final int radiusBlocks;
        private final int totalRegions;
        private int regionIndex;
        private int localChunkIndex;
        private int chunkIndex;
        private int processedChunks;
        private int processedRegions;
        private int lastSavedChunkIndex;

        private RenderJob(String type,
                          List<MarketWebMapRegionScanService.RegionFile> regions,
                          List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks,
                          int radiusBlocks) {
            this.type = type;
            this.regions = regions == null ? List.of() : List.copyOf(regions);
            this.chunks = chunks == null ? List.of() : List.copyOf(chunks);
            this.radiusBlocks = radiusBlocks;
            this.totalRegions = this.regions.isEmpty() ? countRegions(this.chunks) : this.regions.size();
        }

        private static RenderJob full(List<MarketWebMapRegionScanService.RegionFile> regions) {
            return new RenderJob("fullrender", regions, null, 0);
        }

        private static RenderJob radius(List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks, int radiusBlocks) {
            return new RenderJob("radiusrender", null, chunks, radiusBlocks);
        }

        private static RenderJob area(List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks) {
            return new RenderJob("arearender", null, chunks, 0);
        }

        private static RenderJob border(List<MarketWebMapRegionScanService.RegionFile> regions) {
            return new RenderJob("borderrender", regions, null, 0);
        }

        private MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk() {
            if (fullRender()) {
                MarketWebMapRegionScanService.RegionFile region = regions.get(regionIndex);
                int localChunk = region.localChunks().get(localChunkIndex);
                int localX = Math.floorMod(localChunk, CHUNKS_PER_REGION_AXIS);
                int localZ = Math.floorDiv(localChunk, CHUNKS_PER_REGION_AXIS);
                return new MarketWebMapDirtyChunkQueue.ChunkCoordinate(
                        MarketWebMapConstants.OVERWORLD,
                        region.regionX() * CHUNKS_PER_REGION_AXIS + localX,
                        region.regionZ() * CHUNKS_PER_REGION_AXIS + localZ);
            }
            return chunks.get(chunkIndex);
        }

        private void advance() {
            if (fullRender()) {
                localChunkIndex++;
                if (localChunkIndex >= currentRegionChunkCount()) {
                    localChunkIndex = 0;
                    regionIndex++;
                }
                return;
            }
            chunkIndex++;
        }

        private boolean done() {
            if (fullRender()) {
                return regionIndex >= regions.size();
            }
            return chunkIndex >= chunks.size();
        }

        private int totalChunks() {
            if (fullRender()) {
                int total = 0;
                for (MarketWebMapRegionScanService.RegionFile region : regions) {
                    total += region.localChunks().size();
                }
                return total;
            }
            return chunks.size();
        }

        private int totalRegions() {
            return totalRegions;
        }

        private int completedRegionCount() {
            if (fullRender()) {
                return Math.min(regionIndex, regions.size());
            }
            return countRegions(chunks.subList(0, Math.min(chunkIndex, chunks.size())));
        }

        private int completedChunkCount() {
            if (fullRender()) {
                return Math.min(totalChunks(), completedChunksBeforeRegion(regionIndex) + localChunkIndex);
            }
            return Math.min(chunkIndex, chunks.size());
        }

        private int currentRegionX() {
            if (fullRender()) {
                return done() || regions.isEmpty() ? 0 : regions.get(regionIndex).regionX();
            }
            if (done() || chunks.isEmpty()) {
                return 0;
            }
            return regionX(chunks.get(Math.min(chunkIndex, chunks.size() - 1)).chunkX());
        }

        private int currentRegionZ() {
            if (fullRender()) {
                return done() || regions.isEmpty() ? 0 : regions.get(regionIndex).regionZ();
            }
            if (done() || chunks.isEmpty()) {
                return 0;
            }
            return regionZ(chunks.get(Math.min(chunkIndex, chunks.size() - 1)).chunkZ());
        }

        private int currentLocalChunk() {
            if (fullRender()) {
                return done() ? 0 : regions.get(regionIndex).localChunks().get(localChunkIndex);
            }
            if (done() || chunks.isEmpty()) {
                return 0;
            }
            MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk = chunks.get(Math.min(chunkIndex, chunks.size() - 1));
            int localX = Math.floorMod(chunk.chunkX(), CHUNKS_PER_REGION_AXIS);
            int localZ = Math.floorMod(chunk.chunkZ(), CHUNKS_PER_REGION_AXIS);
            return localZ * CHUNKS_PER_REGION_AXIS + localX;
        }

        private boolean fullRender() {
            return !regions.isEmpty();
        }

        private int currentRegionChunkCount() {
            if (!fullRender() || regionIndex < 0 || regionIndex >= regions.size()) {
                return 0;
            }
            return regions.get(regionIndex).localChunks().size();
        }

        private int completedChunksBeforeRegion(int endRegionIndex) {
            int total = 0;
            int limit = Math.min(Math.max(0, endRegionIndex), regions.size());
            for (int i = 0; i < limit; i++) {
                total += regions.get(i).localChunks().size();
            }
            return total;
        }

        @SuppressWarnings("unused")
        private int radiusBlocks() {
            return radiusBlocks;
        }

        private static int countRegions(List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks) {
            Set<Long> regions = new HashSet<>();
            for (MarketWebMapDirtyChunkQueue.ChunkCoordinate chunk : chunks) {
                regions.add(packRegion(regionX(chunk.chunkX()), regionZ(chunk.chunkZ())));
            }
            return regions.size();
        }
    }
}
