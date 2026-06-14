package com.monpai.sailboatmod.market.web.map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.ModConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private static final int CHUNKS_PER_REGION_AXIS = MarketWebMapRegionImage.CHUNKS_PER_REGION_AXIS;

    private final MarketWebMapRegionRenderer regionRenderer = new MarketWebMapRegionRenderer();
    private final MarketWebSquareMapImageIOExecutor imageIO = new MarketWebSquareMapImageIOExecutor();
    private final Map<Long, RegionState> regionStates = new LinkedHashMap<>();
    private final LinkedHashMap<Long, int[][]> bottomRowCache = new LinkedHashMap<>();
    private final AtomicInteger renderingRegions = new AtomicInteger();
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
            if (job == null || paused) {
                return;
            }
            tickCounter++;
            int interval = Math.max(1, configuredInt(ModConfig::marketWebBackgroundIntervalTicks, 200));
            if (tickCounter % interval != 0) {
                return;
            }
        }
        int budget = Math.max(1, configuredInt(ModConfig::marketWebBackgroundMaxChunksPerInterval, 512));
        boolean completedRegion = false;
        synchronized (this) {
            int queued = 0;
            while (queued < budget && activeJob == job && !job.done()) {
                int chunkX = job.chunkX();
                int chunkZ = job.chunkZ();
                if (queue.enqueue(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, nowMillis, MarketWebMapTileQuality.SERVER_REGION_SCAN)) {
                    queued++;
                    job.processedChunks++;
                }
                if (job.advance()) {
                    job.processedRegions++;
                    completedRegion = true;
                }
            }
            if (completedRegion) {
                saveProgress(level, job);
            }
            if (job.done()) {
                clearProgress(level);
                activeJob = null;
                paused = false;
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
        MarketWebMapSnapshotManager manager = snapshotManager(level);
        manager.snapshotDirect(task.dimensionId(), task.chunkX(), task.chunkZ() - 1, task.quality())
                .thenAccept(optional -> optional.ifPresent(this::cacheBottomRow));
        CompletableFuture<Optional<MarketWebMapChunkSnapshot>> future = manager.snapshotWithVerticalNeighbors(
                task.dimensionId(),
                task.chunkX(),
                task.chunkZ(),
                task.quality());
        future.thenAccept(optional -> optional.ifPresent(snapshot ->
                acceptSnapshot(cache, snapshot, task.quality(), nowMillis)));
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
        restoreProgress(level, activeJob);
        paused = false;
        tickCounter = 0;
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
        int minRegionX = Math.floorDiv(minChunkX, CHUNKS_PER_REGION_AXIS);
        int maxRegionX = Math.floorDiv(maxChunkX, CHUNKS_PER_REGION_AXIS);
        int minRegionZ = Math.floorDiv(minChunkZ, CHUNKS_PER_REGION_AXIS);
        int maxRegionZ = Math.floorDiv(maxChunkZ, CHUNKS_PER_REGION_AXIS);
        List<MarketWebMapRegionScanService.RegionFile> regions = new ArrayList<>();
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                regions.add(new MarketWebMapRegionScanService.RegionFile(regionX, regionZ));
            }
        }
        if (regions.isEmpty()) {
            return false;
        }
        activeJob = RenderJob.radius(regions, radiusBlocks);
        paused = false;
        tickCounter = 0;
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
                regionStates.size(),
                renderingRegions.get(),
                imageIO.pendingTasks(),
                job == null ? 0 : job.processedChunks,
                job == null ? 0 : job.totalChunks(),
                job == null ? 0 : job.processedRegions,
                job == null ? 0 : job.regions.size());
    }

    public int pendingTasks() {
        MarketWebMapSnapshotManager manager = snapshotManager;
        return (manager == null ? 0 : manager.activeRequests() + manager.pendingRequests())
                + renderingRegions.get()
                + imageIO.pendingTasks();
    }

    public void shutdown() {
        renderExecutor.shutdownNow();
        imageIO.shutdown();
    }

    private void acceptSnapshot(MarketWebMapTileCache cache,
                                MarketWebMapChunkSnapshot snapshot,
                                MarketWebMapTileQuality quality,
                                long nowMillis) {
        RegionState ready = null;
        synchronized (this) {
            long key = packRegion(regionX(snapshot.chunkX()), regionZ(snapshot.chunkZ()));
            RegionState state = regionStates.computeIfAbsent(key, ignored -> new RegionState(
                    snapshot.dimensionId(),
                    regionX(snapshot.chunkX()),
                    regionZ(snapshot.chunkZ())));
            state.put(snapshot, quality == null ? MarketWebMapTileQuality.SERVER_REGION_SCAN : quality);
            if (state.complete() && !state.renderScheduled) {
                state.renderScheduled = true;
                ready = state;
            }
        }
        if (ready != null) {
            scheduleRegionRender(cache, ready, nowMillis);
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
                        MarketWebMapChunkSnapshot snapshot = state.snapshot(localX, localZ);
                        image.putChunkPixels(snapshot.chunkX(), snapshot.chunkZ(), regionRenderer.renderChunk(snapshot, lastY));
                    }
                }
                cacheBottomRows(state);
                imageIO.submit(() -> {
                    image.writeIfComplete(cache, state.bestQuality(), nowMillis);
                    synchronized (MarketWebMapRenderManager.this) {
                        regionStates.remove(packRegion(state.regionX, state.regionZ));
                    }
                });
            } catch (RuntimeException exception) {
                LOGGER.warn("Market web map region render failed for {},{}", state.regionX, state.regionZ, exception);
            } finally {
                renderingRegions.decrementAndGet();
            }
        });
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
                rows[localX] = regionRenderer.lastYFromBottomRow(state.snapshot(localX, CHUNKS_PER_REGION_AXIS - 1));
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
        Optional<MarketWebMapChunkSnapshot> loaded = MarketWebMapChunkSnapshot.capture(level, chunkX, chunkZ);
        if (loaded.isPresent()) {
            return CompletableFuture.completedFuture(loaded);
        }
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
            job.regionIndex = Math.max(0, json.has("regionIndex") ? json.get("regionIndex").getAsInt() : 0);
            job.nextLocalChunk = Math.max(0, json.has("nextLocalChunk") ? json.get("nextLocalChunk").getAsInt() : 0);
            job.processedChunks = Math.max(0, json.has("processedChunks") ? json.get("processedChunks").getAsInt() : 0);
            job.processedRegions = Math.max(0, json.has("processedRegions") ? json.get("processedRegions").getAsInt() : 0);
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
        json.addProperty("regionIndex", job.regionIndex);
        json.addProperty("nextLocalChunk", job.nextLocalChunk);
        json.addProperty("processedChunks", job.processedChunks);
        json.addProperty("processedRegions", job.processedRegions);
        json.addProperty("totalChunks", job.totalChunks());
        json.addProperty("totalRegions", job.regions.size());
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

    private static int configuredRenderThreads() {
        int configured = configuredInt(ModConfig::marketWebRenderThreads, 0);
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
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

    public record RenderStatus(String activeJob,
                               boolean paused,
                               int queueSize,
                               int activeSnapshotRequests,
                               int pendingSnapshotRequests,
                               int cachedSnapshots,
                               int trackedRegions,
                               int renderingRegions,
                               int pendingImageIo,
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
            json.addProperty("trackedRegions", trackedRegions);
            json.addProperty("renderingRegions", renderingRegions);
            json.addProperty("pendingImageIo", pendingImageIo);
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
        private MarketWebMapTileQuality bestQuality = MarketWebMapTileQuality.UNKNOWN;
        private int count;
        private boolean renderScheduled;

        private RegionState(String dimensionId, int regionX, int regionZ) {
            this.dimensionId = dimensionId;
            this.regionX = regionX;
            this.regionZ = regionZ;
        }

        private void put(MarketWebMapChunkSnapshot snapshot, MarketWebMapTileQuality quality) {
            int localX = snapshot.chunkX() - regionX * CHUNKS_PER_REGION_AXIS;
            int localZ = snapshot.chunkZ() - regionZ * CHUNKS_PER_REGION_AXIS;
            if (localX < 0 || localX >= CHUNKS_PER_REGION_AXIS || localZ < 0 || localZ >= CHUNKS_PER_REGION_AXIS) {
                return;
            }
            int index = localZ * CHUNKS_PER_REGION_AXIS + localX;
            if (snapshots[index] == null) {
                count++;
            }
            snapshots[index] = snapshot;
            if (quality != null && quality.priority() > bestQuality.priority()) {
                bestQuality = quality;
            }
        }

        private boolean complete() {
            return count >= snapshots.length;
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

        private MarketWebMapTileQuality bestQuality() {
            return bestQuality == MarketWebMapTileQuality.UNKNOWN ? MarketWebMapTileQuality.SERVER_REGION_SCAN : bestQuality;
        }
    }

    private static final class RenderJob {
        private final String type;
        private final List<MarketWebMapRegionScanService.RegionFile> regions;
        private final int radiusBlocks;
        private int regionIndex;
        private int nextLocalChunk;
        private int processedChunks;
        private int processedRegions;

        private RenderJob(String type, List<MarketWebMapRegionScanService.RegionFile> regions, int radiusBlocks) {
            this.type = type;
            this.regions = List.copyOf(regions);
            this.radiusBlocks = radiusBlocks;
        }

        private static RenderJob full(List<MarketWebMapRegionScanService.RegionFile> regions) {
            return new RenderJob("fullrender", regions, 0);
        }

        private static RenderJob radius(List<MarketWebMapRegionScanService.RegionFile> regions, int radiusBlocks) {
            return new RenderJob("radiusrender", regions, radiusBlocks);
        }

        private int chunkX() {
            return regions.get(regionIndex).regionX() * CHUNKS_PER_REGION_AXIS + Math.floorMod(nextLocalChunk, CHUNKS_PER_REGION_AXIS);
        }

        private int chunkZ() {
            return regions.get(regionIndex).regionZ() * CHUNKS_PER_REGION_AXIS + Math.floorDiv(nextLocalChunk, CHUNKS_PER_REGION_AXIS);
        }

        private boolean advance() {
            nextLocalChunk++;
            if (nextLocalChunk < CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS) {
                return false;
            }
            nextLocalChunk = 0;
            regionIndex++;
            return true;
        }

        private boolean done() {
            return regionIndex >= regions.size();
        }

        private int totalChunks() {
            return regions.size() * CHUNKS_PER_REGION_AXIS * CHUNKS_PER_REGION_AXIS;
        }
    }
}
