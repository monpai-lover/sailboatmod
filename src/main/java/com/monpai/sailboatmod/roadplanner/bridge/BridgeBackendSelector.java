package com.monpai.sailboatmod.roadplanner.bridge;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;

public class BridgeBackendSelector {
    private static final int LARGE_SPAN_BLOCKS = 24;
    private static final int DEEP_GAP_BLOCKS = 8;

    public BridgeBackend select(RoadToolType toolType, int spanBlocks, int depthBlocks, boolean canyonLike) {
        if (toolType == RoadToolType.BRIDGE) {
            return BridgeBackend.PIER_LARGE_BRIDGE;
        }
        if (spanBlocks >= LARGE_SPAN_BLOCKS || depthBlocks >= DEEP_GAP_BLOCKS || canyonLike) {
            return BridgeBackend.PIER_LARGE_BRIDGE;
        }
        return BridgeBackend.ROADWEAVER_SIMPLE;
    }
}
