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
    List<BlockPos> centerPath
) {}
