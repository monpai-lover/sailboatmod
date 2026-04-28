package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;

public class RoadPlannerNodeHitTester {
    private final double radius;

    public RoadPlannerNodeHitTester(double radius) {
        this.radius = Math.max(1.0D, radius);
    }

    public Optional<RoadPlannerNodeSelection> hitNode(List<BlockPos> nodes, double worldX, double worldZ) {
        if (nodes == null || nodes.isEmpty()) {
            return Optional.empty();
        }
        int bestIndex = -1;
        double bestDistance = radius * radius;
        for (int index = 0; index < nodes.size(); index++) {
            BlockPos node = nodes.get(index);
            double dx = node.getX() - worldX;
            double dz = node.getZ() - worldZ;
            double distance = dx * dx + dz * dz;
            if (distance <= bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex < 0 ? Optional.empty() : Optional.of(new RoadPlannerNodeSelection(bestIndex));
    }
}
