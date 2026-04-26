package com.monpai.sailboatmod.roadplanner.map;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class RoadMapSnapshotCache {
    private final Map<String, Map<RoadMapCacheKey, RoadMapSnapshot>> snapshotsByWorld = new HashMap<>();
    private String activeWorldId = "";

    public void activateWorld(String worldId) {
        activeWorldId = worldId == null ? "" : worldId;
        snapshotsByWorld.computeIfAbsent(activeWorldId, ignored -> new HashMap<>());
    }

    public void put(RoadMapCacheKey key, RoadMapSnapshot snapshot) {
        if (!key.worldId().equals(activeWorldId)) {
            return;
        }
        snapshotsByWorld.computeIfAbsent(activeWorldId, ignored -> new HashMap<>()).put(key, snapshot);
    }

    public Optional<RoadMapSnapshot> get(RoadMapCacheKey key) {
        if (!key.worldId().equals(activeWorldId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshotsByWorld.getOrDefault(activeWorldId, Map.of()).get(key));
    }

    public int activeEntryCount() {
        return snapshotsByWorld.getOrDefault(activeWorldId, Map.of()).size();
    }

    public void clearActiveMemory() {
        snapshotsByWorld.remove(activeWorldId);
    }
}
