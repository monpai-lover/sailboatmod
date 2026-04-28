package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class RoadPlannerLinePlan {
    private final List<BlockPos> nodes = new ArrayList<>();
    private final List<RoadPlannerSegmentType> segments = new ArrayList<>();

    public AddResult addClickNode(BlockPos pos, RoadPlannerSegmentType segmentType) {
        Objects.requireNonNull(pos, "pos");
        RoadPlannerSegmentType safeType = segmentType == null ? RoadPlannerSegmentType.ROAD : segmentType;
        nodes.add(pos.immutable());
        if (nodes.size() > 1) {
            segments.add(safeType);
        }
        return new AddResult(true, safeType);
    }

    public void removeLastNode() {
        if (nodes.isEmpty()) {
            return;
        }
        nodes.remove(nodes.size() - 1);
        if (segments.size() >= nodes.size() && !segments.isEmpty()) {
            segments.remove(segments.size() - 1);
        }
    }

    public void setStartNode(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        if (nodes.isEmpty()) {
            nodes.add(pos.immutable());
            return;
        }
        nodes.set(0, pos.immutable());
    }

    public void setEndNode(BlockPos pos, RoadPlannerSegmentType segmentType) {
        Objects.requireNonNull(pos, "pos");
        RoadPlannerSegmentType safeType = segmentType == null ? RoadPlannerSegmentType.ROAD : segmentType;
        if (nodes.isEmpty()) {
            nodes.add(pos.immutable());
            return;
        }
        if (nodes.size() == 1) {
            addClickNode(pos, safeType);
            return;
        }
        nodes.set(nodes.size() - 1, pos.immutable());
        segments.set(segments.size() - 1, safeType);
    }

    public boolean removeNodeAt(int index) {
        if (index < 0 || index >= nodes.size()) {
            return false;
        }
        nodes.remove(index);
        if (segments.isEmpty()) {
            return true;
        }
        if (index == 0) {
            segments.remove(0);
        } else if (index - 1 < segments.size()) {
            segments.remove(index - 1);
        } else if (!segments.isEmpty()) {
            segments.remove(segments.size() - 1);
        }
        while (segments.size() > Math.max(0, nodes.size() - 1)) {
            segments.remove(segments.size() - 1);
        }
        return true;
    }

    public void clear() {
        nodes.clear();
        segments.clear();
    }

    public void replaceWith(List<BlockPos> newNodes, List<RoadPlannerSegmentType> newSegments) {
        nodes.clear();
        segments.clear();
        if (newNodes != null) {
            for (BlockPos node : newNodes) {
                nodes.add(node.immutable());
            }
        }
        if (newSegments != null) {
            segments.addAll(newSegments);
        }
        while (segments.size() > Math.max(0, nodes.size() - 1)) {
            segments.remove(segments.size() - 1);
        }
        while (segments.size() < Math.max(0, nodes.size() - 1)) {
            segments.add(RoadPlannerSegmentType.ROAD);
        }
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int segmentCount() {
        return segments.size();
    }

    public List<BlockPos> nodes() {
        return List.copyOf(nodes);
    }

    public List<RoadPlannerSegmentType> segments() {
        return List.copyOf(segments);
    }

    public void setSegmentTypeFromNode(int nodeIndex, RoadPlannerSegmentType type) {
        if (type == null || nodeIndex < 0 || nodeIndex >= segments.size()) {
            return;
        }
        segments.set(nodeIndex, type);
    }

    public boolean hasUnresolvedBridgeBlocker() {
        return segments.contains(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE);
    }

    public boolean canConfirm() {
        return nodes.size() >= 2 && !hasUnresolvedBridgeBlocker();
    }

    public record AddResult(boolean accepted, RoadPlannerSegmentType segmentType) {
    }
}
