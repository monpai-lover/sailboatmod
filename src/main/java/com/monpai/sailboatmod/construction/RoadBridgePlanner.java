package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public final class RoadBridgePlanner {
    private RoadBridgePlanner() {}

    public enum BridgeMode {
        FLAT_BRIDGE,
        PIER_BRIDGE,
        NONE
    }

    public record BridgeSpanPlan(BridgeMode mode, boolean valid, int deckY) {}

    public record BridgeProfile(int startIndex, int endIndex) {}

    public static List<RoadPlacementPlan.BridgeRange> normalizeRanges(
            List<RoadPlacementPlan.BridgeRange> ranges, int pathSize) {
        return ranges == null ? List.of() : ranges;
    }

    public static BridgeSpanPlan planBridgeSpan(
            List<BlockPos> centerPath,
            RoadPlacementPlan.BridgeRange range,
            IntPredicate bridgePredicate,
            IntPredicate navigablePredicate,
            IntUnaryOperator terrainSurfaceY,
            IntUnaryOperator waterSurfaceY,
            IntPredicate stableFoundation) {
        return new BridgeSpanPlan(BridgeMode.NONE, false, 0);
    }

    public static List<BridgeProfile> classifyRanges(
            List<BlockPos> centerPath,
            List<RoadPlacementPlan.BridgeRange> bridgeRanges,
            IntPredicate bridgePredicate,
            IntUnaryOperator terrainSurfaceY) {
        return List.of();
    }

    public static BridgeProfile buildNavigableBridgeProfile(
            int startIndex, int endIndex, int maxWaterSurfaceY) {
        return new BridgeProfile(startIndex, endIndex);
    }
}
