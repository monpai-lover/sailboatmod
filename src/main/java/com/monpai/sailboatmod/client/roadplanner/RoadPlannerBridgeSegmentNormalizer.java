package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerBridgeSegmentNormalizer {
    private static final int MAX_INTERNAL_LAND_GAP_BLOCKS = 8;

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

    private static RoadPlannerSegmentType bridgeTypeNear(List<RoadPlannerSegmentType> types, int segmentIndex) {
        RoadPlannerSegmentType before = segmentIndex > 0 ? types.get(segmentIndex - 1) : null;
        RoadPlannerSegmentType after = segmentIndex + 1 < types.size() ? types.get(segmentIndex + 1) : null;
        if (before == RoadPlannerSegmentType.BRIDGE_MAJOR || after == RoadPlannerSegmentType.BRIDGE_MAJOR) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        if (before == RoadPlannerSegmentType.BRIDGE_SMALL || after == RoadPlannerSegmentType.BRIDGE_SMALL) {
            return RoadPlannerSegmentType.BRIDGE_SMALL;
        }
        return RoadPlannerSegmentType.BRIDGE_MAJOR;
    }

    public static Result normalize(List<BlockPos> nodes,
                                   List<RoadPlannerSegmentType> segmentTypes,
                                   RoadPlannerBridgeRuleService.LandProbe landProbe) {
        List<BlockPos> safeNodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        List<RoadPlannerSegmentType> normalizedTypes = normalizeSegmentTypes(segmentTypes, safeNodes.size());
        RoadPlannerBridgeRuleService.LandProbe safeLandProbe = landProbe == null ? (x, z) -> true : landProbe;
        List<BridgeRange> ranges = new ArrayList<>();
        List<String> issues = new ArrayList<>();

        for (int segmentIndex = 0; segmentIndex < normalizedTypes.size(); segmentIndex++) {
            if (normalizedTypes.get(segmentIndex) != RoadPlannerSegmentType.ROAD) {
                continue;
            }
            BlockPos from = safeNodes.get(segmentIndex);
            BlockPos to = safeNodes.get(segmentIndex + 1);
            if (!isLand(from, safeLandProbe) || !isLand(to, safeLandProbe)) {
                normalizedTypes.set(segmentIndex, bridgeTypeNear(normalizedTypes, segmentIndex));
            }
        }
        restoreLongLandConnectors(normalizedTypes, safeNodes, safeLandProbe);

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
            if (!isTinyInternalLandGap(safeNodes, gapStart, i)) {
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

    private static void restoreLongLandConnectors(List<RoadPlannerSegmentType> types,
                                                  List<BlockPos> nodes,
                                                  RoadPlannerBridgeRuleService.LandProbe landProbe) {
        int index = 0;
        while (index < types.size()) {
            if (!isBridge(types.get(index))) {
                index++;
                continue;
            }
            int runStart = index;
            while (index < types.size() && isBridge(types.get(index))) {
                index++;
            }
            int runEnd = index - 1;
            restoreLongLandConnectorsInBridgeRun(types, nodes, landProbe, runStart, runEnd);
        }
    }

    private static void restoreLongLandConnectorsInBridgeRun(List<RoadPlannerSegmentType> types,
                                                             List<BlockPos> nodes,
                                                             RoadPlannerBridgeRuleService.LandProbe landProbe,
                                                             int runStart,
                                                             int runEnd) {
        int index = runStart;
        while (index <= runEnd) {
            if (!isLandOnlySegment(nodes, landProbe, index)) {
                index++;
                continue;
            }
            int landStart = index;
            while (index + 1 <= runEnd && isLandOnlySegment(nodes, landProbe, index + 1)) {
                index++;
            }
            int landEnd = index;
            boolean waterBridgeBefore = hasWaterTouchingSegment(nodes, landProbe, runStart, landStart - 1);
            boolean waterBridgeAfter = hasWaterTouchingSegment(nodes, landProbe, landEnd + 1, runEnd);
            if (waterBridgeBefore && waterBridgeAfter && !isTinyInternalLandGap(nodes, landStart, landEnd + 1)) {
                for (int segment = landStart; segment <= landEnd; segment++) {
                    types.set(segment, RoadPlannerSegmentType.ROAD);
                }
            }
            index++;
        }
    }

    private static boolean isLandOnlySegment(List<BlockPos> nodes,
                                             RoadPlannerBridgeRuleService.LandProbe landProbe,
                                             int segmentIndex) {
        return nodes != null
                && segmentIndex >= 0
                && segmentIndex + 1 < nodes.size()
                && isLand(nodes.get(segmentIndex), landProbe)
                && isLand(nodes.get(segmentIndex + 1), landProbe);
    }

    private static boolean hasWaterTouchingSegment(List<BlockPos> nodes,
                                                   RoadPlannerBridgeRuleService.LandProbe landProbe,
                                                   int startSegment,
                                                   int endSegment) {
        if (nodes == null || startSegment > endSegment) {
            return false;
        }
        for (int segment = Math.max(0, startSegment); segment <= endSegment && segment + 1 < nodes.size(); segment++) {
            if (!isLand(nodes.get(segment), landProbe) || !isLand(nodes.get(segment + 1), landProbe)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTinyInternalLandGap(List<BlockPos> nodes, int gapStartSegment, int nextBridgeSegment) {
        if (nodes == null || gapStartSegment < 0 || nextBridgeSegment < 0
                || gapStartSegment >= nodes.size() || nextBridgeSegment >= nodes.size()) {
            return false;
        }
        BlockPos start = nodes.get(gapStartSegment);
        BlockPos end = nodes.get(nextBridgeSegment);
        if (start == null || end == null) {
            return false;
        }
        int horizontalBlocks = Math.abs(end.getX() - start.getX()) + Math.abs(end.getZ() - start.getZ());
        return horizontalBlocks <= MAX_INTERNAL_LAND_GAP_BLOCKS;
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
