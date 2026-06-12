package com.monpai.sailboatmod.map;

import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import net.minecraft.world.level.ChunkPos;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class RenderedChunkIndex {
    private static final int CHUNKS_PER_TILE_AXIS = RoadMapTileSpec.TILE_BLOCKS / 16;
    private static final int PIXELS_PER_CHUNK_AXIS = RoadMapTileSpec.TILE_PIXELS / CHUNKS_PER_TILE_AXIS;

    private final Map<String, Set<Long>> renderedByDimension = new ConcurrentHashMap<>();

    public boolean markChunk(String dimensionId, int chunkX, int chunkZ) {
        String dimension = normalizeDimension(dimensionId);
        if (dimension.isEmpty()) {
            return false;
        }
        return renderedByDimension
                .computeIfAbsent(dimension, ignored -> ConcurrentHashMap.newKeySet())
                .add(ChunkPos.asLong(chunkX, chunkZ));
    }

    public int markTile(String dimensionId, int tileX, int tileZ, boolean[] coverageMask) {
        String dimension = normalizeDimension(dimensionId);
        if (dimension.isEmpty()) {
            return 0;
        }
        boolean[] safeMask = normalizeMask(coverageMask);
        int marked = 0;
        int tileChunkX = tileX * CHUNKS_PER_TILE_AXIS;
        int tileChunkZ = tileZ * CHUNKS_PER_TILE_AXIS;
        for (int localChunkZ = 0; localChunkZ < CHUNKS_PER_TILE_AXIS; localChunkZ++) {
            for (int localChunkX = 0; localChunkX < CHUNKS_PER_TILE_AXIS; localChunkX++) {
                if (!isChunkCovered(safeMask, localChunkX, localChunkZ)) {
                    continue;
                }
                if (markChunk(dimension, tileChunkX + localChunkX, tileChunkZ + localChunkZ)) {
                    marked++;
                }
            }
        }
        return marked;
    }

    public boolean isRendered(String dimensionId, int chunkX, int chunkZ) {
        Set<Long> rendered = renderedByDimension.get(normalizeDimension(dimensionId));
        return rendered != null && rendered.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    public boolean hasAnyRenderedChunkInTile(String dimensionId, int tileX, int tileZ) {
        String dimension = normalizeDimension(dimensionId);
        Set<Long> rendered = renderedByDimension.get(dimension);
        if (rendered == null || rendered.isEmpty()) {
            return false;
        }
        int startChunkX = tileX * CHUNKS_PER_TILE_AXIS;
        int startChunkZ = tileZ * CHUNKS_PER_TILE_AXIS;
        for (int dz = 0; dz < CHUNKS_PER_TILE_AXIS; dz++) {
            for (int dx = 0; dx < CHUNKS_PER_TILE_AXIS; dx++) {
                if (rendered.contains(ChunkPos.asLong(startChunkX + dx, startChunkZ + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    public int renderedChunkCount(String dimensionId) {
        Set<Long> rendered = renderedByDimension.get(normalizeDimension(dimensionId));
        return rendered == null ? 0 : rendered.size();
    }

    public void clearDimension(String dimensionId) {
        renderedByDimension.remove(normalizeDimension(dimensionId));
    }

    public void clearAll() {
        renderedByDimension.clear();
    }

    private static boolean isChunkCovered(boolean[] mask, int localChunkX, int localChunkZ) {
        int startX = localChunkX * PIXELS_PER_CHUNK_AXIS;
        int startZ = localChunkZ * PIXELS_PER_CHUNK_AXIS;
        int endX = Math.min(RoadMapTileSpec.TILE_PIXELS, startX + PIXELS_PER_CHUNK_AXIS);
        int endZ = Math.min(RoadMapTileSpec.TILE_PIXELS, startZ + PIXELS_PER_CHUNK_AXIS);
        for (int y = startZ; y < endZ; y++) {
            for (int x = startX; x < endX; x++) {
                if (mask[y * RoadMapTileSpec.TILE_PIXELS + x]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean[] normalizeMask(boolean[] mask) {
        int expected = RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS;
        if (mask != null && mask.length == expected) {
            return mask;
        }
        boolean[] full = new boolean[expected];
        java.util.Arrays.fill(full, true);
        return full;
    }

    private static String normalizeDimension(String dimensionId) {
        return dimensionId == null ? "" : dimensionId.trim();
    }
}
