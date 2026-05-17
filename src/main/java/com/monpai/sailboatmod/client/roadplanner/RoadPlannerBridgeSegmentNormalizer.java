package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerBridgeSegmentNormalizer {
    private static final int MAX_INTERNAL_ROAD_GAP_SEGMENTS = 4;

    private RoadPlannerBridgeSegmentNormalizer() {
    }

    private static boolean isBridge(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.BRIDGE_SMALL || type == RoadPlannerSegmentType.BRIDGE_MAJOR;
    }

    private static RoadPlannerSegmentType bridgeTypeForRange(List<RoadPlannerSegmentType> types, int startInclusive, int endInclusive) {
        for (int index = startInclusive; index <= endInclusive && index < types.size(); index++) {
            if (types.get(index) == RoadPlannerSegmentType.BRIDGE_MAJOR) {
                return RoadPlannerSegmentType.BRIDGE_MAJOR;
            }
        }
        return RoadPlannerSegmentType.BRIDGE_SMALL;
    }

    public static Result normalize(List<BlockPos> nodes,
                                   List<RoadPlannerSegmentType> segmentTypes,
                                   RoadPlannerBridgeRuleService.LandProbe landProbe) {
        List<BlockPos> safeNodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        List<RoadPlannerSegmentType> normalizedTypes = normalizeSegmentTypes(segmentTypes, safeNodes.size());
        RoadPlannerBridgeRuleService.LandProbe safeLandProbe = landProbe == null ? (x, z) -> true : landProbe;
        List<BridgeRange> ranges = new ArrayList<>();
        List<String> issues = new ArrayList<>();

        for (int i = 1; i < normalizedTypes.size() - 1; i++) {
            if (normalizedTypes.get(i) != RoadPlannerSegmentType.ROAD || !isBridge(normalizedTypes.get(i - 1))) {
                continue;
            }
            int gapStart = i;
            while (i < normalizedTypes.size() - 1 && normalizedTypes.get(i) == RoadPlannerSegmentType.ROAD) {
                i++;
            }
            if (!isBridge(normalizedTypes.get(i))) {
                continue;
            }
            if (i - gapStart > MAX_INTERNAL_ROAD_GAP_SEGMENTS) {
                continue;
            }
            RoadPlannerSegmentType promoted = bridgeTypeForRange(normalizedTypes, gapStart - 1, i);
            for (int gapIndex = gapStart; gapIndex < i; gapIndex++) {
                normalizedTypes.set(gapIndex, promoted);
            }
        }

        int index = 0;
        while (index < normalizedTypes.size()) {
            if (!isBridge(normalizedTypes.get(index))) {
                index++;
                continue;
            }
            int bridgeStart = index;
            while (index < normalizedTypes.size() && isBridge(normalizedTypes.get(index))) {
                index++;
            }
            int bridgeEnd = index - 1;
            RoadPlannerSegmentType rangeBridgeType = bridgeTypeForRange(normalizedTypes, bridgeStart, bridgeEnd);
            for (int bridgeIndex = bridgeStart; bridgeIndex <= bridgeEnd; bridgeIndex++) {
                normalizedTypes.set(bridgeIndex, rangeBridgeType);
            }

            int rampUpSegment = bridgeStart;
            if (bridgeStart > 0 && isLand(safeNodes.get(bridgeStart), safeLandProbe)) {
                rampUpSegment = bridgeStart;
            } else if (bridgeStart > 0) {
                rampUpSegment = bridgeStart - 1;
                normalizedTypes.set(rampUpSegment, rangeBridgeType);
            } else if (!isLand(safeNodes.get(0), safeLandProbe)) {
                issues.add("bridge_missing_entry_land_anchor");
            }

            int rampDownSegment = bridgeEnd;
            int exitNodeIndex = bridgeEnd + 1;
            if (exitNodeIndex < safeNodes.size() && isLand(safeNodes.get(exitNodeIndex), safeLandProbe)) {
                rampDownSegment = bridgeEnd;
            } else if (exitNodeIndex + 1 < safeNodes.size() && isLand(safeNodes.get(exitNodeIndex + 1), safeLandProbe)) {
                rampDownSegment = bridgeEnd + 1;
                normalizedTypes.set(rampDownSegment, rangeBridgeType);
                index = rampDownSegment + 1;
            } else if (exitNodeIndex >= safeNodes.size() || !isLand(safeNodes.get(exitNodeIndex), safeLandProbe)) {
                issues.add("bridge_missing_exit_land_anchor");
            }

            ranges.add(new BridgeRange(rampUpSegment, rampDownSegment + 1));
        }
        return new Result(safeNodes, normalizedTypes, ranges, issues);
    }

    private static List<RoadPlannerSegmentType> normalizeSegmentTypes(List<RoadPlannerSegmentType> segmentTypes, int nodeCount) {
        int expected = Math.max(0, nodeCount - 1);
        List<RoadPlannerSegmentType> normalized = new ArrayList<>(expected);
        for (int index = 0; index < expected; index++) {
            RoadPlannerSegmentType type = segmentTypes != null && index < segmentTypes.size() ? segmentTypes.get(index) : RoadPlannerSegmentType.ROAD;
            normalized.add(type == null ? RoadPlannerSegmentType.ROAD : type);
        }
        return normalized;
    }

    private static boolean isLand(BlockPos pos, RoadPlannerBridgeRuleService.LandProbe landProbe) {
        return pos != null && landProbe.isLand(pos.getX(), pos.getZ());
    }

    public record BridgeRange(int startSegmentIndex, int endSegmentIndexExclusive) {
    }

    public record Result(List<BlockPos> nodes,
                         List<RoadPlannerSegmentType> segmentTypes,
                         List<BridgeRange> bridgeRanges,
                         List<String> issues) {
        public Result {
            nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
            segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
            bridgeRanges = bridgeRanges == null ? List.of() : List.copyOf(bridgeRanges);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public boolean hasBlockingIssues() {
            return !issues.isEmpty();
        }
    }
}
