package com.monpai.sailboatmod.road.model;

import net.minecraft.core.BlockPos;
import java.util.List;

public record RoadData(
    String roadId,
    int width,
    List<RoadSegment> segments,
    List<BridgeSpan> bridgeSpans,
    RoadMaterial material,
    List<BuildStep> buildSteps,
    List<BlockPos> centerPath,
    List<RoadSegmentPlacement> placements,
    List<Integer> targetY
) {
    public RoadData(String roadId, int width, List<RoadSegment> segments,
                    List<BridgeSpan> bridgeSpans, RoadMaterial material,
                    List<BuildStep> buildSteps, List<BlockPos> centerPath) {
        this(roadId, width, segments, bridgeSpans, material, buildSteps, centerPath,
                List.of(), centerPath == null ? List.of() : centerPath.stream().map(BlockPos::getY).toList());
    }

    public RoadData {
        segments = segments == null ? List.of() : List.copyOf(segments);
        bridgeSpans = bridgeSpans == null ? List.of() : List.copyOf(bridgeSpans);
        buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
        centerPath = centerPath == null ? List.of() : List.copyOf(centerPath);
        placements = placements == null ? List.of() : List.copyOf(placements);
        targetY = targetY == null ? List.of() : List.copyOf(targetY);
    }
}