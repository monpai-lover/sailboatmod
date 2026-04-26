package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshot;
import com.monpai.sailboatmod.roadplanner.map.RoadMapTileRenderCache;

import java.util.List;

public class RoadPlannerMapTexture {
    private final RoadMapTileRenderCache renderCache;

    public RoadPlannerMapTexture(int minimumMeaningfulPixels) {
        this.renderCache = new RoadMapTileRenderCache(minimumMeaningfulPixels);
    }

    public boolean acceptSnapshot(RoadMapSnapshot snapshot) {
        return renderCache.acceptSnapshot(snapshot);
    }

    public int pendingUploadCount() {
        return renderCache.pendingUploadCount();
    }

    public List<RoadMapSnapshot> drainUploadsForRenderThread() {
        return renderCache.drainPendingUploads();
    }
}
