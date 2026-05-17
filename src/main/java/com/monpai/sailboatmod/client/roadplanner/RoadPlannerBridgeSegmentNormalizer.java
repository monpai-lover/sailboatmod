package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerBridgeSegmentNormalizer {
    private RoadPlannerBridgeSegmentNormalizer() {
    }

    public static Result normalize(List<BlockPos> nodes,
                                   List<RoadPlannerSegmentType> segmentTypes,
                                   RoadPlannerBridgeRuleService.LandProbe landProbe) {
        List<BlockPos> safeNodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        List<RoadPlannerSegmentType> normalizedTypes = normalizeSegmentTypes(segmentTypes, safeNodes.size());
        RoadPlannerBridgeRuleService.LandProbe safeLandProbe = landProbe == null ? (x, z) -> true : landProbe;
        List<BridgeRange> ranges = new ArrayList<>();
        List<String> issues = new ArrayList<>();

        int index = 0;
        while (index < normalizedTypes.size()) {
            if (normalizedTypes.get(index) != RoadPlannerSegmentType.BRIDGE_MAJOR) {
                index++;
                continue;
            }
            int bridgeStart = index;
            while (index < normalizedTypes.size() && normalizedTypes.get(index) == RoadPlannerSegmentType.BRIDGE_MAJOR) {
                index++;
            }
            int bridgeEnd = index - 1;

            int rampUpSegment = bridgeStart;
            if (bridgeStart > 0 && isLand(safeNodes.get(bridgeStart), safeLandProbe)) {
                rampUpSegment = bridgeStart;
            } else if (bridgeStart > 0) {
                rampUpSegment = bridgeStart - 1;
                normalizedTypes.set(rampUpSegment, RoadPlannerSegmentType.BRIDGE_MAJOR);
            } else if (!isLand(safeNodes.get(0), safeLandProbe)) {
                issues.add("bridge_missing_entry_land_anchor");
            }

            int rampDownSegment = bridgeEnd;
            int exitNodeIndex = bridgeEnd + 1;
            if (exitNodeIndex < safeNodes.size() && isLand(safeNodes.get(exitNodeIndex), safeLandProbe)) {
                rampDownSegment = bridgeEnd;
            } else if (exitNodeIndex + 1 < safeNodes.size() && isLand(safeNodes.get(exitNodeIndex + 1), safeLandProbe)) {
                rampDownSegment = bridgeEnd + 1;
                normalizedTypes.set(rampDownSegment, RoadPlannerSegmentType.BRIDGE_MAJOR);
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
