package com.monpai.sailboatmod.market.web.map;

public final class MarketWebMapTileCoordinate {
    public static final int BASE_TILE_SIZE = 512;
    public static final int CHUNK_SIZE = 16;
    public static final int CHUNKS_PER_BASE_TILE_AXIS = BASE_TILE_SIZE / CHUNK_SIZE;

    private MarketWebMapTileCoordinate() {
    }

    public static Tile baseTileForChunk(int chunkX, int chunkZ) {
        return new Tile(Math.floorDiv(chunkX, CHUNKS_PER_BASE_TILE_AXIS),
                Math.floorDiv(chunkZ, CHUNKS_PER_BASE_TILE_AXIS));
    }

    public static int localChunkX(int chunkX) {
        return Math.floorMod(chunkX, CHUNKS_PER_BASE_TILE_AXIS);
    }

    public static int localChunkZ(int chunkZ) {
        return Math.floorMod(chunkZ, CHUNKS_PER_BASE_TILE_AXIS);
    }

    public static int basePixelX(int chunkX, int localBlockX) {
        return localChunkX(chunkX) * CHUNK_SIZE + Math.floorMod(localBlockX, CHUNK_SIZE);
    }

    public static int basePixelZ(int chunkZ, int localBlockZ) {
        return localChunkZ(chunkZ) * CHUNK_SIZE + Math.floorMod(localBlockZ, CHUNK_SIZE);
    }

    public static Tile scaleTile(int baseTileX, int baseTileZ, int zoom) {
        int divisor = 1 << Math.max(0, zoom);
        return new Tile(Math.floorDiv(baseTileX, divisor), Math.floorDiv(baseTileZ, divisor));
    }

    public record Tile(int x, int z) {
    }
}
