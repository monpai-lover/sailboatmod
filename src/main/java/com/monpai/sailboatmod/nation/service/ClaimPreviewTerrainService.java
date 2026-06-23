package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.TerrainPreviewSavedData;
import com.monpai.sailboatmod.roadplanner.map.OfflineChunkSnapshotReader;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

public final class ClaimPreviewTerrainService {
    @FunctionalInterface
    interface TileSampler {
        int[] sample(String dimensionId, int chunkX, int chunkZ);
    }

    @FunctionalInterface
    interface ChunkResolver {
        ChunkAccess resolve(int chunkX, int chunkZ, boolean load);
    }

    private static final int DEFAULT_COLOR = 0xFF33414A;
    private static final int WATER_COLOR = 0xFF4466B0;
    private static final int FALLBACK_GRASS_COLOR = 0xFF000000 | (MapColor.GRASS.col & 0x00FFFFFF);
    private static final int DEFAULT_VISIBLE_BUDGET_PER_TICK = 16;
    private static final int DEFAULT_PREFETCH_BUDGET_PER_TICK = 16;
    private static final int QUEUE_AROUND_PREFETCH_RADIUS = 1;
    private static final AtomicReference<ClaimPreviewTerrainService> ACTIVE = new AtomicReference<>();

    /** Sub-samples per chunk axis (2 = 2x2 sub-chunks per chunk, each 8x8 blocks). */
    public static final int SUB = 2;

    private record TileRequest(String dimensionId, int chunkX, int chunkZ, String viewportKey) {
    }

    private record ViewportRequestState(String dimensionId,
                                        int centerChunkX,
                                        int centerChunkZ,
                                        int radius,
                                        int prefetchRadius,
                                        long revision,
                                        String screenKey) {
    }

    private record ResolvedTileRequest(String dimensionId,
                                       int chunkX,
                                       int chunkZ,
                                       String viewportKey,
                                       ChunkAccess chunk) {
    }

    private record SampledTile(String dimensionId,
                               int chunkX,
                               int chunkZ,
                               String viewportKey,
                               int[] tile) {
    }

    private final Queue<TileRequest> visibleQueue = new ConcurrentLinkedQueue<>();
    private final Queue<TileRequest> prefetchQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<String, int[]> hotTiles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> chunkToViewportDependencies = new ConcurrentHashMap<>();
    private final Queue<String> invalidatedViewportKeys = new ConcurrentLinkedQueue<>();
    private final Set<String> invalidatedViewportKeySet = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, ViewportRequestState> viewportRequests = new ConcurrentHashMap<>();
    private final Set<String> visibleQueuedKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> prefetchQueuedKeys = ConcurrentHashMap.newKeySet();

    // 2026-06:未加载区块改"投后台读 region NBT 补画",而非跳过不画(见 plan)。
    // NBT 读 chunkMap.read().get(timeout) 只能在 worker 跑(主线程跑会卡服)。
    private static final long NBT_READ_TIMEOUT_MS = 2000L;
    private final ExecutorService nbtExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "SailboatClaimTerrainNbt");
        thread.setDaemon(true);
        return thread;
    });
    // 已投递后台 NBT 读、尚未返回的 chunk(防重复投递)。key=tileKey。
    private final Set<String> nbtPending = ConcurrentHashMap.newKeySet();
    // 后台读完成的瓦片,等主线程 tick 回填(storeTile + markViewportDirty 必须主线程)。
    private final Queue<SampledTile> nbtCompleted = new ConcurrentLinkedQueue<>();

    public ClaimPreviewTerrainService() {
    }

    public static void onServerStarted(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ACTIVE.set(new ClaimPreviewTerrainService());
    }

    public static void onServerStopping(MinecraftServer server) {
        onServerStopping();
    }

    public static void onServerStopping() {
        ClaimPreviewTerrainService service = ACTIVE.getAndSet(null);
        if (service != null) {
            service.shutdown();
        }
    }

    static void setActiveForTest(ClaimPreviewTerrainService service) {
        ClaimPreviewTerrainService previous = ACTIVE.getAndSet(service);
        if (previous != null && previous != service) {
            previous.shutdown();
        }
    }

    static void clearActiveForTest() {
        ClaimPreviewTerrainService service = ACTIVE.getAndSet(null);
        if (service != null) {
            service.shutdown();
        }
    }

    public static ClaimPreviewTerrainService get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        return ACTIVE.get();
    }

    static void enqueueViewportWork(String dimensionId,
                                    int centerChunkX,
                                    int centerChunkZ,
                                    int radius,
                                    int prefetchRadius,
                                    long revision,
                                    String screenKey) {
        ClaimPreviewTerrainService service = ACTIVE.get();
        if (service == null) {
            return;
        }
        service.enqueueViewport(dimensionId, centerChunkX, centerChunkZ, radius, prefetchRadius, revision, screenKey);
    }

    static void trackViewportDependency(String dimensionId, int chunkX, int chunkZ, String screenKey) {
        ClaimPreviewTerrainService service = ACTIVE.get();
        if (service == null) {
            return;
        }
        service.addViewportDependency(dimensionId, chunkX, chunkZ, screenKey);
    }

    public static List<String> consumeInvalidatedViewportKeys() {
        ClaimPreviewTerrainService service = ACTIVE.get();
        if (service == null) {
            return List.of();
        }
        return service.consumeInvalidatedViewportKeysInternal();
    }

    public static List<Integer> sample(ServerLevel level, ChunkPos centerChunk, int radius) {
        int diameter = radius * 2 + 1;
        if (level == null || centerChunk == null || radius < 0) {
            return filledDefaults(diameter * diameter * SUB * SUB);
        }
        ClaimPreviewTerrainService service = get(level.getServer());
        if (service == null) {
            return filledDefaults(diameter * diameter * SUB * SUB);
        }
        String dimensionId = level.dimension().location().toString();
        service.enqueueViewport(dimensionId, centerChunk.x, centerChunk.z, radius, 0, 0L, "legacy|sample");

        ArrayList<Integer> pixels = new ArrayList<>(diameter * diameter * SUB * SUB);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int[] tile = service.getTile(level, dimensionId, centerChunk.x + dx, centerChunk.z + dz);
                if (tile == null) {
                    tile = defaultTile();
                }
                for (int color : tile) {
                    pixels.add(color);
                }
            }
        }
        return List.copyOf(pixels);
    }

    public static void tick(ServerLevel level) {
        if (level == null) {
            return;
        }
        ClaimPreviewTerrainService service = get(level.getServer());
        if (service == null) {
            return;
        }
        service.processBudgetedWork(level, DEFAULT_VISIBLE_BUDGET_PER_TICK, DEFAULT_PREFETCH_BUDGET_PER_TICK);
    }

    public static void invalidateChunk(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        if (dimension == null) {
            return;
        }
        ClaimPreviewTerrainService service = ACTIVE.get();
        if (service == null) {
            return;
        }
        service.invalidateChunkInternal(dimension.location().toString(), chunkX, chunkZ, null);
    }

    public static void invalidateChunk(Level level, int chunkX, int chunkZ) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        ClaimPreviewTerrainService service = get(serverLevel.getServer());
        if (service == null) {
            return;
        }
        service.invalidateChunkNow(serverLevel, chunkX, chunkZ);
    }

    public static void clearCache() {
        ClaimPreviewTerrainService service = ACTIVE.get();
        if (service == null) {
            return;
        }
        service.clearRuntimeCache();
    }

    public static void unregisterViewport(String logicalScreenKey) {
        if (logicalScreenKey == null || logicalScreenKey.isBlank()) {
            return;
        }
        ClaimPreviewTerrainService service = ACTIVE.get();
        if (service == null) {
            return;
        }
        service.unregisterViewportInternal(logicalScreenKey);
    }

    public static int[] getCachedTileForViewport(String dimensionId, int chunkX, int chunkZ) {
        ClaimPreviewTerrainService service = ACTIVE.get();
        if (service == null || dimensionId == null || dimensionId.isBlank()) {
            return null;
        }
        return service.getHotTile(dimensionId, chunkX, chunkZ);
    }

    public static void warmViewportFromPersisted(ServerLevel level,
                                                 String dimensionId,
                                                 int centerChunkX,
                                                 int centerChunkZ,
                                                 int radius,
                                                 int prefetchRadius) {
        if (level == null || dimensionId == null || dimensionId.isBlank()) {
            return;
        }
        ClaimPreviewTerrainService service = get(level.getServer());
        if (service == null) {
            return;
        }
        service.warmViewportFromPersistedInternal(level, dimensionId, centerChunkX, centerChunkZ, radius, prefetchRadius);
    }

    public static void queueAround(ServerLevel level, ChunkPos centerChunk, int radius) {
        if (level == null || centerChunk == null || radius < 0) {
            return;
        }
        ClaimPreviewTerrainService service = get(level.getServer());
        if (service == null) {
            return;
        }
        service.enqueueViewport(
                level.dimension().location().toString(),
                centerChunk.x,
                centerChunk.z,
                radius,
                QUEUE_AROUND_PREFETCH_RADIUS,
                0L,
                "legacy|queueAround"
        );
    }

    public static void clearAllPersistedColors(ServerLevel level) {
        if (level == null) {
            return;
        }
        TerrainPreviewSavedData.get(level).clearAll();
        clearCache();
    }

    public void enqueueViewport(String dimensionId,
                                int centerChunkX,
                                int centerChunkZ,
                                int radius,
                                int prefetchRadius,
                                long revision,
                                String screenKey) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return;
        }
        int clampedRadius = Math.max(0, radius);
        int clampedPrefetchRadius = Math.max(0, prefetchRadius);
        String normalizedScreenKey = screenKey == null ? "" : screenKey;
        String requestKey = normalizedScreenKey.isBlank()
                ? ClaimMapViewportService.viewportKey(normalizedScreenKey, dimensionId, centerChunkX, centerChunkZ, revision)
                : normalizedScreenKey;
        viewportRequests.put(requestKey, new ViewportRequestState(
                dimensionId,
                centerChunkX,
                centerChunkZ,
                clampedRadius,
                clampedPrefetchRadius,
                revision,
                normalizedScreenKey
        ));
        enqueueArea(visibleQueue, visibleQueuedKeys, dimensionId, centerChunkX, centerChunkZ, clampedRadius, -1, requestKey);
        int outerRadius = clampedRadius + clampedPrefetchRadius;
        if (outerRadius > clampedRadius) {
            enqueueArea(prefetchQueue, prefetchQueuedKeys, dimensionId, centerChunkX, centerChunkZ, outerRadius, clampedRadius, requestKey);
        }
    }

    public void processBudgetedWork(ServerLevel level, int visibleBudget, int prefetchBudget) {
        if (level == null) {
            return;
        }
        String dimensionId = level.dimension().location().toString();
        drainQueue(level, visibleQueue, visibleQueuedKeys, dimensionId, Math.max(0, visibleBudget));
        if (visibleQueue.isEmpty()) {
            drainQueue(level, prefetchQueue, prefetchQueuedKeys, dimensionId, Math.max(0, prefetchBudget));
        }
    }

    public void invalidateChunkNow(ServerLevel level, int chunkX, int chunkZ) {
        if (level == null) {
            return;
        }
        invalidateChunkInternal(level.dimension().location().toString(), chunkX, chunkZ, level);
    }

    private void enqueueArea(Queue<TileRequest> queue,
                             Set<String> queueKeys,
                             String dimensionId,
                             int centerChunkX,
                             int centerChunkZ,
                             int radius,
                             int excludeRadius,
                             String viewportKey) {
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (excludeRadius >= 0 && Math.abs(dx) <= excludeRadius && Math.abs(dz) <= excludeRadius) {
                    continue;
                }
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                String queueKey = queueKey(dimensionId, chunkX, chunkZ);
                if (!queueKeys.add(queueKey)) {
                    continue;
                }
                queue.add(new TileRequest(dimensionId, chunkX, chunkZ, viewportKey));
            }
        }
    }

    private void drainQueue(ServerLevel level,
                            Queue<TileRequest> queue,
                            Set<String> queueKeys,
                            String levelDimensionId,
                            int budget) {
        if (budget <= 0) {
            return;
        }
        TerrainPreviewSavedData savedData = TerrainPreviewSavedData.get(level);
        // 先回填后台 NBT 读完成的瓦片(主线程做 storeTile + markViewportDirty)。
        drainNbtCompleted(savedData);
        List<ResolvedTileRequest> batch = new ArrayList<>(budget);
        for (int processed = 0; processed < budget; processed++) {
            TileRequest request = queue.poll();
            if (request == null) {
                break;
            }
            queueKeys.remove(queueKey(request.dimensionId(), request.chunkX(), request.chunkZ()));
            if (!levelDimensionId.equals(request.dimensionId())) {
                continue;
            }
            LevelChunk chunk = null;
            try {
                chunk = level.getChunkSource().getChunkNow(request.chunkX(), request.chunkZ());
            } catch (Exception ignored) {
                chunk = null;
            }
            if (chunk == null) {
                // 区块未加载:改为投后台离线读 region NBT 补画(不再跳过不画)。读到后回填到 nbtCompleted。
                dispatchOfflineRead(level, request);
                continue;
            }
            batch.add(new ResolvedTileRequest(
                    request.dimensionId(),
                    request.chunkX(),
                    request.chunkZ(),
                    request.viewportKey(),
                    chunk
            ));
        }
        for (SampledTile sampledTile : sampleResolvedBatch(batch)) {
            storeTile(sampledTile.dimensionId(), sampledTile.chunkX(), sampledTile.chunkZ(), sampledTile.tile(), savedData);
            markViewportDirty(sampledTile.viewportKey());
        }
    }

    /** 把未加载区块的离线 NBT 读投到后台 worker。读到→转 2×2 子色→塞 nbtCompleted(等主线程回填);读不到则放弃(保持原"不画")。 */
    private void dispatchOfflineRead(ServerLevel level, TileRequest request) {
        if (!OfflineChunkSnapshotReader.OVERWORLD.equals(request.dimensionId())) {
            return; // 仅主世界支持离线 NBT;其它维度保持原行为(跳过)。
        }
        String key = tileKey(request.dimensionId(), request.chunkX(), request.chunkZ());
        if (!nbtPending.add(key)) {
            return; // 已在后台读中,不重复投递。
        }
        int chunkX = request.chunkX();
        int chunkZ = request.chunkZ();
        String dimensionId = request.dimensionId();
        String viewportKey = request.viewportKey();
        nbtExecutor.execute(() -> {
            try {
                Optional<RoadMapColumnSample[]> samples =
                        OfflineChunkSnapshotReader.readOffThread(level, chunkX, chunkZ, NBT_READ_TIMEOUT_MS);
                if (samples.isPresent()) {
                    int[] tile = sampleSubColorsFromOffline(samples.get());
                    nbtCompleted.offer(new SampledTile(dimensionId, chunkX, chunkZ, viewportKey, tile));
                }
            } catch (Throwable ignored) {
                // 读失败放弃,保持原"未加载不画"行为,绝不让 worker 异常逃逸。
            } finally {
                nbtPending.remove(key);
            }
        });
    }

    /** 主线程回填后台读完成的瓦片(storeTile + markViewportDirty 必须主线程)。 */
    private void drainNbtCompleted(TerrainPreviewSavedData savedData) {
        for (SampledTile tile = nbtCompleted.poll(); tile != null; tile = nbtCompleted.poll()) {
            storeTile(tile.dimensionId(), tile.chunkX(), tile.chunkZ(), tile.tile(), savedData);
            markViewportDirty(tile.viewportKey());
        }
    }

    /** 离线 NBT 列样本(16×16)→ 2×2 子色:对每个子单元中心点(sx*8+4)取对应列的 baseArgb。 */
    private static int[] sampleSubColorsFromOffline(RoadMapColumnSample[] samples) {
        if (samples == null) {
            return null;
        }
        int cellSize = 16 / SUB;
        int[] result = new int[SUB * SUB];
        for (int sz = 0; sz < SUB; sz++) {
            for (int sx = 0; sx < SUB; sx++) {
                int centerX = sx * cellSize + cellSize / 2;
                int centerZ = sz * cellSize + cellSize / 2;
                int index = centerZ * 16 + centerX;
                RoadMapColumnSample sample = index >= 0 && index < samples.length ? samples[index] : null;
                result[sz * SUB + sx] = sample == null ? DEFAULT_COLOR : sample.baseArgb();
            }
        }
        return result;
    }

    void processBudgetedWorkForTest(int visibleBudget, int prefetchBudget, TileSampler sampler) {
        drainQueueForTest(visibleQueue, visibleQueuedKeys, Math.max(0, visibleBudget), sampler);
        if (!visibleQueue.isEmpty()) {
            return;
        }
        drainQueueForTest(prefetchQueue, prefetchQueuedKeys, Math.max(0, prefetchBudget), sampler);
    }

    private void drainQueueForTest(Queue<TileRequest> queue,
                                   Set<String> queueKeys,
                                   int budget,
                                   TileSampler sampler) {
        if (budget <= 0) {
            return;
        }
        List<TileRequest> batch = new ArrayList<>(budget);
        for (int processed = 0; processed < budget; processed++) {
            TileRequest request = queue.poll();
            if (request == null) {
                break;
            }
            queueKeys.remove(queueKey(request.dimensionId(), request.chunkX(), request.chunkZ()));
            batch.add(request);
        }
        if (sampler == null || batch.isEmpty()) {
            return;
        }
        for (TileRequest request : batch) {
            int[] tile = sampler.sample(request.dimensionId(), request.chunkX(), request.chunkZ());
            if (tile == null) {
                continue;
            }
            storeTile(request.dimensionId(), request.chunkX(), request.chunkZ(), tile, null);
            markViewportDirty(request.viewportKey());
        }
    }

    private void clearRuntimeCache() {
        visibleQueue.clear();
        prefetchQueue.clear();
        hotTiles.clear();
        chunkToViewportDependencies.clear();
        invalidatedViewportKeys.clear();
        invalidatedViewportKeySet.clear();
        viewportRequests.clear();
        visibleQueuedKeys.clear();
        prefetchQueuedKeys.clear();
    }

    private List<SampledTile> sampleResolvedBatch(List<ResolvedTileRequest> batch) {
        if (batch == null || batch.isEmpty()) {
            return List.of();
        }
        List<SampledTile> sampledTiles = new ArrayList<>(batch.size());
        for (ResolvedTileRequest request : batch) {
            sampledTiles.add(new SampledTile(
                    request.dimensionId(),
                    request.chunkX(),
                    request.chunkZ(),
                    request.viewportKey(),
                    request.chunk() == null ? null : sampleChunkSubColors(request.chunk(), request.chunkX(), request.chunkZ())
            ));
        }
        return sampledTiles;
    }

    private void unregisterViewportInternal(String logicalScreenKey) {
        viewportRequests.remove(logicalScreenKey);
        removeInvalidatedViewportKey(logicalScreenKey);
        removeViewportDependencies(logicalScreenKey);
        removeQueuedViewportWork(logicalScreenKey);
    }

    private void removeInvalidatedViewportKey(String logicalScreenKey) {
        if (!invalidatedViewportKeySet.remove(logicalScreenKey)) {
            return;
        }
        List<String> retained = new ArrayList<>();
        for (String key = invalidatedViewportKeys.poll(); key != null; key = invalidatedViewportKeys.poll()) {
            if (!logicalScreenKey.equals(key)) {
                retained.add(key);
            }
        }
        invalidatedViewportKeys.addAll(retained);
    }

    private void removeViewportDependencies(String logicalScreenKey) {
        chunkToViewportDependencies.forEach((chunkKey, dependentViewports) -> {
            if (dependentViewports == null) {
                return;
            }
            dependentViewports.remove(logicalScreenKey);
            if (dependentViewports.isEmpty()) {
                chunkToViewportDependencies.remove(chunkKey, dependentViewports);
            }
        });
    }

    private void removeQueuedViewportWork(String logicalScreenKey) {
        removeQueuedViewportWork(visibleQueue, visibleQueuedKeys, logicalScreenKey);
        removeQueuedViewportWork(prefetchQueue, prefetchQueuedKeys, logicalScreenKey);
    }

    private void removeQueuedViewportWork(Queue<TileRequest> queue,
                                          Set<String> queueKeys,
                                          String logicalScreenKey) {
        if (queue.isEmpty()) {
            return;
        }
        List<TileRequest> retained = new ArrayList<>();
        for (TileRequest request = queue.poll(); request != null; request = queue.poll()) {
            if (!logicalScreenKey.equals(request.viewportKey())) {
                retained.add(request);
            }
        }
        queueKeys.clear();
        for (TileRequest request : retained) {
            queue.offer(request);
            queueKeys.add(queueKey(request.dimensionId(), request.chunkX(), request.chunkZ()));
        }
    }

    private void storeTile(String dimensionId, int chunkX, int chunkZ, int[] tile, TerrainPreviewSavedData savedData) {
        if (tile == null) {
            return;
        }
        hotTiles.put(tileKey(dimensionId, chunkX, chunkZ), tile);
        if (savedData != null) {
            savedData.putTile(dimensionId, chunkX, chunkZ, tile);
        }
    }

    private int[] getTile(ServerLevel level, String dimensionId, int chunkX, int chunkZ) {
        String key = tileKey(dimensionId, chunkX, chunkZ);
        int[] hot = hotTiles.get(key);
        if (hot != null) {
            return hot.clone();
        }

        TerrainPreviewSavedData savedData = TerrainPreviewSavedData.get(level);
        int[] persisted = savedData.getTile(dimensionId, chunkX, chunkZ);
        if (persisted != null) {
            hotTiles.putIfAbsent(key, persisted.clone());
            return persisted;
        }

        int[] sampled = sampleChunkSubColors(level, chunkX, chunkZ);
        if (sampled != null) {
            hotTiles.put(key, sampled);
            savedData.putTile(dimensionId, chunkX, chunkZ, sampled);
            return sampled.clone();
        }
        return null;
    }

    private int[] getHotTile(String dimensionId, int chunkX, int chunkZ) {
        int[] hot = hotTiles.get(tileKey(dimensionId, chunkX, chunkZ));
        return hot == null ? null : hot.clone();
    }

    private void warmViewportFromPersistedInternal(ServerLevel level,
                                                   String dimensionId,
                                                   int centerChunkX,
                                                   int centerChunkZ,
                                                   int radius,
                                                   int prefetchRadius) {
        TerrainPreviewSavedData savedData = TerrainPreviewSavedData.get(level);
        int outerRadius = Math.max(0, radius) + Math.max(0, prefetchRadius);
        for (int dz = -outerRadius; dz <= outerRadius; dz++) {
            for (int dx = -outerRadius; dx <= outerRadius; dx++) {
                int chunkX = centerChunkX + dx;
                int chunkZ = centerChunkZ + dz;
                String key = tileKey(dimensionId, chunkX, chunkZ);
                if (hotTiles.containsKey(key)) {
                    continue;
                }
                int[] persisted = savedData.getTile(dimensionId, chunkX, chunkZ);
                if (persisted != null) {
                    hotTiles.putIfAbsent(key, persisted.clone());
                }
            }
        }
    }

    private void addViewportDependency(String dimensionId, int chunkX, int chunkZ, String screenKey) {
        if (dimensionId == null || dimensionId.isBlank() || screenKey == null || screenKey.isBlank()) {
            return;
        }
        chunkToViewportDependencies
                .computeIfAbsent(tileKey(dimensionId, chunkX, chunkZ), ignored -> ConcurrentHashMap.newKeySet())
                .add(screenKey);
    }

    private void invalidateChunkInternal(String dimensionId, int chunkX, int chunkZ, ServerLevel level) {
        String key = tileKey(dimensionId, chunkX, chunkZ);
        hotTiles.remove(key);
        visibleQueuedKeys.remove(queueKey(dimensionId, chunkX, chunkZ));
        prefetchQueuedKeys.remove(queueKey(dimensionId, chunkX, chunkZ));
        if (level != null) {
            TerrainPreviewSavedData.get(level).removeTile(dimensionId, chunkX, chunkZ);
        }
        queueChangedChunkForResample(dimensionId, chunkX, chunkZ);
        Set<String> dependentScreenKeys = chunkToViewportDependencies.remove(key);
        if (dependentScreenKeys != null) {
            for (String screenKey : dependentScreenKeys) {
                requeueViewport(screenKey);
            }
        }
    }

    private void queueChangedChunkForResample(String dimensionId, int chunkX, int chunkZ) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return;
        }
        String queueKey = queueKey(dimensionId, chunkX, chunkZ);
        if (!visibleQueuedKeys.add(queueKey)) {
            return;
        }
        visibleQueue.offer(new TileRequest(dimensionId, chunkX, chunkZ, ""));
    }

    private void requeueViewport(String screenKey) {
        if (screenKey == null || screenKey.isBlank()) {
            return;
        }
        if (invalidatedViewportKeySet.add(screenKey)) {
            invalidatedViewportKeys.offer(screenKey);
        }
        ViewportRequestState requestState = viewportRequests.get(screenKey);
        if (requestState == null) {
            return;
        }
        enqueueViewport(
                requestState.dimensionId(),
                requestState.centerChunkX(),
                requestState.centerChunkZ(),
                requestState.radius(),
                requestState.prefetchRadius(),
                requestState.revision(),
                screenKey
        );
    }

    private void markViewportDirty(String screenKey) {
        if (screenKey == null || screenKey.isBlank()) {
            return;
        }
        if (invalidatedViewportKeySet.add(screenKey)) {
            invalidatedViewportKeys.offer(screenKey);
        }
    }

    private int[] sampleChunkSubColors(ServerLevel level, int chunkX, int chunkZ) {
        return sampleChunkSubColors(chunkX, chunkZ,
                (x, z, load) -> level.getChunkSource().getChunkNow(x, z));
    }

    private int[] sampleChunkSubColors(int chunkX, int chunkZ, ChunkResolver chunkResolver) {
        if (chunkResolver == null) {
            return null;
        }
        try {
            ChunkAccess chunk = chunkResolver.resolve(chunkX, chunkZ, false);
            if (chunk == null) {
                return null;
            }
            return sampleChunkSubColors(chunk, chunkX, chunkZ);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int[] sampleChunkSubColors(ChunkAccess chunk, int chunkX, int chunkZ) {
        int cellSize = 16 / SUB;
        int[] result = new int[SUB * SUB];
        for (int sz = 0; sz < SUB; sz++) {
            for (int sx = 0; sx < SUB; sx++) {
                int centerX = sx * cellSize + cellSize / 2;
                int centerZ = sz * cellSize + cellSize / 2;
                int[] sampled = sampleBlockColorAndHeight(chunk, chunkX, chunkZ, centerX, centerZ);
                result[sz * SUB + sx] = sampled[0];
            }
        }
        return result;
    }

    private static int[] sampleBlockColorAndHeight(ChunkAccess chunk, int chunkX, int chunkZ, int localX, int localZ) {
        int worldX = (chunkX << 4) + localX;
        int worldZ = (chunkZ << 4) + localZ;
        int worldY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX & 15, localZ & 15) - 1;
        if (worldY < chunk.getMinBuildHeight()) {
            return new int[] {FALLBACK_GRASS_COLOR, worldY};
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
        BlockState state = chunk.getBlockState(pos);
        while (state.isAir() && worldY > chunk.getMinBuildHeight()) {
            worldY--;
            pos.set(worldX, worldY, worldZ);
            state = chunk.getBlockState(pos);
        }
        if (state.getFluidState().is(FluidTags.WATER)) {
            return new int[] {WATER_COLOR, worldY};
        }
        MapColor mapColor = state.getMapColor(chunk, pos);
        if (mapColor == null || mapColor == MapColor.NONE || mapColor.col == 0) {
            return new int[] {FALLBACK_GRASS_COLOR, worldY};
        }
        if (mapColor == MapColor.DIRT) {
            mapColor = MapColor.GRASS;
        }
        return new int[] {0xFF000000 | (mapColor.col & 0x00FFFFFF), worldY};
    }

    private static List<Integer> filledDefaults(int size) {
        ArrayList<Integer> values = new ArrayList<>(Math.max(0, size));
        int[] tile = defaultTile();
        for (int i = 0; i < size / tile.length; i++) {
            for (int color : tile) {
                values.add(color);
            }
        }
        while (values.size() < size) {
            values.add(DEFAULT_COLOR);
        }
        return List.copyOf(values);
    }

    private static int[] defaultTile() {
        int[] tile = new int[SUB * SUB];
        Arrays.fill(tile, DEFAULT_COLOR);
        return tile;
    }

    private static String tileKey(String dimensionId, int chunkX, int chunkZ) {
        return dimensionId + "|" + chunkX + "|" + chunkZ;
    }

    private static String queueKey(String dimensionId, int chunkX, int chunkZ) {
        return dimensionId + ":" + chunkX + ":" + chunkZ;
    }

    private List<String> consumeInvalidatedViewportKeysInternal() {
        if (invalidatedViewportKeys.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> drained = new LinkedHashSet<>();
        for (String key = invalidatedViewportKeys.poll(); key != null; key = invalidatedViewportKeys.poll()) {
            invalidatedViewportKeySet.remove(key);
            drained.add(key);
        }
        return drained.isEmpty() ? List.of() : List.copyOf(drained);
    }

    void enqueueViewportForTest(String dimensionId,
                                int centerChunkX,
                                int centerChunkZ,
                                int radius,
                                int prefetchRadius,
                                long revision,
                                String screenKey) {
        enqueueViewport(dimensionId, centerChunkX, centerChunkZ, radius, prefetchRadius, revision, screenKey);
    }

    void putTileForTest(String dimensionId, int chunkX, int chunkZ, int[] colors) {
        hotTiles.put(tileKey(dimensionId, chunkX, chunkZ), colors);
    }

    int[] getTileForTest(String dimensionId, int chunkX, int chunkZ) {
        return hotTiles.get(tileKey(dimensionId, chunkX, chunkZ));
    }

    void putViewportDependencyForTest(String dimensionId, int chunkX, int chunkZ, String viewportKey) {
        chunkToViewportDependencies
                .computeIfAbsent(tileKey(dimensionId, chunkX, chunkZ), ignored -> ConcurrentHashMap.newKeySet())
                .add(viewportKey);
    }

    int[] sampleChunkSubColorsForTest(int chunkX, int chunkZ, ChunkResolver resolver) {
        return sampleChunkSubColors(chunkX, chunkZ, resolver);
    }

    void clearQueuedWorkForTest() {
        visibleQueue.clear();
        prefetchQueue.clear();
        visibleQueuedKeys.clear();
        prefetchQueuedKeys.clear();
    }

    void invalidateChunkForTest(String dimensionId, int chunkX, int chunkZ) {
        invalidateChunkInternal(dimensionId, chunkX, chunkZ, null);
    }

    int visibleQueueSizeForTest() {
        return visibleQueue.size();
    }

    int prefetchQueueSizeForTest() {
        return prefetchQueue.size();
    }

    List<String> invalidatedViewportKeysForTest() {
        return List.copyOf(invalidatedViewportKeySet);
    }

    boolean hasViewportRequestForTest(String viewportKey) {
        return viewportRequests.containsKey(viewportKey);
    }

    boolean hasViewportDependencyForTest(String dimensionId, int chunkX, int chunkZ, String viewportKey) {
        Set<String> dependentKeys = chunkToViewportDependencies.get(tileKey(dimensionId, chunkX, chunkZ));
        return dependentKeys != null && dependentKeys.contains(viewportKey);
    }

    private void shutdown() {
        nbtExecutor.shutdownNow(); // 关后台离线读 worker,避免换 ACTIVE 实例时旧线程池泄漏。
    }
}
