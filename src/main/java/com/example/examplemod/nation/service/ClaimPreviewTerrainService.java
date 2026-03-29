package com.example.examplemod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
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
    private static final int WATER_COLOR = 0xFF57B7EA;
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
            int tint = resolveTintedColor(level, pos, state);
            if (tint != -1) {
                return tint;
            }
            MapColor mapColor = state.getMapColor(level, pos);
            int base = mapColor == null ? 0x6D7C86 : mapColor.col;
            return 0xFF000000 | (base & 0x00FFFFFF);
        } catch (Exception ignored) {
            return DEFAULT_COLOR;
        }
    }

    private static int resolveTintedColor(ServerLevel level, BlockPos pos, BlockState state) {
        int mapColor = safeMapColor(level, pos, state);
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.GRASS) || state.is(Blocks.FERN)
                || state.is(Blocks.TALL_GRASS) || state.is(Blocks.LARGE_FERN) || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.MOSS_BLOCK)) {
            int biomeGrass = level.getBiome(pos).value().getGrassColor(pos.getX(), pos.getZ());
            return blendOpaqueColors(biomeGrass, mapColor, 0.32D);
        }
        if (state.is(BlockTags.LEAVES) || state.is(Blocks.VINE)) {
            int foliage = level.getBiome(pos).value().getFoliageColor();
            return blendOpaqueColors(foliage, mapColor, 0.26D);
        }
        return -1;
    }

    private static int safeMapColor(ServerLevel level, BlockPos pos, BlockState state) {
        MapColor mapColor = state.getMapColor(level, pos);
        return mapColor == null ? 0x6D7C86 : mapColor.col;
    }

    private static int blendOpaqueColors(int primary, int secondary, double secondaryWeight) {
        double mix = Math.max(0.0D, Math.min(1.0D, secondaryWeight));
        int pr = (primary >> 16) & 0xFF;
        int pg = (primary >> 8) & 0xFF;
        int pb = primary & 0xFF;
        int sr = (secondary >> 16) & 0xFF;
        int sg = (secondary >> 8) & 0xFF;
        int sb = secondary & 0xFF;
        int rr = (int) Math.round(pr * (1.0D - mix) + sr * mix);
        int rg = (int) Math.round(pg * (1.0D - mix) + sg * mix);
        int rb = (int) Math.round(pb * (1.0D - mix) + sb * mix);
        return 0xFF000000 | (rr << 16) | (rg << 8) | rb;
    }

    private record CachedSnapshot(long createdAt, List<Integer> colors) {
    }

    private ClaimPreviewTerrainService() {
    }
}