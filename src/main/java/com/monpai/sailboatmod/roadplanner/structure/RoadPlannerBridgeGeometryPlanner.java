package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadPlannerBridgeGeometryPlanner {
    private static final int SEA_LEVEL = 63;

    private RoadPlannerBridgeGeometryPlanner() {
    }

    public static Plan plan(List<RoadCenterlinePoint> points,
                            RoadSpan span,
                            RoadTerrainSampler terrainSampler,
                            BridgeConfig config) {
        BridgeConfig safeConfig = safeConfig(config);
        if (points == null || points.isEmpty()) {
            return new Plan(List.of(), List.of(), SEA_LEVEL + safeConfig.getDeckHeight(), RoadPlannerBridgeProfile.PIER_BRIDGE);
        }
        RoadTerrainSampler safeSampler = terrainSampler == null ? RoadTerrainSampler.flat(points.get(0).terrainY()) : terrainSampler;
        int waterSurfaceY = bridgeWaterSurfaceY(points, safeSampler);
        int waterY = Math.max(waterSurfaceY, SEA_LEVEL);
        int entryY = Math.max(points.get(0).targetY(), waterY);
        int exitY = Math.max(points.get(points.size() - 1).targetY(), waterY);
        int spanLength = span == null ? points.size() - 1 : span.endIndex() - span.startIndex();
        RoadPlannerBridgeProfile profile = resolveProfile(RoadPlannerBridgeProfile.classify(spanLength), waterY, entryY, exitY);
        int deckY = deckYForProfile(profile, waterY, entryY, exitY, safeConfig);

        List<PlannedPoint> plannedPoints = rampedDeck(points, entryY, exitY, deckY);
        List<Pier> piers = profile.usesPiers() ? piers(points, plannedPoints, deckY, safeSampler, safeConfig) : List.of();
        return new Plan(plannedPoints, piers, deckY, profile);
    }

    private static BridgeConfig safeConfig(BridgeConfig config) {
        return config == null ? new BridgeConfig() : config;
    }

    private static int bridgeWaterSurfaceY(List<RoadCenterlinePoint> points, RoadTerrainSampler sampler) {
        int max = Integer.MIN_VALUE;
        for (RoadCenterlinePoint point : points) {
            max = Math.max(max, sampler.waterSurfaceY(point.pos().getX(), point.pos().getZ()));
        }
        return max == Integer.MIN_VALUE ? SEA_LEVEL : max;
    }

    private static int deckYForActualBridge(int waterY, int entryY, int exitY, BridgeConfig config) {
        int maxRampHeight = config.getDeckHeight();
        int higherShore = Math.max(entryY, exitY);
        int deckY = Math.max(waterY + config.getDeckHeight(), Math.max(entryY + 3, exitY + 3));
        deckY = Math.min(deckY, Math.min(entryY, exitY) + maxRampHeight);
        return Math.max(Math.max(deckY, waterY + config.getDeckHeight()), higherShore);
    }

    private static RoadPlannerBridgeProfile resolveProfile(RoadPlannerBridgeProfile profile,
                                                           int waterY,
                                                           int entryY,
                                                           int exitY) {
        RoadPlannerBridgeProfile safeProfile = profile == null ? RoadPlannerBridgeProfile.PIER_BRIDGE : profile;
        if (safeProfile.usesPiers()) {
            return safeProfile;
        }
        int lowerShore = Math.min(entryY, exitY);
        int higherShore = Math.max(entryY, exitY);
        int requiredDeck = Math.max(waterY + safeProfile.waterClearance(), higherShore + 1);
        int maxLowDeck = lowerShore + safeProfile.maxRiseFromLowerShore();
        return requiredDeck <= maxLowDeck ? safeProfile : RoadPlannerBridgeProfile.PIER_BRIDGE;
    }

    private static int deckYForProfile(RoadPlannerBridgeProfile profile,
                                       int waterY,
                                       int entryY,
                                       int exitY,
                                       BridgeConfig config) {
        RoadPlannerBridgeProfile safeProfile = profile == null ? RoadPlannerBridgeProfile.PIER_BRIDGE : profile;
        if (safeProfile == RoadPlannerBridgeProfile.PIER_BRIDGE) {
            return deckYForActualBridge(waterY, entryY, exitY, config);
        }
        int lowerShore = Math.min(entryY, exitY);
        int higherShore = Math.max(entryY, exitY);
        int clearanceDeck = waterY + safeProfile.waterClearance();
        int shoreDeck = higherShore + 1;
        int maxLowDeck = lowerShore + safeProfile.maxRiseFromLowerShore();
        return Math.max(higherShore, Math.min(Math.max(clearanceDeck, shoreDeck), maxLowDeck));
    }

    private static List<PlannedPoint> rampedDeck(List<RoadCenterlinePoint> points, int entryY, int exitY, int deckY) {
        int total = points.size();
        if (total == 1) {
            int y = Math.max(points.get(0).targetY(), Math.min(deckY, Math.max(entryY, exitY)));
            return List.of(new PlannedPoint(points.get(0).withTargetY(y), BuildPhase.DECK));
        }
        int ascHeight = Math.max(0, deckY - entryY);
        int descHeight = Math.max(0, deckY - exitY);
        int intervals = total - 1;
        int ascLen = rampLength(ascHeight, intervals);
        int descLen = rampLength(descHeight, intervals);
        int deckStart = Math.min(intervals, ascLen);
        int deckEndExclusive = Math.max(deckStart, total - descLen);
        if (deckEndExclusive > total) {
            deckEndExclusive = total;
        }
        if (deckStart >= deckEndExclusive && (ascHeight > 0 || descHeight > 0)) {
            int deckIndex = Math.max(0, Math.min(total - 1, total / 2));
            deckStart = deckIndex;
            deckEndExclusive = deckIndex + 1;
        }
        List<PlannedPoint> result = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            int y;
            BuildPhase phase;
            if (index < deckStart) {
                y = rampY(entryY, deckY, index, Math.max(1, deckStart));
                phase = BuildPhase.RAMP;
            } else if (index >= deckEndExclusive) {
                int local = total - 1 - index;
                y = rampY(exitY, deckY, local, Math.max(1, total - deckEndExclusive));
                phase = BuildPhase.RAMP;
            } else {
                y = deckY;
                phase = BuildPhase.DECK;
            }
            result.add(new PlannedPoint(points.get(index).withTargetY(y), phase));
        }
        return List.copyOf(result);
    }

    private static int rampLength(int height, int availableIntervals) {
        if (availableIntervals <= 0) {
            return 0;
        }
        if (height <= 0) {
            return 0;
        }
        return Math.min(height * 2, availableIntervals);
    }

    private static int rampY(int shoreY, int deckY, int localIndex, int rampLen) {
        if (deckY <= shoreY || rampLen <= 0) {
            return shoreY;
        }
        int height = deckY - shoreY;
        int y = shoreY + Math.round((float) localIndex * height / rampLen);
        return Math.min(y, deckY);
    }

    private static List<Pier> piers(List<RoadCenterlinePoint> sourcePoints,
                                    List<PlannedPoint> plannedPoints,
                                    int deckY,
                                    RoadTerrainSampler sampler,
                                    BridgeConfig config) {
        int interval = Math.max(5, config.getPierInterval());
        List<Pier> result = new ArrayList<>();
        for (int index = 0; index < plannedPoints.size(); index += interval) {
            addPier(result, sourcePoints.get(index), deckY, sampler);
        }
        if (!plannedPoints.isEmpty()) {
            addPier(result, sourcePoints.get(sourcePoints.size() - 1), deckY, sampler);
        }
        return List.copyOf(result);
    }

    private static void addPier(List<Pier> piers, RoadCenterlinePoint sourcePoint, int deckY, RoadTerrainSampler sampler) {
        int x = sourcePoint.pos().getX();
        int z = sourcePoint.pos().getZ();
        int bottomY = sampler.oceanFloorY(x, z);
        BlockPos center = new BlockPos(x, deckY, z);
        Pier pier = new Pier(center, bottomY, deckY);
        if (piers.stream().noneMatch(existing -> existing.center().getX() == x && existing.center().getZ() == z)) {
            piers.add(pier);
        }
    }

    public record PlannedPoint(RoadCenterlinePoint point, BuildPhase phase) {
    }

    public record Pier(BlockPos center, int bottomY, int topY) {
    }

    public record Plan(List<PlannedPoint> points, List<Pier> piers, int deckY, RoadPlannerBridgeProfile profile) {
        public Plan {
            points = points == null ? List.of() : List.copyOf(points);
            piers = piers == null ? List.of() : List.copyOf(piers);
            profile = profile == null ? RoadPlannerBridgeProfile.PIER_BRIDGE : profile;
        }
    }
}
