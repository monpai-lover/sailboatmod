package com.monpai.sailboatmod.roadplanner.model;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RoadPlan(UUID planId,
                       BlockPos start,
                       BlockPos destination,
                       List<RoadSegment> segments,
                       RoadSettings settings) {
    private static final double DESTINATION_CONNECT_DISTANCE_SQ = 8.0D * 8.0D;

    public RoadPlan {
        planId = Objects.requireNonNull(planId, "planId");
        start = Objects.requireNonNull(start, "start").immutable();
        destination = Objects.requireNonNull(destination, "destination").immutable();
        segments = segments == null ? List.of() : List.copyOf(segments);
        settings = settings == null ? RoadSettings.defaults() : settings;
    }

    public List<RoadNode> nodesInOrder() {
        List<RoadNode> nodes = new ArrayList<>();
        for (RoadSegment segment : segments) {
            for (RoadStroke stroke : segment.strokes()) {
                nodes.addAll(stroke.nodes());
            }
        }
        return List.copyOf(nodes);
    }

    public boolean isConnectedToDestination() {
        if (segments.isEmpty()) {
            return start.distSqr(destination) <= DESTINATION_CONNECT_DISTANCE_SQ;
        }
        BlockPos exit = segments.get(segments.size() - 1).exitPoint();
        return exit != null && exit.distSqr(destination) <= DESTINATION_CONNECT_DISTANCE_SQ;
    }
}
