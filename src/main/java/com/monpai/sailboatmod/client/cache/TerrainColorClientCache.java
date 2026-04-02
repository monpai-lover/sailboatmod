package com.monpai.sailboatmod.client.cache;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TerrainColorClientCache {
    private static final int MAX_ENTRIES = 8192;
    private static final Map<Long, Integer> CACHE = new LinkedHashMap<Long, Integer>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    public static Integer get(int chunkX, int chunkZ) {
        return CACHE.get(packKey(chunkX, chunkZ));
    }

    public static void put(int chunkX, int chunkZ, int color) {
        CACHE.put(packKey(chunkX, chunkZ), color);
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
