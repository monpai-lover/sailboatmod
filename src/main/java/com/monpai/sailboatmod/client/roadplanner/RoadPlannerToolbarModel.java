package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;

import java.util.List;

public record RoadPlannerToolbarModel(RoadToolType activeTool, List<RoadToolType> tools) {
    public RoadPlannerToolbarModel {
        activeTool = activeTool == null ? RoadToolType.ROAD : activeTool;
        tools = tools == null ? defaultTools() : List.copyOf(tools);
    }

    public static RoadPlannerToolbarModel defaults() {
        return new RoadPlannerToolbarModel(RoadToolType.ROAD, defaultTools());
    }

    public RoadPlannerToolbarModel select(RoadToolType tool) {
        return new RoadPlannerToolbarModel(tool, tools);
    }

    private static List<RoadToolType> defaultTools() {
        return List.of(RoadToolType.ROAD, RoadToolType.BRIDGE, RoadToolType.TUNNEL, RoadToolType.ERASE, RoadToolType.SELECT);
    }
}
