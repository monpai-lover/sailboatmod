package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerRouteExpander {
    private static final int DEFAULT_SPACING_BLOCKS = 8;

    private RoadPlannerRouteExpander() {
    }

    public static Result expand(List<BlockPos> nodes,
                                List<RoadPlannerSegmentType> segmentTypes,
                                RoadPlannerBridgeRuleService.LandProbe landProbe,
                                RoadPlannerHeightSampler heightSampler) {
        return expand(nodes, segmentTypes, landProbe, heightSampler, RoadPlannerWaterDepthProbe.shallowFromLandProbe(landProbe));
    }

    public static Result expand(List<BlockPos> nodes,
                                List<RoadPlannerSegmentType> segmentTypes,
                                RoadPlannerBridgeRuleService.LandProbe landProbe,
                                RoadPlannerHeightSampler heightSampler,
                                RoadPlannerWaterDepthProbe waterDepthProbe) {
        List<BlockPos> compactNodes = compact(nodes);
        if (compactNodes.size() < 2) {
            return Result.failure(compactNodes, normalizeSegments(segmentTypes, compactNodes.size()), "route_requires_two_nodes");
        }
        RoadPlannerBridgeRuleService.LandProbe safeLandProbe = landProbe == null ? (x, z) -> true : landProbe;
        RoadPlannerHeightSampler safeHeightSampler = heightSampler == null ? (x, z) -> 64 : heightSampler;
        RoadPlannerWaterDepthProbe safeWaterDepthProbe = waterDepthProbe == null
                ? RoadPlannerWaterDepthProbe.shallowFromLandProbe(safeLandProbe)
                : waterDepthProbe;
        RoadPlannerBridgeRuleService.LandProbe bridgeLandProbe = (x, z) ->
                safeLandProbe.isLand(x, z) || safeWaterDepthProbe.waterDepthAt(x, z) <= 0;
        List<RoadPlannerSegmentType> compactTypes = normalizeSegments(segmentTypes, compactNodes.size());
        List<BlockPos> expandedNodes = new ArrayList<>();
        List<RoadPlannerSegmentType> expandedTypes = new ArrayList<>();
        addDistinct(expandedNodes, compactNodes.get(0));
        int segmentIndex = 0;
        while (segmentIndex < compactNodes.size() - 1) {
            BlockPos from = compactNodes.get(segmentIndex);
            BlockPos to = compactNodes.get(segmentIndex + 1);
            RoadPlannerSegmentType requestedType = compactTypes.get(segmentIndex);
            List<SegmentPoint> segmentPoints = null;
            int consumedSegments = 1;
            if (requestedType == RoadPlannerSegmentType.ROAD
                    && !bridgeLandProbe.isLand(to.getX(), to.getZ())
                    && segmentIndex + 2 < compactNodes.size()
                    && compactTypes.get(segmentIndex + 1) == RoadPlannerSegmentType.ROAD) {
                BlockPos nextLandAnchor = compactNodes.get(segmentIndex + 2);
                if (bridgeLandProbe.isLand(from.getX(), from.getZ()) && bridgeLandProbe.isLand(nextLandAnchor.getX(), nextLandAnchor.getZ())) {
                    List<SegmentPoint> combinedPoints = expandSegment(from, nextLandAnchor, RoadPlannerSegmentType.ROAD,
                            bridgeLandProbe, safeHeightSampler, safeWaterDepthProbe);
                    if (combinedPoints.stream().anyMatch(point -> isBridge(point.segmentType()))) {
                        segmentPoints = combinedPoints;
                        consumedSegments = 2;
                    }
                }
            }
            if (segmentPoints == null) {
                segmentPoints = expandSegment(from, to, requestedType, bridgeLandProbe, safeHeightSampler, safeWaterDepthProbe);
            }
            for (int index = 1; index < segmentPoints.size(); index++) {
                SegmentPoint previous = segmentPoints.get(index - 1);
                SegmentPoint point = segmentPoints.get(index);
                addConnectedNode(expandedNodes, expandedTypes, point.pos(), segmentTypeForConnection(previous, point));
            }
            segmentIndex += consumedSegments;
        }
        RoadPlannerBridgeSegmentNormalizer.Result normalized = RoadPlannerBridgeSegmentNormalizer.normalize(expandedNodes, expandedTypes, bridgeLandProbe);
        boolean hasBridge = normalized.segmentTypes().stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL);
        boolean success = !normalized.hasBlockingIssues() || hasBridge;
        return new Result(success, normalized.nodes(), normalized.segmentTypes(), normalized.issues());
    }

    private static List<SegmentPoint> expandSegment(BlockPos from,
                                                    BlockPos to,
                                                    RoadPlannerSegmentType requestedType,
                                                    RoadPlannerBridgeRuleService.LandProbe landProbe,
                                                    RoadPlannerHeightSampler heightSampler,
                                                    RoadPlannerWaterDepthProbe waterDepthProbe) {
        if (requestedType == RoadPlannerSegmentType.TUNNEL) {
            return interpolateTyped(from, to, RoadPlannerSegmentType.TUNNEL, heightSampler);
        }
        if (requestedType == RoadPlannerSegmentType.BRIDGE_MAJOR || requestedType == RoadPlannerSegmentType.BRIDGE_SMALL) {
            return interpolateTyped(from, to, requestedType, heightSampler);
        }
        RoadPlannerWaterCrossingSplitter.SplitResult split = RoadPlannerWaterCrossingSplitter.split(from, to, landProbe, heightSampler, waterDepthProbe);
        if (split.didSplit()) {
            List<SegmentPoint> points = new ArrayList<>();
            for (RoadPlannerWaterCrossingSplitter.SplitNode node : split.nodes()) {
                points.add(new SegmentPoint(node.pos(), node.segmentType()));
            }
            return points;
        }
        return interpolateTyped(from, to, RoadPlannerSegmentType.ROAD, heightSampler);
    }

    private static List<SegmentPoint> interpolateTyped(BlockPos from,
                                                       BlockPos to,
                                                       RoadPlannerSegmentType type,
                                                       RoadPlannerHeightSampler heightSampler) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(distance / DEFAULT_SPACING_BLOCKS));
        List<SegmentPoint> points = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            int x = (int) Math.round(from.getX() + dx * t);
            int z = (int) Math.round(from.getZ() + dz * t);
            int y = heightSampler.heightAt(x, z);
            points.add(new SegmentPoint(new BlockPos(x, y, z), type));
        }
        return points;
    }

    private static List<BlockPos> compact(List<BlockPos> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        List<BlockPos> compact = new ArrayList<>();
        for (BlockPos node : nodes) {
            if (node != null) {
                addDistinct(compact, node);
            }
        }
        return List.copyOf(compact);
    }

    private static List<RoadPlannerSegmentType> normalizeSegments(List<RoadPlannerSegmentType> segmentTypes, int nodeCount) {
        int expected = Math.max(0, nodeCount - 1);
        List<RoadPlannerSegmentType> normalized = new ArrayList<>(expected);
        for (int index = 0; index < expected; index++) {
            RoadPlannerSegmentType type = segmentTypes != null && index < segmentTypes.size() ? segmentTypes.get(index) : RoadPlannerSegmentType.ROAD;
            normalized.add(type == null || type == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE ? RoadPlannerSegmentType.ROAD : type);
        }
        return normalized;
    }

    private static void addConnectedNode(List<BlockPos> nodes,
                                         List<RoadPlannerSegmentType> segmentTypes,
                                         BlockPos node,
                                         RoadPlannerSegmentType segmentType) {
        if (node == null) {
            return;
        }
        if (!nodes.isEmpty() && nodes.get(nodes.size() - 1).equals(node)) {
            return;
        }
        nodes.add(node.immutable());
        if (nodes.size() > 1) {
            segmentTypes.add(segmentType == null ? RoadPlannerSegmentType.ROAD : segmentType);
        }
    }

    private static void addDistinct(List<BlockPos> nodes, BlockPos node) {
        if (node != null && (nodes.isEmpty() || !nodes.get(nodes.size() - 1).equals(node))) {
            nodes.add(node.immutable());
        }
    }

    private static RoadPlannerSegmentType segmentTypeForConnection(SegmentPoint previous,
                                                                    SegmentPoint point) {
        RoadPlannerSegmentType previousType = previous.segmentType();
        RoadPlannerSegmentType pointType = point.segmentType();
        if (previousType == RoadPlannerSegmentType.BRIDGE_MAJOR || pointType == RoadPlannerSegmentType.BRIDGE_MAJOR) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        if (previousType == RoadPlannerSegmentType.BRIDGE_SMALL || pointType == RoadPlannerSegmentType.BRIDGE_SMALL) {
            return RoadPlannerSegmentType.BRIDGE_SMALL;
        }
        if (previousType == RoadPlannerSegmentType.TUNNEL || pointType == RoadPlannerSegmentType.TUNNEL) {
            return RoadPlannerSegmentType.TUNNEL;
        }
        return RoadPlannerSegmentType.ROAD;
    }

    private static boolean isBridge(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL;
    }

    private record SegmentPoint(BlockPos pos, RoadPlannerSegmentType segmentType) {
    }

    public record Result(boolean success,
                         List<BlockPos> nodes,
                         List<RoadPlannerSegmentType> segmentTypes,
                         List<String> issues) {
        public Result {
            nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
            segmentTypes = segmentTypes == null ? List.of() : List.copyOf(segmentTypes);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }

        public static Result failure(List<BlockPos> nodes, List<RoadPlannerSegmentType> segmentTypes, String issue) {
            return new Result(false, nodes, segmentTypes, List.of(issue));
        }
    }
}
