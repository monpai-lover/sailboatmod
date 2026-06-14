package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.List;

public record RoadPlannerAutoCompleteResult(boolean success,
                                            List<BlockPos> nodes,
                                            List<RoadPlannerSegmentType> segmentTypes,
                                            String message,
                                            List<RoadPlannerTerrainSample> terrainSamples) {
    public RoadPlannerAutoCompleteResult(boolean success,
                                         List<BlockPos> nodes,
                                         List<RoadPlannerSegmentType> segmentTypes,
                                         String message) {
        this(success, nodes, segmentTypes, message, List.of());
    }

    public RoadPlannerAutoCompleteResult {
        nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
        message = message == null ? "" : message;
        terrainSamples = terrainSamples == null ? List.of() : List.copyOf(terrainSamples);
    }

    public static RoadPlannerAutoCompleteResult failure(String message) {
        return new RoadPlannerAutoCompleteResult(false, List.of(), List.of(), message, List.of());
    }
}
