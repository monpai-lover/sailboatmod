package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import java.util.List;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public record RoadPlacementPlan(
        List<BlockPos> centerPath,
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
        RoadCorridorPlan corridorPlan
) {
    public record BridgeRange(int startIndex, int endIndex) {}
}
