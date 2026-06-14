package com.monpai.sailboatmod.client.marketweb;

import com.monpai.sailboatmod.roadplanner.map.MapLod;

import java.util.LinkedHashMap;
import java.util.Map;

final class MarketWebMapTileUploadDedupe {
    private final int maxKeys;
    private final LinkedHashMap<Key, Boolean> seenKeys = new LinkedHashMap<>();

    MarketWebMapTileUploadDedupe(int maxKeys) {
        this.maxKeys = Math.max(1, maxKeys);
    }

    synchronized boolean remember(Key key) {
        if (key == null || seenKeys.containsKey(key)) {
            return false;
        }
        seenKeys.put(key, Boolean.TRUE);
        trimToLimit();
        return true;
    }

    synchronized void clear() {
        seenKeys.clear();
    }

    private void trimToLimit() {
        while (seenKeys.size() > maxKeys) {
            Map.Entry<Key, Boolean> eldest = seenKeys.entrySet().iterator().next();
            seenKeys.remove(eldest.getKey());
        }
    }

    record Key(String dimensionId, MapLod lod, int tileX, int tileZ, int coverageHash, int pixelHash) {
    }
}
