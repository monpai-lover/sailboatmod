package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;

import java.util.UUID;

public record RoadPlannerClientState(UUID sessionId,
                                     boolean open,
                                     RoadToolType activeTool,
                                     int activeRegionIndex,
                                     int selectedWidth,
                                     UUID selectedRoadEdgeId) {
    public RoadPlannerClientState {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        activeTool = activeTool == null ? RoadToolType.ROAD : activeTool;
        if (activeRegionIndex < 0) {
            throw new IllegalArgumentException("activeRegionIndex must be non-negative");
        }
        if (selectedWidth != 3 && selectedWidth != 5 && selectedWidth != 7) {
            throw new IllegalArgumentException("selectedWidth must be 3, 5, or 7");
        }
    }

    public static RoadPlannerClientState open(UUID sessionId) {
        return new RoadPlannerClientState(sessionId, true, RoadToolType.ROAD, 0, 5, null);
    }

    public RoadPlannerClientState withActiveTool(RoadToolType tool) {
        return new RoadPlannerClientState(sessionId, open, tool, activeRegionIndex, selectedWidth, selectedRoadEdgeId);
    }

    public RoadPlannerClientState withActiveRegionIndex(int regionIndex) {
        return new RoadPlannerClientState(sessionId, open, activeTool, regionIndex, selectedWidth, selectedRoadEdgeId);
    }

    public RoadPlannerClientState withSelectedWidth(int width) {
        return new RoadPlannerClientState(sessionId, open, activeTool, activeRegionIndex, width, selectedRoadEdgeId);
    }

    public RoadPlannerClientState withSelectedRoadEdge(UUID edgeId) {
        return new RoadPlannerClientState(sessionId, open, activeTool, activeRegionIndex, selectedWidth, edgeId);
    }

    public boolean isOpen() {
        return open;
    }
}
