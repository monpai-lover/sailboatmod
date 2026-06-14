package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;

public final class RoadPlannerManualTerrainRules {
    private static final int SAMPLE_SPACING_BLOCKS = 4;
    private static final int MIN_BRIDGE_SPAN_BLOCKS = 12;
    private static final int MAX_ROAD_GRADE_HEIGHT_PER_SAMPLE = 5;
    private static final int MAX_ROAD_HEIGHT_RANGE = 18;

    private RoadPlannerManualTerrainRules() {
    }

    public static boolean isRoadPassable(int x,
                                         int z,
                                         RoadPlannerBridgeRuleService.LandProbe landProbe,
                                         RoadPlannerWaterDepthProbe waterDepthProbe) {
        RoadPlannerBridgeRuleService.LandProbe safeLandProbe = landProbe == null ? (px, pz) -> true : landProbe;
        if (safeLandProbe.isLand(x, z)) {
            return true;
        }
        RoadPlannerWaterDepthProbe safeWaterDepthProbe = waterDepthProbe == null ? (px, pz) -> 0 : waterDepthProbe;
        return safeWaterDepthProbe.waterDepthAt(x, z) <= 0;
    }

    public static boolean requiresBridgeForSpan(BlockPos from,
                                                BlockPos to,
                                                RoadPlannerBridgeRuleService.LandProbe landProbe,
                                                RoadPlannerWaterDepthProbe waterDepthProbe,
                                                RoadPlannerHeightSampler heightSampler) {
        if (from == null || to == null) {
            return false;
        }
        RoadPlannerHeightSampler safeHeightSampler = heightSampler == null ? (x, z) -> from.getY() : heightSampler;
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < MIN_BRIDGE_SPAN_BLOCKS) {
            return false;
        }
        int steps = Math.max(2, (int) Math.ceil(distance / SAMPLE_SPACING_BLOCKS));
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        int previousHeight = Integer.MIN_VALUE;
        int steepTransitions = 0;
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            int x = (int) Math.round(from.getX() + dx * t);
            int z = (int) Math.round(from.getZ() + dz * t);
            if (!isRoadPassable(x, z, landProbe, waterDepthProbe)) {
                return true;
            }
            int height = safeHeightSampler.heightAt(x, z);
            minHeight = Math.min(minHeight, height);
            maxHeight = Math.max(maxHeight, height);
            if (previousHeight != Integer.MIN_VALUE
                    && Math.abs(height - previousHeight) > MAX_ROAD_GRADE_HEIGHT_PER_SAMPLE) {
                steepTransitions++;
            }
            previousHeight = height;
        }
        return steepTransitions > 0 || maxHeight - minHeight > MAX_ROAD_HEIGHT_RANGE;
    }

    public static RoadPlannerSegmentType segmentTypeForConnection(BlockPos from,
                                                                  BlockPos to,
                                                                  RoadPlannerSegmentType fallback,
                                                                  RoadPlannerBridgeRuleService.LandProbe landProbe,
                                                                  RoadPlannerWaterDepthProbe waterDepthProbe,
                                                                  RoadPlannerHeightSampler heightSampler) {
        RoadPlannerSegmentType safeFallback = fallback == null ? RoadPlannerSegmentType.ROAD : fallback;
        if (safeFallback == RoadPlannerSegmentType.BRIDGE_MAJOR
                || safeFallback == RoadPlannerSegmentType.BRIDGE_SMALL
                || safeFallback == RoadPlannerSegmentType.TUNNEL) {
            return safeFallback;
        }
        if (to == null) {
            return safeFallback;
        }
        if (!isRoadPassable(to.getX(), to.getZ(), landProbe, waterDepthProbe)) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        if (from != null && requiresBridgeForSpan(from, to, landProbe, waterDepthProbe, heightSampler)) {
            return RoadPlannerSegmentType.BRIDGE_MAJOR;
        }
        return safeFallback;
    }
}
