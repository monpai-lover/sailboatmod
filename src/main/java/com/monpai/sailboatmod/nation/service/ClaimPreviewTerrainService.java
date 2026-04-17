package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.TerrainPreviewSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

public final class ClaimPreviewTerrainService {
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

    private final Queue<TileRequest> visibleQueue = new ConcurrentLinkedQueue<>();
    private final Queue<TileRequest> prefetchQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentMap<String, int[]> hotTiles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> chunkToViewportDependencies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ClaimMapViewportSnapshot> viewportSnapshotCache = new ConcurrentHashMap<>();
    private final List<String> invalidatedViewportKeys = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<String, ViewportRequestState> viewportRequests = new ConcurrentHashMap<>();
    private final Set<String> visibleQueuedKeys = ConcurrentHashMap.newKeySet();
    private final Set<String> prefetchQueuedKeys = ConcurrentHashMap.newKeySet();

    public ClaimPreviewTerrainService() {
    }

    public static void onServerStarted(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ACTIVE.set(new ClaimPreviewTerrainService());
    }

    public static void onServerStopping() {
        ACTIVE.set(null);
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

    static void trackViewportDependency(String dimensionId, int chunkX, int chunkZ, String viewportKey) {
        ClaimPreviewTerrainService service = ACTIVE.get();
        if (service == null) {
            return;
        }
        service.addViewportDependency(dimensionId, chunkX, chunkZ, viewportKey);
    }

    static void putViewportSnapshot(String viewportKey, ClaimMapViewportSnapshot snapshot) {
        ClaimPreviewTerrainService service = ACTIVE.get();
        if (service == null || viewportKey == null || viewportKey.isBlank() || snapshot == null) {
            return;
        }
        service.viewportSnapshotCache.put(viewportKey, snapshot);
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
        String viewportKey = viewportKey(screenKey, revision);
        viewportRequests.put(viewportKey, new ViewportRequestState(
                dimensionId,
                centerChunkX,
                centerChunkZ,
                clampedRadius,
                clampedPrefetchRadius,
                revision,
                screenKey == null ? "" : screenKey
        ));
        enqueueArea(visibleQueue, visibleQueuedKeys, dimensionId, centerChunkX, centerChunkZ, clampedRadius, -1, viewportKey);
        int outerRadius = clampedRadius + clampedPrefetchRadius;
        if (outerRadius > clampedRadius) {
            enqueueArea(prefetchQueue, prefetchQueuedKeys, dimensionId, centerChunkX, centerChunkZ, outerRadius, clampedRadius, viewportKey);
        }
    }

    public void processBudgetedWork(ServerLevel level, int visibleBudget, int prefetchBudget) {
        if (level == null) {
            return;
        }
        String dimensionId = level.dimension().location().toString();
        drainQueue(level, visibleQueue, visibleQueuedKeys, dimensionId, Math.max(0, visibleBudget));
        drainQueue(level, prefetchQueue, prefetchQueuedKeys, dimensionId, Math.max(0, prefetchBudget));
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
        TerrainPreviewSavedData savedData = TerrainPreviewSavedData.get(level);
        for (int processed = 0; processed < budget; processed++) {
            TileRequest request = queue.poll();
            if (request == null) {
                return;
            }
            queueKeys.remove(queueKey(request.dimensionId(), request.chunkX(), request.chunkZ()));
            if (!levelDimensionId.equals(request.dimensionId())) {
                continue;
            }
            int[] tile = sampleChunkSubColors(level, request.chunkX(), request.chunkZ());
            if (tile == null) {
                continue;
            }
            hotTiles.put(tileKey(request.dimensionId(), request.chunkX(), request.chunkZ()), tile);
            savedData.putTile(request.dimensionId(), request.chunkX(), request.chunkZ(), tile);
        }
    }

    private void clearRuntimeCache() {
        visibleQueue.clear();
        prefetchQueue.clear();
        hotTiles.clear();
        chunkToViewportDependencies.clear();
        viewportSnapshotCache.clear();
        invalidatedViewportKeys.clear();
        viewportRequests.clear();
        visibleQueuedKeys.clear();
        prefetchQueuedKeys.clear();
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

    private void addViewportDependency(String dimensionId, int chunkX, int chunkZ, String viewportKey) {
        if (dimensionId == null || dimensionId.isBlank() || viewportKey == null || viewportKey.isBlank()) {
            return;
        }
        chunkToViewportDependencies
                .computeIfAbsent(tileKey(dimensionId, chunkX, chunkZ), ignored -> ConcurrentHashMap.newKeySet())
                .add(viewportKey);
    }

    private void invalidateChunkInternal(String dimensionId, int chunkX, int chunkZ, ServerLevel level) {
        String key = tileKey(dimensionId, chunkX, chunkZ);
        hotTiles.remove(key);
        visibleQueuedKeys.remove(queueKey(dimensionId, chunkX, chunkZ));
        prefetchQueuedKeys.remove(queueKey(dimensionId, chunkX, chunkZ));
        if (level != null) {
            TerrainPreviewSavedData.get(level).removeTile(dimensionId, chunkX, chunkZ);
        }
        Set<String> dependentViewportKeys = chunkToViewportDependencies.remove(key);
        if (dependentViewportKeys != null) {
            for (String viewportKey : dependentViewportKeys) {
                viewportSnapshotCache.remove(viewportKey);
                requeueViewport(viewportKey);
            }
        }
    }

    private void requeueViewport(String viewportKey) {
        if (viewportKey == null || viewportKey.isBlank()) {
            return;
        }
        invalidatedViewportKeys.add(viewportKey);
        ViewportRequestState requestState = viewportRequests.get(viewportKey);
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
                requestState.screenKey()
        );
    }

    private int[] sampleChunkSubColors(ServerLevel level, int chunkX, int chunkZ) {
        try {
            ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
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

    private static String viewportKey(String screenKey, long revision) {
        String normalizedScreenKey = screenKey == null ? "" : screenKey;
        return normalizedScreenKey + "|" + revision;
    }

    private static String tileKey(String dimensionId, int chunkX, int chunkZ) {
        return dimensionId + "|" + chunkX + "|" + chunkZ;
    }

    private static String queueKey(String dimensionId, int chunkX, int chunkZ) {
        return dimensionId + ":" + chunkX + ":" + chunkZ;
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

    void invalidateChunkForTest(String dimensionId, int chunkX, int chunkZ) {
        hotTiles.remove(tileKey(dimensionId, chunkX, chunkZ));
        Set<String> dependentViewportKeys = chunkToViewportDependencies.remove(tileKey(dimensionId, chunkX, chunkZ));
        if (dependentViewportKeys != null) {
            invalidatedViewportKeys.addAll(dependentViewportKeys);
        }
    }

    int visibleQueueSizeForTest() {
        return visibleQueue.size();
    }

    int prefetchQueueSizeForTest() {
        return prefetchQueue.size();
    }

    List<String> invalidatedViewportKeysForTest() {
        return List.copyOf(invalidatedViewportKeys);
    }
}
