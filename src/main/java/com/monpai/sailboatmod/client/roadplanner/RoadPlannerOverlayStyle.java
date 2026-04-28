package com.monpai.sailboatmod.client.roadplanner;

public final class RoadPlannerOverlayStyle {
    private RoadPlannerOverlayStyle() {
    }

    public static int roadLineColor() {
        return RoadPlannerMapTheme.ROAD_LINE;
    }

    public static int bridgeLineColor() {
        return RoadPlannerMapTheme.BRIDGE_LINE;
    }

    public static int bridgeNodeColor() {
        return RoadPlannerMapTheme.BRIDGE_NODE;
    }

    public static int tunnelLineColor() {
        return RoadPlannerMapTheme.TUNNEL_LINE;
    }

    public static int nodeColor() {
        return RoadPlannerMapTheme.NODE;
    }

    public static int lineColor(RoadPlannerSegmentType type) {
        if (type == RoadPlannerSegmentType.BRIDGE_SMALL || type == RoadPlannerSegmentType.BRIDGE_MAJOR) {
            return bridgeLineColor();
        }
        if (type == RoadPlannerSegmentType.TUNNEL) {
            return tunnelLineColor();
        }
        if (type == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE) {
            return RoadPlannerMapTheme.BLOCKED_LINE;
        }
        return roadLineColor();
    }
}
