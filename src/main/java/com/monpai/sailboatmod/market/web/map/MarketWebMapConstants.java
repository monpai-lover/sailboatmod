package com.monpai.sailboatmod.market.web.map;

public final class MarketWebMapConstants {
    public static final String OVERWORLD = "minecraft:overworld";
    public static final String UNKNOWN_COLOR = "#000000";
    public static final int TILE_SIZE = 256;
    public static final int CHUNK_SIZE = 16;
    public static final int CHUNKS_PER_TILE_AXIS = TILE_SIZE / CHUNK_SIZE;
    public static final int LOD_BLOCKS_PER_PIXEL = 1;
    public static final int MAX_TILE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_UPLOAD_CHUNK_BYTES = 28 * 1024;
    public static final int MAX_UPLOAD_CHUNKS = (MAX_TILE_BYTES + MAX_UPLOAD_CHUNK_BYTES - 1) / MAX_UPLOAD_CHUNK_BYTES;
    public static final int MAX_UPLOADS_PER_PLAYER_PER_MINUTE = 24;
    public static final String CACHE_DATA_DIR = "sailboatmod_market_web_map";

    private MarketWebMapConstants() {
    }
}
