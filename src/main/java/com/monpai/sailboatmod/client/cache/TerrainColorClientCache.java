package com.monpai.sailboatmod.client.cache;

import com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TerrainColorClientCache {
    private static final int MAX_ENTRIES = 8192;
    private static final Map<Long, int[]> CACHE = new LinkedHashMap<Long, int[]>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, int[]> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public static Integer get(int chunkX, int chunkZ, int subX, int subZ) {
        int[] colors = CACHE.get(packKey(chunkX, chunkZ));
        if (colors == null || colors.length == 0) {
            return null;
        }
        int sub = Math.max(1, ClaimPreviewTerrainService.SUB);
        int clampedSubX = Math.max(0, Math.min(sub - 1, subX));
        int clampedSubZ = Math.max(0, Math.min(sub - 1, subZ));
        int index = clampedSubZ * sub + clampedSubX;
        if (index < 0 || index >= colors.length) {
            return colors[0];
        }
        return colors[index];
    }

    public static void put(int chunkX, int chunkZ, int[] colors) {
        if (colors == null || colors.length == 0) {
            return;
        }
        CACHE.put(packKey(chunkX, chunkZ), Arrays.copyOf(colors, colors.length));
    }

    public static void put(int chunkX, int chunkZ, int subX, int subZ, int color) {
        int sub = Math.max(1, ClaimPreviewTerrainService.SUB);
        int[] colors = CACHE.computeIfAbsent(packKey(chunkX, chunkZ), ignored -> new int[sub * sub]);
        int clampedSubX = Math.max(0, Math.min(sub - 1, subX));
        int clampedSubZ = Math.max(0, Math.min(sub - 1, subZ));
        colors[clampedSubZ * sub + clampedSubX] = color;
    }

    public static void clear() {
        CACHE.clear();
    }

    private static long packKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private TerrainColorClientCache() {
    }
}
