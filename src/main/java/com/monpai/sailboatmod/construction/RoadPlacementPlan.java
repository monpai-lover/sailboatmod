package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public record RoadPlacementPlan(List<BridgeRange> bridgeRanges,
                                List<BlockPos> centerPath,
                                BlockPos sourceInternalAnchor,
                                BlockPos sourceBoundaryAnchor,
                                BlockPos targetBoundaryAnchor,
                                BlockPos targetInternalAnchor,
                                List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                List<RoadGeometryPlanner.RoadBuildStep> buildSteps,
                                BlockPos startHighlightPos,
                                BlockPos endHighlightPos,
                                BlockPos focusPos) {
    public RoadPlacementPlan {
        bridgeRanges = bridgeRanges == null ? List.of() : List.copyOf(bridgeRanges);
        centerPath = copyPositions(centerPath);
        sourceInternalAnchor = immutable(sourceInternalAnchor);
        sourceBoundaryAnchor = immutable(sourceBoundaryAnchor);
        targetBoundaryAnchor = immutable(targetBoundaryAnchor);
        targetInternalAnchor = immutable(targetInternalAnchor);
        ghostBlocks = ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
        buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
        startHighlightPos = immutable(startHighlightPos);
        endHighlightPos = immutable(endHighlightPos);
        focusPos = immutable(focusPos);
    }

    private static List<BlockPos> copyPositions(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        List<BlockPos> copied = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (pos != null) {
                copied.add(pos.immutable());
            }
        }
        return List.copyOf(copied);
    }

    private static BlockPos immutable(BlockPos pos) {
        return pos == null ? null : pos.immutable();
    }

    public record BridgeRange(int startIndex, int endIndex) {
        public BridgeRange {
            if (startIndex < 0) {
                throw new IllegalArgumentException("startIndex must be non-negative");
            }
            if (endIndex < startIndex) {
                throw new IllegalArgumentException("endIndex must be >= startIndex");
            }
        }
    }
}
