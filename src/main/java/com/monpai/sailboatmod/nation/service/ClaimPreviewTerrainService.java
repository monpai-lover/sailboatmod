package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.TerrainPreviewSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ClaimPreviewTerrainService {
    private static final int DEFAULT_COLOR = 0xFF33414A;
    private static final int WATER_COLOR = 0xFF2F8FBF;
    private static final long CHUNK_TTL_MILLIS = 60_000L;
    private static final int MAX_CHUNK_CACHE = 4096;
    private static final int PREWARM_BUDGET_PER_TICK = 6;
    private static final int PREWARM_RADIUS_PADDING = 2;

    private record ChunkColorEntry(long createdAt, int color) {}
    private record PrewarmRequest(int chunkX, int chunkZ) {}

    private static final ConcurrentMap<String, ChunkColorEntry> CHUNK_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Queue<PrewarmRequest>> PREWARM_QUEUES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> QUEUED_CHUNKS = ConcurrentHashMap.newKeySet();

    public static List<Integer> sample(ServerLevel level, ChunkPos centerChunk, int radius) {
        int diameter = radius * 2 + 1;
        if (level == null || centerChunk == null || radius < 0) {
            return filledDefaults(diameter * diameter);
        }

        TerrainPreviewSavedData savedData = TerrainPreviewSavedData.get(level);
        queueAround(level, centerChunk, radius + PREWARM_RADIUS_PADDING);

        Integer[] colors = new Integer[diameter * diameter];
        int index = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                colors[index++] = sampleChunkColorCached(level, savedData, centerChunk.x + dx, centerChunk.z + dz);
            }
        }

        fillMissingColors(colors, diameter);
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
            }
        }
    }

    public static void invalidateChunk(ResourceKey<Level> dimension, int chunkX, int chunkZ) {
        CHUNK_CACHE.remove(chunkKey(dimension, chunkX, chunkZ));
        QUEUED_CHUNKS.remove(chunkKey(dimension, chunkX, chunkZ));
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
                    if (CHUNK_CACHE.containsKey(key) || !QUEUED_CHUNKS.add(key)) {
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
        }
        return color;
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
            if (level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) == null) {
                return null;
            }
            int worldX = (chunkX << 4) + 8;
            int worldZ = (chunkZ << 4) + 8;
            int worldY = level.getHeight(Heightmap.Types.WORLD_SURFACE, worldX, worldZ) - 1;
            if (worldY < level.getMinBuildHeight()) {
                return DEFAULT_COLOR;
            }
            BlockPos pos = new BlockPos(worldX, worldY, worldZ);
            BlockState state = level.getBlockState(pos);
            while (state.isAir() && worldY > level.getMinBuildHeight()) {
                worldY--;
                pos = new BlockPos(worldX, worldY, worldZ);
                state = level.getBlockState(pos);
            }
            if (state.getFluidState().is(FluidTags.WATER)) {
                return WATER_COLOR;
            }
            MapColor mapColor = state.getMapColor(level, pos);
            int base = mapColor == null ? 0x55606A : mapColor.col;
            return 0xFF000000 | (base & 0x00FFFFFF);
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

    private static void fillMissingColors(Integer[] colors, int diameter) {
        boolean changed = true;
        for (int pass = 0; pass < diameter && changed; pass++) {
            changed = false;
            Integer[] next = colors.clone();
            for (int index = 0; index < colors.length; index++) {
                if (colors[index] != null) {
                    continue;
                }
                int x = index % diameter;
                int z = index / diameter;
                int sumR = 0;
                int sumG = 0;
                int sumB = 0;
                int count = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        int nx = x + dx;
                        int nz = z + dz;
                        if (nx < 0 || nx >= diameter || nz < 0 || nz >= diameter) {
                            continue;
                        }
                        Integer neighbor = colors[nz * diameter + nx];
                        if (neighbor == null) {
                            continue;
                        }
                        sumR += (neighbor >> 16) & 0xFF;
                        sumG += (neighbor >> 8) & 0xFF;
                        sumB += neighbor & 0xFF;
                        count++;
                    }
                }
                if (count > 0) {
                    next[index] = 0xFF000000
                            | ((sumR / count) << 16)
                            | ((sumG / count) << 8)
                            | (sumB / count);
                    changed = true;
                }
            }
            System.arraycopy(next, 0, colors, 0, colors.length);
        }
    }

    private ClaimPreviewTerrainService() {
    }
}
