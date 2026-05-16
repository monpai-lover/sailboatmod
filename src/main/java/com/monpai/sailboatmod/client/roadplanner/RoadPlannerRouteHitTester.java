package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

public final class RoadPlannerRouteHitTester {
    private final RoadPlannerNodeHitTester nodeHitTester;
    private final double segmentRadiusSq;

    public RoadPlannerRouteHitTester(double nodeRadius, double segmentRadius) {
        this.nodeHitTester = new RoadPlannerNodeHitTester(nodeRadius);
        this.segmentRadiusSq = Math.max(1.0D, segmentRadius) * Math.max(1.0D, segmentRadius);
    }

    public Optional<Hit> hit(List<BlockPos> nodes, double worldX, double worldZ) {
        if (nodes == null || nodes.size() < 2) {
            return Optional.empty();
        }
        Optional<RoadPlannerNodeSelection> node = nodeHitTester.hitNode(nodes, worldX, worldZ);
        if (node.isPresent()) {
            int nodeIndex = node.get().nodeIndex();
            int segmentIndex = Math.max(0, Math.min(nodeIndex, nodes.size() - 2));
            return Optional.of(new Hit(nodeIndex, segmentIndex));
        }
        int bestSegment = -1;
        double bestDistance = segmentRadiusSq;
        for (int index = 0; index < nodes.size() - 1; index++) {
            double distance = distanceSqToSegment(worldX, worldZ, nodes.get(index), nodes.get(index + 1));
            if (distance <= bestDistance) {
                bestDistance = distance;
                bestSegment = index;
            }
        }
        return bestSegment < 0 ? Optional.empty() : Optional.of(new Hit(bestSegment, bestSegment));
    }

    private static double distanceSqToSegment(double x, double z, BlockPos a, BlockPos b) {
        double ax = a.getX();
        double az = a.getZ();
        double bx = b.getX();
        double bz = b.getZ();
        double dx = bx - ax;
        double dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        if (lenSq <= 0.0001D) {
            double px = x - ax;
            double pz = z - az;
            return px * px + pz * pz;
        }
        double t = Math.max(0.0D, Math.min(1.0D, ((x - ax) * dx + (z - az) * dz) / lenSq));
        double px = ax + dx * t;
        double pz = az + dz * t;
        double ox = x - px;
        double oz = z - pz;
        return ox * ox + oz * oz;
    }

    public record Hit(int nodeIndex, int segmentIndex) {
    }
}
