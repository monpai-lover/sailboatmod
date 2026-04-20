package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

public final class RoadCorridorPlanner {
    private RoadCorridorPlanner() {}

    public static RoadCorridorPlan plan(List<BlockPos> centerPath) {
        if (centerPath == null || centerPath.isEmpty()) return RoadCorridorPlan.empty();
        List<RoadCorridorPlan.CorridorSlice> slices = new ArrayList<>();
        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos center = centerPath.get(i);
            List<BlockPos> columns = List.of(center.west(), center, center.east());
            slices.add(new RoadCorridorPlan.CorridorSlice(i, center, columns));
        }
        return new RoadCorridorPlan(true, centerPath, slices);
    }

    public static RoadCorridorPlan plan(
            List<BlockPos> centerPath,
            List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans,
            int[] placementHeights) {
        if (centerPath == null || centerPath.isEmpty()) return RoadCorridorPlan.empty();
        List<RoadCorridorPlan.CorridorSlice> slices = new ArrayList<>();
        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos center = centerPath.get(i);
            int y = (placementHeights != null && i < placementHeights.length) ? placementHeights[i] : center.getY();
            BlockPos adjusted = new BlockPos(center.getX(), y, center.getZ());
            List<BlockPos> columns = List.of(adjusted.west(), adjusted, adjusted.east());
            RoadCorridorPlan.SegmentKind kind = RoadCorridorPlan.SegmentKind.GROUND;
            if (bridgePlans != null) {
                for (RoadBridgePlanner.BridgeSpanPlan bp : bridgePlans) {
                    if (bp.valid() && bp.mode() != RoadBridgePlanner.BridgeMode.NONE) {
                        kind = RoadCorridorPlan.SegmentKind.BRIDGE_DECK;
                        break;
                    }
                }
            }
            slices.add(new RoadCorridorPlan.CorridorSlice(i, adjusted, columns,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), kind));
        }
        return new RoadCorridorPlan(true, centerPath, slices);
    }

    public static List<RoadPlacementPlan.BridgeRange> detectContiguousSubranges(
            List<BlockPos> centerPath,
            List<RoadPlacementPlan.BridgeRange> bridgeRanges,
            IntPredicate predicate) {
        if (centerPath == null || bridgeRanges == null || predicate == null) return List.of();
        List<RoadPlacementPlan.BridgeRange> result = new ArrayList<>();
        for (RoadPlacementPlan.BridgeRange range : bridgeRanges) {
            int start = -1;
            for (int i = range.startIndex(); i <= range.endIndex() && i < centerPath.size(); i++) {
                if (predicate.test(i)) {
                    if (start == -1) start = i;
                } else {
                    if (start != -1) {
                        result.add(new RoadPlacementPlan.BridgeRange(start, i - 1));
                        start = -1;
                    }
                }
            }
            if (start != -1) {
                result.add(new RoadPlacementPlan.BridgeRange(start, range.endIndex()));
            }
        }
        return result;
    }
}
