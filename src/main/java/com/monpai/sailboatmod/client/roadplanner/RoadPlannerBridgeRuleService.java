package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.List;

public class RoadPlannerBridgeRuleService {
    private static final int MAJOR_BRIDGE_HORIZONTAL_THRESHOLD = 96;
    private static final int MAJOR_BRIDGE_VERTICAL_THRESHOLD = 10;

    private final LandProbe landProbe;

    public RoadPlannerBridgeRuleService(LandProbe landProbe) {
        this.landProbe = landProbe == null ? (x, z) -> true : landProbe;
    }

    public Decision evaluateRoadTool(List<BlockPos> nodes, BlockPos target) {
        if (nodes == null || nodes.isEmpty() || target == null) {
            return Decision.accept(RoadPlannerSegmentType.ROAD, -1);
        }
        BlockPos previous = nodes.get(nodes.size() - 1);
        if (requiresMajorBridge(previous, target)) {
            return Decision.reject(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE, -1);
        }
        return Decision.accept(RoadPlannerSegmentType.ROAD, -1);
    }

    public Decision evaluateBridgeTool(List<BlockPos> nodes, BlockPos target) {
        int startIndex = lastLandNodeIndex(nodes);
        if (startIndex < 0) {
            return Decision.reject(RoadPlannerSegmentType.BRIDGE_MAJOR, -1);
        }
        if (target == null || !landProbe.isLand(target.getX(), target.getZ())) {
            return Decision.reject(RoadPlannerSegmentType.BRIDGE_MAJOR, startIndex);
        }
        return Decision.accept(RoadPlannerSegmentType.BRIDGE_MAJOR, startIndex);
    }

    private int lastLandNodeIndex(List<BlockPos> nodes) {
        if (nodes == null) {
            return -1;
        }
        for (int index = nodes.size() - 1; index >= 0; index--) {
            BlockPos node = nodes.get(index);
            if (node != null && landProbe.isLand(node.getX(), node.getZ())) {
                return index;
            }
        }
        return -1;
    }

    private static boolean requiresMajorBridge(BlockPos from, BlockPos to) {
        int horizontal = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
        int vertical = Math.abs(to.getY() - from.getY());
        return horizontal >= MAJOR_BRIDGE_HORIZONTAL_THRESHOLD || vertical >= MAJOR_BRIDGE_VERTICAL_THRESHOLD;
    }

    public record Decision(boolean accepted, RoadPlannerSegmentType segmentType, int bridgeStartNodeIndex) {
        static Decision accept(RoadPlannerSegmentType type, int bridgeStartNodeIndex) {
            return new Decision(true, type, bridgeStartNodeIndex);
        }

        static Decision reject(RoadPlannerSegmentType type, int bridgeStartNodeIndex) {
            return new Decision(false, type, bridgeStartNodeIndex);
        }
    }

    @FunctionalInterface
    public interface LandProbe {
        boolean isLand(int x, int z);
    }
}
