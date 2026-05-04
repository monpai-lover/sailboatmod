package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadCenterlineBuilder {
    private RoadCenterlineBuilder() {
    }

    public static List<RoadCenterlinePoint> build(RoadRouteSection section, RoadTerrainSampler terrainSampler) {
        if (section == null || section.nodes().size() < 2) {
            return List.of();
        }
        List<RoadCenterlinePoint> points = new ArrayList<>();
        double distance = 0.0D;
        for (int segmentIndex = 0; segmentIndex < section.nodes().size() - 1; segmentIndex++) {
            List<BlockPos> segment = interpolate(section.nodes().get(segmentIndex), section.nodes().get(segmentIndex + 1));
            RoadPlannerSegmentType segmentType = section.segmentTypes().get(segmentIndex);
            for (BlockPos raw : segment) {
                if (!points.isEmpty() && points.get(points.size() - 1).pos().getX() == raw.getX() && points.get(points.size() - 1).pos().getZ() == raw.getZ()) {
                    continue;
                }
                if (!points.isEmpty()) {
                    BlockPos prev = points.get(points.size() - 1).pos();
                    distance += Math.sqrt(Math.pow(raw.getX() - prev.getX(), 2) + Math.pow(raw.getZ() - prev.getZ(), 2));
                }
                int terrainY = terrainSampler == null ? raw.getY() : terrainSampler.terrainY(raw.getX(), raw.getZ());
                BlockPos pos = new BlockPos(raw.getX(), terrainY, raw.getZ());
                points.add(new RoadCenterlinePoint(pos, segmentIndex, segmentType, terrainY, terrainY, distance));
            }
        }
        return List.copyOf(points);
    }

    private static List<BlockPos> interpolate(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz)));
        List<BlockPos> points = new ArrayList<>(steps + 1);
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            points.add(new BlockPos(
                    (int) Math.round(from.getX() + dx * t),
                    (int) Math.round(from.getY() + dy * t),
                    (int) Math.round(from.getZ() + dz * t)
            ));
        }
        return List.copyOf(points);
    }
}
