package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ClaimPreviewTerrainService {
    private static final int DEFAULT_COLOR = 0xFF33414A;
    private static final int WATER_COLOR = 0xFF2F8FBF;
    private static final long CACHE_TTL_MILLIS = 10_000L;
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final ConcurrentMap<String, CachedSnapshot> CACHE = new ConcurrentHashMap<>();

    public static List<Integer> sample(ServerLevel level, ChunkPos centerChunk, int radius) {
        int diameter = radius * 2 + 1;
        if (level == null || centerChunk == null || radius < 0) {
            return filledDefaults(diameter * diameter);
        }
        long now = System.currentTimeMillis();
        String cacheKey = level.dimension().location() + "|" + centerChunk.x + "|" + centerChunk.z + "|" + radius;
        CachedSnapshot cached = CACHE.get(cacheKey);
        if (cached != null && now - cached.createdAt <= CACHE_TTL_MILLIS) {
            return cached.colors;
        }

        List<Integer> colors = new ArrayList<>(diameter * diameter);
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                colors.add(sampleChunkColor(level, centerChunk.x + dx, centerChunk.z + dz));
            }
        }

        List<Integer> immutableColors = List.copyOf(colors);
        if (CACHE.size() >= MAX_CACHE_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(cacheKey, new CachedSnapshot(now, immutableColors));
        return immutableColors;
    }

    public static void clearCache() {
        CACHE.clear();
    }

    private static List<Integer> filledDefaults(int size) {
        List<Integer> colors = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            colors.add(DEFAULT_COLOR);
        }
        return List.copyOf(colors);
    }

    private static int sampleChunkColor(ServerLevel level, int chunkX, int chunkZ) {
        try {
            if (level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, true) == null) {
                return DEFAULT_COLOR;
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
            return DEFAULT_COLOR;
        }
    }

    private record CachedSnapshot(long createdAt, List<Integer> colors) {
    }

    private ClaimPreviewTerrainService() {
    }
}