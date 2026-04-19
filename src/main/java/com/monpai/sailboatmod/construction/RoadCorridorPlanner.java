package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public final class RoadCorridorPlanner {
    private RoadCorridorPlanner() {}

    public static RoadCorridorPlan plan(List<BlockPos> centerPath) {
        return RoadCorridorPlan.empty();
    }

    public static RoadCorridorPlan plan(
            List<BlockPos> centerPath,
            List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans,
            int[] placementHeights) {
        return RoadCorridorPlan.empty();
    }

    public static List<RoadPlacementPlan.BridgeRange> detectContiguousSubranges(
            List<BlockPos> centerPath,
            List<RoadPlacementPlan.BridgeRange> bridgeRanges,
            IntPredicate predicate) {
        return List.of();
    }
}
