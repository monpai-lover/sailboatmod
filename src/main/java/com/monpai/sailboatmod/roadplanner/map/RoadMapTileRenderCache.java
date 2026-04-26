package com.monpai.sailboatmod.roadplanner.map;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class RoadMapTileRenderCache {
    private final int minimumMeaningfulPixels;
    private final Map<String, RoadMapSnapshot> snapshotsByRegion = new LinkedHashMap<>();
    private final Map<String, RoadMapSnapshot> pendingUploadsByRegion = new LinkedHashMap<>();

    public RoadMapTileRenderCache(int minimumMeaningfulPixels) {
        this.minimumMeaningfulPixels = Math.max(1, minimumMeaningfulPixels);
    }

    public boolean acceptSnapshot(RoadMapSnapshot snapshot) {
        if (!isMeaningful(snapshot, minimumMeaningfulPixels)) {
            return false;
        }
        String key = key(snapshot.region());
        RoadMapSnapshot previous = snapshotsByRegion.get(key);
        if (previous != null && previous.createdAtGameTime() == snapshot.createdAtGameTime()) {
            return false;
        }
        snapshotsByRegion.put(key, snapshot);
        pendingUploadsByRegion.put(key, snapshot);
        return true;
    }

    public int pendingUploadCount() {
        return pendingUploadsByRegion.size();
    }

    public List<RoadMapSnapshot> drainPendingUploads() {
        List<RoadMapSnapshot> snapshots = new ArrayList<>(pendingUploadsByRegion.values());
        pendingUploadsByRegion.clear();
        return List.copyOf(snapshots);
    }

    public static boolean isMeaningful(RoadMapSnapshot snapshot, int minimumMeaningfulPixels) {
        if (snapshot == null) {
            return false;
        }
        int meaningful = 0;
        for (int pixel : snapshot.argbPixels()) {
            int alpha = (pixel >>> 24) & 0xFF;
            int rgb = pixel & 0x00FFFFFF;
            if (alpha > 0 && rgb != 0) {
                meaningful++;
                if (meaningful >= minimumMeaningfulPixels) {
                    return true;
                }
            }
        }
        return false;
    }

    private String key(RoadMapRegion region) {
        return region.center().getX() + ":" + region.center().getZ() + ":" + region.regionSize() + ":" + region.lod();
    }
}
