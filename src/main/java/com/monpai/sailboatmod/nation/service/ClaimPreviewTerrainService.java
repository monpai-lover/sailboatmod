package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.nation.data.TerrainPreviewSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraftforge.common.world.ForgeChunkManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
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
    // 远处未加载区块的两档 force 节流(force 是主线程操作:已生成只读盘较快,未生成要同步跑地形生成最重)。
    // 绝不后台读 NBT(会崩:pendingWrites 共享引用被 vanilla 并发 datafix 改写 HashMap)。force 是 vanilla 唯一安全方式。
    private static final int MAX_GENERATED_FORCE_PER_TICK = 16;
    private static final int MAX_UNGENERATED_FORCE_PER_TICK = 2;
    // 探测区块是否已生成:主线程异步发起 chunkMap.read(不阻塞),后续 tick 看 isDone 读 Status 单字段判档。
    // 超过此 tick 数还没读完 → 保守当未生成走慢档(宁慢不卡)。
    private static final int MAX_PROBE_WAIT_TICKS = 40;
    // 单 tick 慢档(未生成)force 的累计 wall-clock 上限(ms),超了就停、剩余下 tick(比纯计数更稳,生成耗时差异大)。
    private static final long MAX_UNGENERATED_FORCE_MILLIS_PER_TICK = 35L;
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
    // 已生成探测:chunkKey(asLong) → 异步 chunkMap.read future + 发起 tick(超时降级慢档)。仅主线程读写。
    private final java.util.Map<Long, ChunkProbe> pendingProbe = new java.util.HashMap<>();
    // 已 force 加载未释放的 chunk(防重复 force;关停遍历释放票据)。dimId → set(chunkKey)。仅主线程读写。
    private final java.util.Map<String, Set<Long>> forcedChunks = new java.util.HashMap<>();
    private long currentTick;

    private record ChunkProbe(CompletableFuture<Optional<CompoundTag>> future, long startedTick) {
    }

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

    // 每 tick force 配额(visible+prefetch 两次 drain 共享),processBudgetedWork 开头重置。
    private int genForcedThisTick;
    private int ungenForcedThisTick;
    private long ungenForceMillisThisTick;

    public void processBudgetedWork(ServerLevel level, int visibleBudget, int prefetchBudget) {
        if (level == null) {
            return;
        }
        currentTick++;
        genForcedThisTick = 0;
        ungenForcedThisTick = 0;
        ungenForceMillisThisTick = 0L;
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
            if (chunk != null) {
                // 已加载内存:最快路径,直接采样(不变)。
                batch.add(new ResolvedTileRequest(
                        request.dimensionId(),
                        request.chunkX(),
                        request.chunkZ(),
                        request.viewportKey(),
                        chunk));
                continue;
            }
            // 未加载:两档节流 force(已生成快档 / 未生成慢档),采到的直接进 savedData(不进 batch,batch 只装 getChunkNow 命中)。
            forceLoadAndSample(level, queue, queueKeys, request, savedData);
        }
        for (SampledTile sampledTile : sampleResolvedBatch(batch)) {
            storeTile(sampledTile.dimensionId(), sampledTile.chunkX(), sampledTile.chunkZ(), sampledTile.tile(), savedData);
            markViewportDirty(sampledTile.viewportKey());
        }
    }

    /**
     * 未加载区块的两档 force 加载 + 采样。先异步探测是否已生成(只读 Status,不解码地形,不后台读),按档限速 force,
     * 采到即进 savedData,采不到/未决/配额满则把瓦片回队等下 tick。force 即采即放票据(try/finally),不常驻。
     */
    private void forceLoadAndSample(ServerLevel level,
                                    Queue<TileRequest> queue,
                                    Set<String> queueKeys,
                                    TileRequest request,
                                    TerrainPreviewSavedData savedData) {
        int chunkX = request.chunkX();
        int chunkZ = request.chunkZ();
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);

        Boolean generated = resolveGeneratedOrProbe(level, chunkX, chunkZ, chunkKey);
        if (generated == null) {
            requeue(queue, queueKeys, request); // 探测未决:下 tick 再看
            return;
        }

        // 按档限速:已生成快档 / 未生成慢档(慢档还有 wall-clock 守门)。配额满则回队等下 tick。
        if (generated) {
            if (genForcedThisTick >= MAX_GENERATED_FORCE_PER_TICK) {
                requeue(queue, queueKeys, request);
                return;
            }
        } else {
            if (ungenForcedThisTick >= MAX_UNGENERATED_FORCE_PER_TICK
                    || ungenForceMillisThisTick >= MAX_UNGENERATED_FORCE_MILLIS_PER_TICK) {
                requeue(queue, queueKeys, request);
                return;
            }
        }

        long startNanos = System.nanoTime();
        int[] tile = forceSampleRelease(level, request.dimensionId(), chunkX, chunkZ, chunkKey);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        if (generated) {
            genForcedThisTick++;
        } else {
            ungenForcedThisTick++;
            ungenForceMillisThisTick += elapsedMillis;
        }
        if (tile != null) {
            storeTile(request.dimensionId(), chunkX, chunkZ, tile, savedData);
            markViewportDirty(request.viewportKey());
        } else {
            // force 后同 tick 仍没拿到(罕见):回队下 tick 重试。
            requeue(queue, queueKeys, request);
        }
    }

    /**
     * 判断区块是否已生成存盘:主线程异步发起 {@code chunkMap.read}(只发起异步 IO,不阻塞、不后台解码),
     * 后续 tick 看 isDone 读出 tag 的 Status 字段。返回 TRUE=已生成 / FALSE=未生成 / null=探测未决(下 tick 再看)。
     * 超时 / 非主世界 / 异常 → 保守 FALSE(当未生成走慢档,宁慢不卡)。
     * <p><b>为什么安全</b>:只在主线程读 tag 的 Status 单字段就丢,不遍历整个 HashMap 解码地形 —— 即使偶发命中
     * pendingWrites 共享引用被 datafix 并发改,最坏只是 Status 判错一次(降级慢档),绝不会像整块解码那样桶损坏崩。
     */
    private Boolean resolveGeneratedOrProbe(ServerLevel level, int chunkX, int chunkZ, long chunkKey) {
        ChunkProbe probe = pendingProbe.get(chunkKey);
        if (probe == null) {
            try {
                CompletableFuture<Optional<CompoundTag>> future =
                        level.getChunkSource().chunkMap.read(new ChunkPos(chunkX, chunkZ));
                pendingProbe.put(chunkKey, new ChunkProbe(future, currentTick));
            } catch (Throwable t) {
                return Boolean.FALSE; // 发起失败:保守当未生成
            }
            return null; // 刚发起,未决
        }
        if (!probe.future().isDone()) {
            if (currentTick - probe.startedTick() >= MAX_PROBE_WAIT_TICKS) {
                pendingProbe.remove(chunkKey);
                return Boolean.FALSE; // 等太久:保守当未生成
            }
            return null; // 仍在读盘,下 tick 再看
        }
        pendingProbe.remove(chunkKey);
        try {
            Optional<CompoundTag> tag = probe.future().getNow(Optional.empty());
            if (tag.isEmpty()) {
                return Boolean.FALSE; // 磁盘上没有 → 未生成
            }
            String status = tag.get().getString("Status"); // 只读单字段
            return (status.equals("minecraft:full") || status.equals("full")) ? Boolean.TRUE : Boolean.FALSE;
        } catch (Throwable t) {
            return Boolean.FALSE; // 读 Status 出错:保守当未生成
        }
    }

    /** force 加载该区块(同 tick 同步生成/加载完),getChunkNow 取出采样,try/finally 即采即放票据。采到返回 tile,否则 null。 */
    private int[] forceSampleRelease(ServerLevel level, String dimensionId, int chunkX, int chunkZ, long chunkKey) {
        BlockPos owner = new BlockPos(chunkX << 4, level.getSeaLevel(), chunkZ << 4);
        boolean forced = false;
        try {
            forced = ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, chunkX, chunkZ, true, false);
            if (forced) {
                forcedChunks.computeIfAbsent(dimensionId, ignored -> new LinkedHashSet<>()).add(chunkKey);
            }
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
            if (chunk == null) {
                return null;
            }
            return sampleChunkSubColors(chunk, chunkX, chunkZ);
        } catch (Throwable t) {
            return null;
        } finally {
            if (forced) {
                try {
                    ForgeChunkManager.forceChunk(level, SailboatMod.MODID, owner, chunkX, chunkZ, false, false);
                } catch (Throwable ignored) {
                    // 释放失败不致命:关停时 releaseAllForcedChunks 兜底
                }
                Set<Long> set = forcedChunks.get(dimensionId);
                if (set != null) {
                    set.remove(chunkKey);
                    if (set.isEmpty()) {
                        forcedChunks.remove(dimensionId);
                    }
                }
            }
        }
    }

    /** 瓦片回队等下 tick(去重:queueKeys 没有才加,避免重复堆积)。 */
    private void requeue(Queue<TileRequest> queue, Set<String> queueKeys, TileRequest request) {
        String key = queueKey(request.dimensionId(), request.chunkX(), request.chunkZ());
        if (queueKeys.add(key)) {
            queue.add(request);
        }
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
        pendingProbe.clear();
        forcedChunks.clear();
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
        // force 是即采即放(forceSampleRelease 的 try/finally 同方法内释放),正常残留为空。
        // 关停只清内存探测/记账状态;残留票据由 server 停止时 ForgeChunkManager 随存档卸载自然清理。
        pendingProbe.clear();
        forcedChunks.clear();
    }
}
