package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;

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
        if (ranges == null || ranges.isEmpty()) return List.of();
        List<RoadPlacementPlan.BridgeRange> normalized = new ArrayList<>();
        for (RoadPlacementPlan.BridgeRange r : ranges) {
            int start = Math.max(0, r.startIndex());
            int end = Math.min(pathSize - 1, r.endIndex());
            if (start <= end) {
                normalized.add(new RoadPlacementPlan.BridgeRange(start, end));
            }
        }
        return normalized;
    }

    public static BridgeSpanPlan planBridgeSpan(
            List<BlockPos> centerPath,
            RoadPlacementPlan.BridgeRange range,
            IntPredicate bridgePredicate,
            IntPredicate navigablePredicate,
            IntUnaryOperator terrainSurfaceY,
            IntUnaryOperator waterSurfaceY,
            IntPredicate stableFoundation) {
        if (centerPath == null || range == null) {
            return new BridgeSpanPlan(BridgeMode.NONE, false, 0);
        }
        int spanLength = range.endIndex() - range.startIndex();
        if (spanLength <= 0) {
            return new BridgeSpanPlan(BridgeMode.NONE, false, 0);
        }

        int maxWaterY = 0;
        boolean hasNavigable = false;
        for (int i = range.startIndex(); i <= range.endIndex() && i < centerPath.size(); i++) {
            int wsy = waterSurfaceY.applyAsInt(i);
            if (wsy > maxWaterY) maxWaterY = wsy;
            if (navigablePredicate != null && navigablePredicate.test(i)) hasNavigable = true;
        }

        int deckY = maxWaterY + 5;
        BridgeMode mode = hasNavigable ? BridgeMode.PIER_BRIDGE : BridgeMode.FLAT_BRIDGE;
        return new BridgeSpanPlan(mode, true, deckY);
    }

    public static List<BridgeProfile> classifyRanges(
            List<BlockPos> centerPath,
            List<RoadPlacementPlan.BridgeRange> bridgeRanges,
            IntPredicate bridgePredicate,
            IntUnaryOperator terrainSurfaceY) {
        if (centerPath == null || bridgeRanges == null || bridgeRanges.isEmpty()) {
            return List.of();
        }
        List<BridgeProfile> profiles = new ArrayList<>();
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            profiles.add(new BridgeProfile(range.startIndex(), range.endIndex()));
        }
        return profiles;
    }

    public static BridgeProfile buildNavigableBridgeProfile(
            int startIndex, int endIndex, int maxWaterSurfaceY) {
        return new BridgeProfile(startIndex, endIndex);
    }
}
