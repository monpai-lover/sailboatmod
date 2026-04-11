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
                                List<BridgeRange> navigableWaterBridgeRanges,
                                List<BlockPos> ownedBlocks,
                                BlockPos startHighlightPos,
                                BlockPos endHighlightPos,
                                BlockPos focusPos,
                                RoadCorridorPlan corridorPlan) {
    public RoadPlacementPlan {
        centerPath = copyPositions(centerPath);
        sourceInternalAnchor = immutable(sourceInternalAnchor);
        sourceBoundaryAnchor = immutable(sourceBoundaryAnchor);
        targetBoundaryAnchor = immutable(targetBoundaryAnchor);
        targetInternalAnchor = immutable(targetInternalAnchor);
        ghostBlocks = copyGhostBlocks(ghostBlocks);
        buildSteps = copyBuildSteps(buildSteps);
        bridgeRanges = bridgeRanges == null ? List.of() : List.copyOf(bridgeRanges);
        navigableWaterBridgeRanges = navigableWaterBridgeRanges == null ? List.of() : List.copyOf(navigableWaterBridgeRanges);
        ownedBlocks = copyOwnedBlocks(ownedBlocks);
        startHighlightPos = immutable(startHighlightPos);
        endHighlightPos = immutable(endHighlightPos);
        focusPos = immutable(focusPos);
    }

    public RoadPlacementPlan(List<BlockPos> centerPath,
                             BlockPos sourceInternalAnchor,
                             BlockPos sourceBoundaryAnchor,
                             BlockPos targetBoundaryAnchor,
                             BlockPos targetInternalAnchor,
                             List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                             List<RoadGeometryPlanner.RoadBuildStep> buildSteps,
                             List<BridgeRange> bridgeRanges,
                             List<BridgeRange> navigableWaterBridgeRanges,
                             List<BlockPos> ownedBlocks,
                             BlockPos startHighlightPos,
                             BlockPos endHighlightPos,
                             BlockPos focusPos) {
        this(centerPath,
                sourceInternalAnchor,
                sourceBoundaryAnchor,
                targetBoundaryAnchor,
                targetInternalAnchor,
                ghostBlocks,
                buildSteps,
                bridgeRanges,
                navigableWaterBridgeRanges,
                ownedBlocks,
                startHighlightPos,
                endHighlightPos,
                focusPos,
                null);
    }

    public RoadPlacementPlan(List<BlockPos> centerPath,
                             BlockPos sourceInternalAnchor,
                             BlockPos sourceBoundaryAnchor,
                             BlockPos targetBoundaryAnchor,
                             BlockPos targetInternalAnchor,
                             List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                             List<RoadGeometryPlanner.RoadBuildStep> buildSteps,
                             List<BridgeRange> bridgeRanges,
                             List<BlockPos> ownedBlocks,
                             BlockPos startHighlightPos,
                             BlockPos endHighlightPos,
                             BlockPos focusPos) {
        this(centerPath,
                sourceInternalAnchor,
                sourceBoundaryAnchor,
                targetBoundaryAnchor,
                targetInternalAnchor,
                ghostBlocks,
                buildSteps,
                bridgeRanges,
                List.of(),
                ownedBlocks,
                startHighlightPos,
                endHighlightPos,
                focusPos,
                null);
    }

    public RoadPlacementPlan(List<BlockPos> centerPath,
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
        this(centerPath,
                sourceInternalAnchor,
                sourceBoundaryAnchor,
                targetBoundaryAnchor,
                targetInternalAnchor,
                ghostBlocks,
                buildSteps,
                bridgeRanges,
                List.of(),
                defaultOwnedBlocks(ghostBlocks),
                startHighlightPos,
                endHighlightPos,
                focusPos,
                null);
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

    private static List<RoadGeometryPlanner.GhostRoadBlock> copyGhostBlocks(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        if (ghostBlocks == null || ghostBlocks.isEmpty()) {
            return List.of();
        }
        List<RoadGeometryPlanner.GhostRoadBlock> copied = new ArrayList<>(ghostBlocks.size());
        for (RoadGeometryPlanner.GhostRoadBlock block : ghostBlocks) {
            if (block != null) {
                copied.add(new RoadGeometryPlanner.GhostRoadBlock(block.pos(), block.state()));
            }
        }
        return copied.isEmpty() ? List.of() : List.copyOf(copied);
    }

    private static List<RoadGeometryPlanner.RoadBuildStep> copyBuildSteps(List<RoadGeometryPlanner.RoadBuildStep> buildSteps) {
        if (buildSteps == null || buildSteps.isEmpty()) {
            return List.of();
        }
        List<RoadGeometryPlanner.RoadBuildStep> copied = new ArrayList<>(buildSteps.size());
        for (int i = 0; i < buildSteps.size(); i++) {
            RoadGeometryPlanner.RoadBuildStep step = Objects.requireNonNull(buildSteps.get(i), "buildSteps contains null at index " + i);
            copied.add(new RoadGeometryPlanner.RoadBuildStep(step.order(), step.pos(), step.state()));
        }
        return List.copyOf(copied);
    }

    private static List<BlockPos> copyOwnedBlocks(List<BlockPos> ownedBlocks) {
        if (ownedBlocks == null || ownedBlocks.isEmpty()) {
            return List.of();
        }
        List<BlockPos> copied = new ArrayList<>(ownedBlocks.size());
        for (int i = 0; i < ownedBlocks.size(); i++) {
            BlockPos pos = Objects.requireNonNull(ownedBlocks.get(i), "ownedBlocks contains null at index " + i);
            copied.add(pos.immutable());
        }
        return List.copyOf(copied);
    }

    private static List<BlockPos> defaultOwnedBlocks(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        if (ghostBlocks == null || ghostBlocks.isEmpty()) {
            return List.of();
        }
        List<BlockPos> owned = new ArrayList<>(ghostBlocks.size());
        for (RoadGeometryPlanner.GhostRoadBlock block : ghostBlocks) {
            if (block != null && block.pos() != null) {
                owned.add(block.pos().immutable());
            }
        }
        return List.copyOf(owned);
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
