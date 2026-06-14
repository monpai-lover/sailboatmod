package com.monpai.sailboatmod.market.web.map;

public enum MarketWebMapTileQuality {
    SERVER_REGION_SCAN("server_region_scan", 5),
    SERVER_LOADED_CHUNK("server_loaded_chunk", 4),
    CLIENT_UPLOAD("client_upload", 3),
    LEGACY_UNKNOWN("legacy_unknown", 2),
    UNKNOWN("unknown", 1);

    private final String id;
    private final int priority;

    MarketWebMapTileQuality(String id, int priority) {
        this.id = id;
        this.priority = priority;
    }

    public String id() {
        return id;
    }

    public int priority() {
        return priority;
    }

    public boolean canOverwrite(MarketWebMapTileQuality existing) {
        MarketWebMapTileQuality current = existing == null ? UNKNOWN : existing;
        if (this == SERVER_REGION_SCAN) {
            return current != SERVER_REGION_SCAN;
        }
        if (this == SERVER_LOADED_CHUNK) {
            return current == CLIENT_UPLOAD || current == LEGACY_UNKNOWN || current == UNKNOWN;
        }
        if (this == CLIENT_UPLOAD) {
            return current == UNKNOWN;
        }
        return false;
    }

    public static MarketWebMapTileQuality fromId(String id) {
        if (id == null || id.isBlank()) {
            return UNKNOWN;
        }
        for (MarketWebMapTileQuality quality : values()) {
            if (quality.id.equals(id)) {
                return quality;
            }
        }
        return UNKNOWN;
    }
}
