package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.TerrainPreviewSavedData;
import com.monpai.sailboatmod.util.OfflineChunkNbtReader;
import com.monpai.sailboatmod.util.OfflineChunkPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
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
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
    // 远处未加载区块:照 Xaero「绝不 force 生成」,改为后台 IOWorker 异步读盘(chunkMap.read 不生成)+ 后台自解颜色。
    // 主线程只发起异步读、不阻塞;区块不在盘上(未生成/未探索)→ 留空。彻底消除 force 同步生成卡服(watchdog 60s 崩)。
    private static final int MAX_READS_IN_FLIGHT = 64; // 同时在途异步读上限(背压,防一次发起过多)
    private static final int MAX_DECODES_PER_TICK = 16; // 每 tick 派发后台解码的完成读上限
    private static final long READ_TIMEOUT_MS = 2000L; // 单次后台读盘等待上限
    // 在途读超过此 tick 数还没完成 → 丢弃(避免卡死队列),该瓦片下次 viewport 重入时再读。
    private static final int MAX_READ_WAIT_TICKS = 100;
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
    // 在途异步读:chunkKey(asLong) → PendingRead(chunkMap.read future + 发起 tick)。仅主线程读写(发起/查完成/超时)。
    private final java.util.Map<Long, PendingRead> inFlightReads = new java.util.HashMap<>();
    // 后台解码完成的颜色结果:后台执行器写,主线程 processBudgetedWork 读出 storeTile(SavedData 主线程 only)。
    private final Queue<SampledTile> decodedResults = new ConcurrentLinkedQueue<>();
    // 后台 IOWorker 解码线程(单线程 daemon):从独占 tag 自解颜色,绝不碰主线程区块/SavedData。懒启动。
    private volatile ExecutorService decodeExecutor;
    private long currentTick;

    /** 一个在途异步读盘:future(chunkMap.read 不生成) + 发起 tick + 该瓦片的标识(供完成后后台解码采样)。 */
    private record PendingRead(CompletableFuture<Optional<CompoundTag>> future,
                               long startedTick,
                               String dimensionId,
                               int chunkX,
                               int chunkZ,
                               String viewportKey) {
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

    public void processBudgetedWork(ServerLevel level, int visibleBudget, int prefetchBudget) {
        if (level == null) {
            return;
        }
        currentTick++;
        // (a) 完成的在途异步读 → 派后台执行器自解颜色(主线程只发派、不解码、不阻塞)。
        dispatchCompletedReads(level);
        // (b) 后台解码完成的结果 → 主线程 storeTile + markViewportDirty(SavedData 主线程 only)。
        drainDecodedResults(level);
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
            // 未加载:照 Xaero 发起后台异步读盘(chunkMap.read 不生成),完成后后台自解颜色回主线程存。
            // 绝不 force(force 未生成区块会同步生成卡服崩)。瓦片不进 batch(batch 只装 getChunkNow 命中)。
            beginAsyncRead(level, request);
        }
        for (SampledTile sampledTile : sampleResolvedBatch(batch)) {
            storeTile(sampledTile.dimensionId(), sampledTile.chunkX(), sampledTile.chunkZ(), sampledTile.tile(), savedData);
            markViewportDirty(sampledTile.viewportKey());
        }
    }

    /**
     * 未加载区块:发起后台异步读盘(chunkMap.read 走 IOWorker,<b>绝不生成区块</b>)。去重(在途/已发起跳过)+ 背压
     * (在途上限)。完成后由 {@link #dispatchCompletedReads} 派后台执行器自解颜色。瓦片不回队(完成后采样写缓存,
     * viewport 仍引用该 chunk → markViewportDirty 触发前端重取)。
     */
    private void beginAsyncRead(ServerLevel level, TileRequest request) {
        long chunkKey = ChunkPos.asLong(request.chunkX(), request.chunkZ());
        if (inFlightReads.containsKey(chunkKey)) {
            return; // 已在读,等完成
        }
        if (inFlightReads.size() >= MAX_READS_IN_FLIGHT) {
            return; // 背压:在途太多,本 tick 不再发起(瓦片下次 viewport 重入时再读)
        }
        CompletableFuture<Optional<CompoundTag>> future =
                OfflineChunkNbtReader.beginProbe(level, new ChunkPos(request.chunkX(), request.chunkZ()));
        inFlightReads.put(chunkKey, new PendingRead(future, currentTick,
                request.dimensionId(), request.chunkX(), request.chunkZ(), request.viewportKey()));
    }

    /**
     * 主线程:遍历在途异步读,完成的(future done)取出独占 tag → 派后台执行器自解颜色(主线程不解码、不阻塞);
     * 超时未完成的丢弃。每 tick 最多派 {@link #MAX_DECODES_PER_TICK} 个。
     */
    private void dispatchCompletedReads(ServerLevel level) {
        if (inFlightReads.isEmpty()) {
            return;
        }
        int minBuild = level.getMinBuildHeight();
        int maxBuild = level.getMaxBuildHeight();
        int dispatched = 0;
        java.util.Iterator<java.util.Map.Entry<Long, PendingRead>> it = inFlightReads.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Long, PendingRead> e = it.next();
            PendingRead read = e.getValue();
            if (!read.future().isDone()) {
                if (currentTick - read.startedTick() >= MAX_READ_WAIT_TICKS) {
                    it.remove(); // 等太久:丢弃,下次 viewport 重入再读
                }
                continue;
            }
            it.remove();
            if (dispatched >= MAX_DECODES_PER_TICK) {
                continue; // 本 tick 派满:已完成的也丢弃(下次重入再读,避免一次堆积过多后台任务)
            }
            Optional<CompoundTag> tag;
            try {
                tag = read.future().getNow(Optional.empty());
            } catch (Throwable t) {
                continue;
            }
            // 磁盘上没有(未生成/未探索)或非 full → 留空,不派后台。
            if (OfflineChunkNbtReader.classify(tag) != OfflineChunkNbtReader.ChunkStatusClass.FULL) {
                continue;
            }
            CompoundTag chunkTag = tag.get(); // 独占新对象,可交后台安全解码
            dispatched++;
            decodeExecutor().execute(() -> {
                int[] tile = sampleTileFromNbt(chunkTag, read.chunkX(), read.chunkZ(), minBuild, maxBuild);
                if (tile != null) {
                    decodedResults.offer(new SampledTile(read.dimensionId(), read.chunkX(), read.chunkZ(),
                            read.viewportKey(), tile));
                }
            });
        }
    }

    /** 主线程:把后台解码完成的颜色结果写进缓存/SavedData(SavedData 主线程 only)+ 标脏视口。 */
    private void drainDecodedResults(ServerLevel level) {
        if (decodedResults.isEmpty()) {
            return;
        }
        TerrainPreviewSavedData savedData = TerrainPreviewSavedData.get(level);
        String levelDimensionId = level.dimension().location().toString();
        SampledTile result;
        while ((result = decodedResults.poll()) != null) {
            // 只把属于本 level 的写进它的 savedData;hotTiles 跨维度共用(键含 dimId),都写。
            TerrainPreviewSavedData target = levelDimensionId.equals(result.dimensionId()) ? savedData : null;
            storeTile(result.dimensionId(), result.chunkX(), result.chunkZ(), result.tile(), target);
            markViewportDirty(result.viewportKey());
        }
    }

    private ExecutorService decodeExecutor() {
        ExecutorService exec = decodeExecutor;
        if (exec == null) {
            synchronized (this) {
                exec = decodeExecutor;
                if (exec == null) {
                    exec = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "ClaimPreview-NbtDecode");
                        t.setDaemon(true);
                        return t;
                    });
                    decodeExecutor = exec;
                }
            }
        }
        return exec;
    }

    /**
     * <b>后台线程</b>:从独占 NBT tag 自解出 2×2 颜色 tile,口径与 {@link #sampleBlockColorAndHeight}(已加载区块版)一致:
     * WORLD_SURFACE 表面高度(NBT 无 heightmap 则自顶向下扫,跳噪声装饰)→ 表面 BlockState(NbtUtils.readBlockState 重建)
     * → 判水 FluidTags.WATER → getMapColor(null,pos) → dirt→grass。tag 是调用方独占新对象,后台解码无并发。
     */
    private static int[] sampleTileFromNbt(CompoundTag chunkTag, int chunkX, int chunkZ, int minBuild, int maxBuild) {
        try {
            OfflineChunkPalette.OfflineChunkBlocks blocks = OfflineChunkPalette.decode(chunkTag, minBuild, maxBuild);
            if (blocks.isEmpty()) {
                return null;
            }
            int cellSize = 16 / SUB;
            int[] result = new int[SUB * SUB];
            for (int sz = 0; sz < SUB; sz++) {
                for (int sx = 0; sx < SUB; sx++) {
                    int localX = sx * cellSize + cellSize / 2;
                    int localZ = sz * cellSize + cellSize / 2;
                    result[sz * SUB + sx] = sampleColorFromNbt(blocks, chunkX, chunkZ, localX, localZ);
                }
            }
            return result;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 后台:一个子格的表面颜色(口径对齐 sampleBlockColorAndHeight)。 */
    private static int sampleColorFromNbt(OfflineChunkPalette.OfflineChunkBlocks blocks,
                                          int chunkX, int chunkZ, int localX, int localZ) {
        int worldX = (chunkX << 4) + localX;
        int worldZ = (chunkZ << 4) + localZ;
        int surfaceTop = blocks.firstAvailableHeight(localX, localZ, OfflineChunkPalette::isDefaultSurfaceNoise);
        int worldY = surfaceTop - 1;
        if (worldY < blocks.minBuildHeight()) {
            return FALLBACK_GRASS_COLOR;
        }
        CompoundTag entry = blocks.paletteTag(localX, worldY, localZ);
        if (entry == null) {
            return FALLBACK_GRASS_COLOR;
        }
        BlockState state;
        try {
            state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), entry);
        } catch (Throwable t) {
            return FALLBACK_GRASS_COLOR;
        }
        if (state == null) {
            return FALLBACK_GRASS_COLOR;
        }
        if (state.getFluidState().is(FluidTags.WATER)) {
            return WATER_COLOR;
        }
        MapColor mapColor;
        try {
            // 离线无 BlockGetter:传 null + 真实世界坐标 pos(绝大多数方块忽略 getter;个别解 null → catch 退 fallback)。
            mapColor = state.getMapColor(null, new BlockPos(worldX, worldY, worldZ));
        } catch (Throwable t) {
            return FALLBACK_GRASS_COLOR;
        }
        if (mapColor == null || mapColor == MapColor.NONE || mapColor.col == 0) {
            return FALLBACK_GRASS_COLOR;
        }
        if (mapColor == MapColor.DIRT) {
            mapColor = MapColor.GRASS;
        }
        return 0xFF000000 | (mapColor.col & 0x00FFFFFF);
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
        inFlightReads.clear();
        decodedResults.clear();
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
        // 关停:关后台解码线程 + 清在途读/解码结果队列。读盘走 vanilla IOWorker(随 server 停止自然卸载),无 force 票据残留。
        ExecutorService exec = decodeExecutor;
        if (exec != null) {
            decodeExecutor = null;
            try {
                exec.shutdownNow();
                exec.awaitTermination(2L, TimeUnit.SECONDS);
            } catch (Throwable ignored) {
                Thread.currentThread().interrupt();
            }
        }
        inFlightReads.clear();
        decodedResults.clear();
    }
}
