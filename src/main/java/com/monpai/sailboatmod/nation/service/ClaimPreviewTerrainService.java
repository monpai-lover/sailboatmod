package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.TerrainPreviewSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;

public final class ClaimPreviewTerrainService {
    private static final int DEFAULT_COLOR = 0xFF33414A;
    private static final int WATER_COLOR = 0xFF2F8FBF;
    private static final long CHUNK_TTL_MILLIS = 60_000L;
    private static final int MAX_CHUNK_CACHE = 4096;
    private static final int PREWARM_BUDGET_PER_TICK = 64;
    private static final int PREWARM_RADIUS_PADDING = 2;
    private static final int STORAGE_COMPLETION_BUDGET_PER_TICK = 128;
    private static final Method READ_CHUNK_METHOD = resolveReadChunkMethod();

    private record ChunkColorEntry(long createdAt, int color) {}
    private record PrewarmRequest(int chunkX, int chunkZ) {}
    private record StorageReadTask(ResourceKey<Level> dimension, int chunkX, int chunkZ, CompletableFuture<Optional<CompoundTag>> future) {}
    private record SurfaceSample(int color, int score) {}

    private static final ConcurrentMap<String, ChunkColorEntry> CHUNK_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Queue<PrewarmRequest>> PREWARM_QUEUES = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, StorageReadTask> STORAGE_READS = new ConcurrentHashMap<>();
    private static final java.util.Set<String> QUEUED_CHUNKS = ConcurrentHashMap.newKeySet();

    public static List<Integer> sample(ServerLevel level, ChunkPos centerChunk, int radius) {
        int diameter = radius * 2 + 1;
        if (level == null || centerChunk == null || radius < 0) {
            return filledDefaults(diameter * diameter);
        }

        TerrainPreviewSavedData savedData = TerrainPreviewSavedData.get(level);
        queueAround(level, centerChunk, radius + PREWARM_RADIUS_PADDING);
        drainCompletedStorageReads(level, savedData, STORAGE_COMPLETION_BUDGET_PER_TICK);

        Integer[] colors = new Integer[diameter * diameter];
        int index = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                colors[index++] = sampleChunkColorCached(level, savedData, centerChunk.x + dx, centerChunk.z + dz);
            }
        }

        List<Integer> result = new ArrayList<>(colors.length);
        for (Integer color : colors) {
            result.add(color == null ? DEFAULT_COLOR : color);
        }
        return List.copyOf(result);
    }

    public static void tick(ServerLevel level) {
        if (level == null) {
            return;
        }
        Queue<PrewarmRequest> queue = PREWARM_QUEUES.get(level.dimension().location().toString());
        if (queue == null || queue.isEmpty()) {
            return;
        }

        TerrainPreviewSavedData savedData = TerrainPreviewSavedData.get(level);
        drainCompletedStorageReads(level, savedData, STORAGE_COMPLETION_BUDGET_PER_TICK);
        for (int i = 0; i < PREWARM_BUDGET_PER_TICK && !queue.isEmpty(); i++) {
            PrewarmRequest request = queue.poll();
            if (request == null) {
                continue;
            }
            String key = chunkKey(level.dimension(), request.chunkX(), request.chunkZ());
            QUEUED_CHUNKS.remove(key);
            if (savedData.getColor(level.dimension().location().toString(), request.chunkX(), request.chunkZ()) != null) {
                continue;
            }
            Integer sampled = sampleChunkColorIfAvailable(level, request.chunkX(), request.chunkZ());
            if (sampled != null) {
                cacheColor(level, savedData, request.chunkX(), request.chunkZ(), sampled);
                continue;
            }
            startStorageRead(level, request.chunkX(), request.chunkZ());
        }
        drainCompletedStorageReads(level, savedData, STORAGE_COMPLETION_BUDGET_PER_TICK);
    }

    public static void invalidateChunk(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        String key = chunkKey(dimension, chunkX, chunkZ);
        CHUNK_CACHE.remove(key);
        QUEUED_CHUNKS.remove(key);
        STORAGE_READS.remove(key);
    }

    public static void invalidateChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (level == null) {
            return;
        }
        invalidateChunk(level.dimension(), chunkX, chunkZ);
        TerrainPreviewSavedData.get(level).removeColor(level.dimension().location().toString(), chunkX, chunkZ);
    }

    public static void clearCache() {
        CHUNK_CACHE.clear();
        PREWARM_QUEUES.clear();
        QUEUED_CHUNKS.clear();
    }

    public static void queueAround(ServerLevel level, ChunkPos centerChunk, int radius) {
        if (level == null || centerChunk == null || radius < 0) {
            return;
        }
        String dimensionId = level.dimension().location().toString();
        TerrainPreviewSavedData savedData = TerrainPreviewSavedData.get(level);
        Queue<PrewarmRequest> queue = PREWARM_QUEUES.computeIfAbsent(dimensionId, ignored -> new ConcurrentLinkedQueue<>());
        for (int ring = 0; ring <= radius; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int chunkX = centerChunk.x + dx;
                    int chunkZ = centerChunk.z + dz;
                    if (savedData.getColor(dimensionId, chunkX, chunkZ) != null) {
                        continue;
                    }
                    String key = chunkKey(level.dimension(), chunkX, chunkZ);
                    if (CHUNK_CACHE.containsKey(key) || STORAGE_READS.containsKey(key) || !QUEUED_CHUNKS.add(key)) {
                        continue;
                    }
                    queue.add(new PrewarmRequest(chunkX, chunkZ));
                }
            }
        }
    }

    private static Integer sampleChunkColorCached(ServerLevel level, TerrainPreviewSavedData savedData, int chunkX, int chunkZ) {
        String key = chunkKey(level.dimension(), chunkX, chunkZ);
        long now = System.currentTimeMillis();
        ChunkColorEntry cached = CHUNK_CACHE.get(key);
        if (cached != null && now - cached.createdAt <= CHUNK_TTL_MILLIS) {
            return cached.color;
        }

        String dimensionId = level.dimension().location().toString();
        Integer persisted = savedData.getColor(dimensionId, chunkX, chunkZ);
        if (persisted != null) {
            CHUNK_CACHE.put(key, new ChunkColorEntry(now, persisted));
            return persisted;
        }

        Integer color = sampleChunkColorIfAvailable(level, chunkX, chunkZ);
        if (color != null) {
            cacheColor(level, savedData, chunkX, chunkZ, color);
            return color;
        }

        color = sampleChunkColorBlockingFromStorage(level, savedData, chunkX, chunkZ);
        if (color != null) {
            return color;
        }

        color = consumeCompletedStorageRead(level, savedData, chunkX, chunkZ);
        if (color != null) {
            return color;
        }

        return null;
    }

    private static void evictIfNeeded() {
        if (CHUNK_CACHE.size() < MAX_CHUNK_CACHE) return;
        int toRemove = MAX_CHUNK_CACHE / 4;
        CHUNK_CACHE.entrySet().stream()
                .sorted(Comparator.comparingLong(e -> e.getValue().createdAt))
                .limit(toRemove)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(CHUNK_CACHE::remove);
    }

    private static String chunkKey(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        return dimension.location() + "|" + chunkX + "|" + chunkZ;
    }

    private static List<Integer> filledDefaults(int size) {
        List<Integer> colors = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            colors.add(DEFAULT_COLOR);
        }
        return List.copyOf(colors);
    }

    private static Integer sampleChunkColorIfAvailable(ServerLevel level, int chunkX, int chunkZ) {
        try {
            ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
            if (chunk == null) {
                return null;
            }
            return sampleChunkColor(chunk, chunkX, chunkZ);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer consumeCompletedStorageRead(ServerLevel level, TerrainPreviewSavedData savedData, int chunkX, int chunkZ) {
        String key = chunkKey(level.dimension(), chunkX, chunkZ);
        StorageReadTask task = STORAGE_READS.get(key);
        if (task == null || !task.future().isDone()) {
            return null;
        }
        return finishStorageRead(level, savedData, key, task);
    }

    private static void drainCompletedStorageReads(ServerLevel level, TerrainPreviewSavedData savedData, int budget) {
        if (budget <= 0 || STORAGE_READS.isEmpty()) {
            return;
        }
        int processed = 0;
        for (Map.Entry<String, StorageReadTask> entry : STORAGE_READS.entrySet()) {
            if (processed >= budget) {
                break;
            }
            StorageReadTask task = entry.getValue();
            if (task == null || task.dimension() != level.dimension() || !task.future().isDone()) {
                continue;
            }
            finishStorageRead(level, savedData, entry.getKey(), task);
            processed++;
        }
    }

    private static Integer finishStorageRead(ServerLevel level, TerrainPreviewSavedData savedData, String key, StorageReadTask task) {
        if (task == null || !STORAGE_READS.remove(key, task)) {
            return null;
        }
        QUEUED_CHUNKS.remove(key);
        try {
            Optional<CompoundTag> chunkTag = task.future().join();
            if (chunkTag.isEmpty()) {
                return null;
            }
            Integer color = sampleChunkColorFromStorage(level, task.chunkX(), task.chunkZ(), chunkTag.get());
            if (color != null) {
                cacheColor(level, savedData, task.chunkX(), task.chunkZ(), color);
            }
            return color;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void startStorageRead(ServerLevel level, int chunkX, int chunkZ) {
        if (READ_CHUNK_METHOD == null || level == null) {
            return;
        }
        String key = chunkKey(level.dimension(), chunkX, chunkZ);
        STORAGE_READS.computeIfAbsent(key, ignored -> {
            try {
                Object result = READ_CHUNK_METHOD.invoke(level.getChunkSource().chunkMap, new ChunkPos(chunkX, chunkZ));
                if (result instanceof CompletableFuture<?> future) {
                    @SuppressWarnings("unchecked")
                    CompletableFuture<Optional<CompoundTag>> typedFuture = (CompletableFuture<Optional<CompoundTag>>) future;
                    return new StorageReadTask(level.dimension(), chunkX, chunkZ, typedFuture);
                }
            } catch (Exception ignoredException) {
                QUEUED_CHUNKS.remove(key);
            }
            return null;
        });
    }

    private static Integer sampleChunkColorBlockingFromStorage(ServerLevel level, TerrainPreviewSavedData savedData, int chunkX, int chunkZ) {
        if (READ_CHUNK_METHOD == null || level == null) {
            return null;
        }
        try {
            Object result = READ_CHUNK_METHOD.invoke(level.getChunkSource().chunkMap, new ChunkPos(chunkX, chunkZ));
            if (!(result instanceof CompletableFuture<?> future)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            CompletableFuture<Optional<CompoundTag>> typedFuture = (CompletableFuture<Optional<CompoundTag>>) future;
            Optional<CompoundTag> chunkTag = typedFuture.join();
            if (chunkTag.isEmpty()) {
                return null;
            }
            Integer color = sampleChunkColorFromStorage(level, chunkX, chunkZ, chunkTag.get());
            if (color != null) {
                cacheColor(level, savedData, chunkX, chunkZ, color);
            }
            return color;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer sampleChunkColorFromStorage(ServerLevel level, int chunkX, int chunkZ, CompoundTag chunkTag) {
        try {
            ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
            ChunkAccess chunk = ChunkSerializer.read(level, level.getChunkSource().getPoiManager(), chunkPos, chunkTag);
            return sampleChunkColor(chunk, chunkX, chunkZ);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Integer sampleChunkColor(ChunkAccess chunk, int chunkX, int chunkZ) {
        int[][] sampleOffsets = {{8, 8}, {4, 4}, {12, 4}, {4, 12}, {12, 12}};
        SurfaceSample best = null;
        for (int[] offset : sampleOffsets) {
            SurfaceSample sample = sampleSurfaceColor(chunk, chunkX, chunkZ, offset[0], offset[1]);
            if (sample == null) {
                continue;
            }
            if (best == null || sample.score() > best.score()) {
                best = sample;
            }
            if (sample.score() >= 100) {
                return sample.color();
            }
        }
        return best == null ? DEFAULT_COLOR : best.color();
    }

    private static SurfaceSample sampleSurfaceColor(ChunkAccess chunk, int chunkX, int chunkZ, int localX, int localZ) {
        int worldX = (chunkX << 4) + localX;
        int worldZ = (chunkZ << 4) + localZ;
        int worldY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX & 15, localZ & 15) - 1;
        if (worldY < chunk.getMinBuildHeight()) {
            return null;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(worldX, worldY, worldZ);
        BlockState state = chunk.getBlockState(pos);
        while (state.isAir() && worldY > chunk.getMinBuildHeight()) {
            worldY--;
            pos.set(worldX, worldY, worldZ);
            state = chunk.getBlockState(pos);
        }
        if (state.getFluidState().is(FluidTags.WATER)) {
            return new SurfaceSample(WATER_COLOR, 60);
        }
        MapColor mapColor = state.getMapColor(chunk, pos);
        int base = mapColor == null ? 0x55606A : mapColor.col;
        return new SurfaceSample(0xFF000000 | (base & 0x00FFFFFF), sampleScore(state, mapColor));
    }

    private static int sampleScore(BlockState state, MapColor mapColor) {
        if (state.is(Blocks.GRASS_BLOCK) || mapColor == MapColor.GRASS) {
            return 100;
        }
        if (state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.MYCELIUM)) {
            return 92;
        }
        if (mapColor == MapColor.PLANT || mapColor == MapColor.COLOR_GREEN) {
            return 86;
        }
        if (mapColor == MapColor.WATER) {
            return 60;
        }
        if (mapColor == MapColor.SAND || mapColor == MapColor.COLOR_YELLOW) {
            return 40;
        }
        return 20;
    }

    private static Method resolveReadChunkMethod() {
        try {
            Method method = net.minecraft.server.level.ChunkMap.class.getDeclaredMethod("readChunk", ChunkPos.class);
            method.setAccessible(true);
            return method;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void cacheColor(ServerLevel level, TerrainPreviewSavedData savedData, int chunkX, int chunkZ, int color) {
        long now = System.currentTimeMillis();
        evictIfNeeded();
        CHUNK_CACHE.put(chunkKey(level.dimension(), chunkX, chunkZ), new ChunkColorEntry(now, color));
        savedData.putColor(level.dimension().location().toString(), chunkX, chunkZ, color);
    }

    private ClaimPreviewTerrainService() {
    }
}
