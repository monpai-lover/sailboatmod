package com.monpai.sailboatmod.roadplanner.bridge;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;

public class BridgeBackendSelector {
    private static final int PIER_BRIDGE_MIN_SPAN_BLOCKS = 33;

    public BridgeBackend select(RoadToolType toolType, int spanBlocks, int depthBlocks, boolean canyonLike) {
        if (spanBlocks >= PIER_BRIDGE_MIN_SPAN_BLOCKS || canyonLike) {
            return BridgeBackend.PIER_LARGE_BRIDGE;
        }
        return BridgeBackend.ROADWEAVER_SIMPLE;
    }
}
