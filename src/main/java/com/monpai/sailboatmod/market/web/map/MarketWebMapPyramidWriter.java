package com.monpai.sailboatmod.market.web.map;

import java.util.Arrays;

public final class MarketWebMapPyramidWriter {
    public static final int MAX_ZOOM = 4;

    public boolean mergeChunk(MarketWebMapTileCache cache,
                              String dimensionId,
                              int chunkX,
                              int chunkZ,
                              int[] chunkPixels) {
        if (cache == null
                || !MarketWebMapConstants.OVERWORLD.equals(dimensionId)
                || chunkPixels == null
                || chunkPixels.length != MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE) {
            return false;
        }
        boolean wrote = false;
        for (int zoom = 0; zoom <= MAX_ZOOM; zoom++) {
            int[] tile = cache.readSquareTilePixels(dimensionId, zoom, squareTileX(chunkX, zoom), squareTileZ(chunkZ, zoom));
            int scale = 1 << zoom;
            int originX = squareTileX(chunkX, zoom) * MarketWebMapTileCoordinate.BASE_TILE_SIZE * scale;
            int originZ = squareTileZ(chunkZ, zoom) * MarketWebMapTileCoordinate.BASE_TILE_SIZE * scale;
            boolean changed = false;
            for (int localZ = 0; localZ < MarketWebMapConstants.CHUNK_SIZE; localZ++) {
                for (int localX = 0; localX < MarketWebMapConstants.CHUNK_SIZE; localX++) {
                    int worldX = chunkX * MarketWebMapConstants.CHUNK_SIZE + localX;
                    int worldZ = chunkZ * MarketWebMapConstants.CHUNK_SIZE + localZ;
                    int pixelX = Math.floorDiv(worldX - originX, scale);
                    int pixelZ = Math.floorDiv(worldZ - originZ, scale);
                    if (pixelX < 0 || pixelX >= MarketWebMapTileCoordinate.BASE_TILE_SIZE
                            || pixelZ < 0 || pixelZ >= MarketWebMapTileCoordinate.BASE_TILE_SIZE) {
                        continue;
                    }
                    int index = pixelZ * MarketWebMapTileCoordinate.BASE_TILE_SIZE + pixelX;
                    int color = chunkPixels[localZ * MarketWebMapConstants.CHUNK_SIZE + localX];
                    if (tile[index] != color) {
                        tile[index] = color;
                        changed = true;
                    }
                }
            }
            if (changed && cache.writeSquareTilePixels(dimensionId, zoom, squareTileX(chunkX, zoom), squareTileZ(chunkZ, zoom), tile)) {
                wrote = true;
            }
        }
        return wrote || Arrays.stream(chunkPixels).anyMatch(pixel -> pixel != 0);
    }

    private static int squareTileX(int chunkX, int zoom) {
        int blockX = chunkX * MarketWebMapConstants.CHUNK_SIZE;
        int blocksPerTile = MarketWebMapTileCoordinate.BASE_TILE_SIZE * (1 << Math.max(0, zoom));
        return Math.floorDiv(blockX, blocksPerTile);
    }

    private static int squareTileZ(int chunkZ, int zoom) {
        int blockZ = chunkZ * MarketWebMapConstants.CHUNK_SIZE;
        int blocksPerTile = MarketWebMapTileCoordinate.BASE_TILE_SIZE * (1 << Math.max(0, zoom));
        return Math.floorDiv(blockZ, blocksPerTile);
    }
}
