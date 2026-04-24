package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadSegmentPlacement;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class RoadSegmentStepPlanner {
    private final RoadSegmentPaver paver;

    public RoadSegmentStepPlanner(RoadSegmentPaver paver) {
        this.paver = paver;
    }

    public List<BuildStep> buildLandSteps(List<RoadSegmentPlacement> placements,
                                          List<BlockPos> centerPath,
                                          List<Integer> targetY,
                                          List<BridgeSpan> bridgeSpans,
                                          TerrainSamplingCache cache,
                                          String materialPreset,
                                          int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        int[] heights = toArray(centerPath, targetY);
        for (RoadSegmentPlacement placement : placements == null ? List.<RoadSegmentPlacement>of() : placements) {
            if (placement == null || placement.bridge() || isBridgeSegment(placement.segmentIndex(), bridgeSpans)) {
                continue;
            }
            List<BuildStep> segmentSteps = paver.paveSegment(placement, centerPath, heights, cache, materialPreset, order);
            steps.addAll(segmentSteps);
            order += segmentSteps.size();
        }
        return steps;
    }

    private int[] toArray(List<BlockPos> centerPath, List<Integer> targetY) {
        if (centerPath == null) {
            return new int[0];
        }
        int[] heights = new int[centerPath.size()];
        for (int i = 0; i < centerPath.size(); i++) {
            heights[i] = targetY != null && i < targetY.size() ? targetY.get(i) : centerPath.get(i).getY();
        }
        return heights;
    }

    private boolean isBridgeSegment(int segmentIndex, List<BridgeSpan> bridgeSpans) {
        if (bridgeSpans == null) {
            return false;
        }
        for (BridgeSpan span : bridgeSpans) {
            if (span != null && segmentIndex >= span.startIndex() && segmentIndex <= span.endIndex()) {
                return true;
            }
        }
        return false;
    }
}