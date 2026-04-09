package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record RoadPlacementPlan(List<BlockPos> centerPath,
                                BlockPos sourceInternalAnchor,
                                BlockPos sourceBoundaryAnchor,
                                BlockPos targetBoundaryAnchor,
                                BlockPos targetInternalAnchor,
                                List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                List<RoadGeometryPlanner.RoadBuildStep> buildSteps,
                                List<BridgeRange> bridgeRanges,
                                BlockPos startHighlightPos,
                                BlockPos endHighlightPos,
                                BlockPos focusPos) {
    public RoadPlacementPlan {
        centerPath = copyPositions(centerPath);
        sourceInternalAnchor = immutable(sourceInternalAnchor);
        sourceBoundaryAnchor = immutable(sourceBoundaryAnchor);
        targetBoundaryAnchor = immutable(targetBoundaryAnchor);
        targetInternalAnchor = immutable(targetInternalAnchor);
        ghostBlocks = ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
        buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
        bridgeRanges = bridgeRanges == null ? List.of() : List.copyOf(bridgeRanges);
        startHighlightPos = immutable(startHighlightPos);
        endHighlightPos = immutable(endHighlightPos);
        focusPos = immutable(focusPos);
    }

    private static List<BlockPos> copyPositions(List<BlockPos> positions) {
        Objects.requireNonNull(positions, "centerPath");
        if (positions.isEmpty()) {
            return List.of();
        }
        List<BlockPos> copied = new ArrayList<>(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = Objects.requireNonNull(positions.get(i), "centerPath contains null at index " + i);
            copied.add(pos.immutable());
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
