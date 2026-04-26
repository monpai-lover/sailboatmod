package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;

import java.util.Comparator;
import java.util.Optional;

public class RoadGraphHitTester {
    private final RoadNetworkGraph graph;

    public RoadGraphHitTester(RoadNetworkGraph graph) {
        this.graph = graph;
    }

    public Optional<RoadGraphEdge> nearestEdge(double worldX, double worldZ, double thresholdBlocks) {
        return graph.edges().stream()
                .filter(edge -> distanceToEdge(edge, worldX, worldZ) <= thresholdBlocks)
                .min(Comparator.comparingDouble(edge -> distanceToEdge(edge, worldX, worldZ)));
    }

    private double distanceToEdge(RoadGraphEdge edge, double worldX, double worldZ) {
        BlockPos from = graph.node(edge.fromNodeId()).orElseThrow().position();
        BlockPos to = graph.node(edge.toNodeId()).orElseThrow().position();
        return distanceToSegment(worldX, worldZ, from.getX(), from.getZ(), to.getX(), to.getZ());
    }

    private double distanceToSegment(double pointX, double pointZ, double startX, double startZ, double endX, double endZ) {
        double segmentX = endX - startX;
        double segmentZ = endZ - startZ;
        double lengthSquared = segmentX * segmentX + segmentZ * segmentZ;
        if (lengthSquared == 0) {
            return Math.hypot(pointX - startX, pointZ - startZ);
        }
        double t = ((pointX - startX) * segmentX + (pointZ - startZ) * segmentZ) / lengthSquared;
        double clamped = Math.max(0, Math.min(1, t));
        double closestX = startX + clamped * segmentX;
        double closestZ = startZ + clamped * segmentZ;
        return Math.hypot(pointX - closestX, pointZ - closestZ);
    }
}
