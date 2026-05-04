package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;

import java.util.List;

public record RoadRouteSection(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes) {
    public RoadRouteSection {
        nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
        if (nodes.size() >= 2 && segmentTypes.size() != nodes.size() - 1) {
            throw new IllegalArgumentException("segmentTypes must be nodes.size() - 1 for a route section");
        }
    }
}
