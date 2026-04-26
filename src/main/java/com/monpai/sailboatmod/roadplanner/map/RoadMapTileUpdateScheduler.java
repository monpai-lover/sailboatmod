package com.monpai.sailboatmod.roadplanner.map;

import java.util.HashMap;
import java.util.Map;

public class RoadMapTileUpdateScheduler {
    private final long currentTileIntervalMs;
    private final long neighborTileIntervalMs;
    private final Map<String, Long> lastCurrentUpdates = new HashMap<>();
    private long lastNeighborUpdateTime;

    public RoadMapTileUpdateScheduler(long currentTileIntervalMs, long neighborTileIntervalMs) {
        this.currentTileIntervalMs = Math.max(1L, currentTileIntervalMs);
        this.neighborTileIntervalMs = Math.max(1L, neighborTileIntervalMs);
    }

    public boolean shouldUpdateCurrent(String tileKey, long nowMs) {
        String safeKey = tileKey == null ? "" : tileKey;
        Long lastUpdate = lastCurrentUpdates.get(safeKey);
        if (lastUpdate == null || nowMs - lastUpdate >= currentTileIntervalMs) {
            lastCurrentUpdates.put(safeKey, nowMs);
            return true;
        }
        return false;
    }

    public boolean shouldUpdateNeighbor(long nowMs) {
        if (nowMs - lastNeighborUpdateTime >= neighborTileIntervalMs) {
            lastNeighborUpdateTime = nowMs;
            return true;
        }
        return false;
    }
}
