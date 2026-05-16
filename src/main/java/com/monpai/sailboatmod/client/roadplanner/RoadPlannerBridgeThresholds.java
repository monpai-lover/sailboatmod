package com.monpai.sailboatmod.client.roadplanner;

public final class RoadPlannerBridgeThresholds {
    public static final int SHORT_SPAN_WITHOUT_PIERS_LIMIT = 32;

    private RoadPlannerBridgeThresholds() {
    }

    public static boolean requiresMajorBridge(int waterSpanLength) {
        return waterSpanLength > SHORT_SPAN_WITHOUT_PIERS_LIMIT;
    }
}
