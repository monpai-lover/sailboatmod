package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.bridge.BridgeBuilder;
import com.monpai.sailboatmod.road.construction.bridge.BridgeRangeDetector;
import com.monpai.sailboatmod.road.model.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class RoadBuilder {
    private final RoadConfig config;
    private final RoadSegmentPaver paver;
    private final StreetlightPlacer streetlightPlacer;
    private final BridgeRangeDetector bridgeDetector;
    private final BridgeBuilder bridgeBuilder;
    private final BiomeMaterialSelector materialSelector;
    private final TunnelDigger tunnelDigger;

    public RoadBuilder(RoadConfig config) {
        this.config = config;
        this.materialSelector = new BiomeMaterialSelector();
        this.paver = new RoadSegmentPaver(materialSelector);
        this.streetlightPlacer = new StreetlightPlacer(config.getAppearance(), materialSelector);
        this.bridgeDetector = new BridgeRangeDetector(config.getBridge());
        this.bridgeBuilder = new BridgeBuilder(config.getBridge());
        this.tunnelDigger = new TunnelDigger(config.getAppearance());
    }

    public RoadData buildRoad(String roadId, List<BlockPos> centerPath, int width,
                               TerrainSamplingCache cache) {
        return buildRoad(roadId, centerPath, width, cache, "auto");
    }

    public RoadData buildRoad(String roadId, List<BlockPos> centerPath, int width,
                              TerrainSamplingCache cache, String materialPreset) {
        List<BridgeSpan> bridgeSpans = bridgeDetector.detect(centerPath, cache);

        List<BuildStep> allSteps = new ArrayList<>();
        int order = 0;
        String normalizedPreset = materialPreset == null ? "auto" : materialPreset;
        List<BlockPos> landPath = filterLandPath(centerPath, bridgeSpans);

        List<BuildStep> landSteps = paver.pave(
            landPath, width, cache, normalizedPreset);
        for (BuildStep step : landSteps) {
            allSteps.add(new BuildStep(order++, step.pos(), step.state(), step.phase()));
        }

        if (config.getAppearance().isTunnelEnabled()) {
            List<BuildStep> tunnelSteps = buildTunnelSteps(landPath, width, cache, order);
            allSteps.addAll(tunnelSteps);
            order += tunnelSteps.size();
        }

        RoadMaterial defaultMaterial = materialSelector.select(
            normalizedPreset, cache.getBiome(centerPath.get(0).getX(), centerPath.get(0).getZ()));
        for (BridgeSpan span : bridgeSpans) {
            List<BuildStep> bridgeSteps = bridgeBuilder.build(
                span, centerPath, width, defaultMaterial, order);
            allSteps.addAll(bridgeSteps);
            order += bridgeSteps.size();
        }

        List<BuildStep> lights = streetlightPlacer.placeLights(
            centerPath, width, bridgeSpans, cache, order, normalizedPreset);
        allSteps.addAll(lights);

        return new RoadData(roadId, width, List.of(), bridgeSpans, defaultMaterial, allSteps, centerPath);
    }

    private List<BuildStep> buildTunnelSteps(List<BlockPos> landPath,
                                             int width,
                                             TerrainSamplingCache cache,
                                             int startOrder) {
        if (landPath.isEmpty()) {
            return List.of();
        }
        List<BuildStep> steps = new ArrayList<>();
        int order = startOrder;
        for (int i = 0; i < landPath.size(); i++) {
            BlockPos center = landPath.get(i);
            if (!tunnelDigger.isMountainInterior(center, 3, cache.getLevel())) {
                continue;
            }
            Direction roadDir = getDirection(landPath, i);
            List<BuildStep> tunnel = tunnelDigger.dig(center, width, roadDir, order);
            steps.addAll(tunnel);
            order += tunnel.size();
            if (i % Math.max(1, config.getAppearance().getTunnelLightInterval()) == 0) {
                List<BuildStep> lightSteps = tunnelDigger.placeTunnelLight(center, roadDir, order);
                steps.addAll(lightSteps);
                order += lightSteps.size();
            }
        }
        return steps;
    }

    private List<BlockPos> filterLandPath(List<BlockPos> path, List<BridgeSpan> bridges) {
        List<BlockPos> land = new ArrayList<>();
        for (int i = 0; i < path.size(); i++) {
            boolean inBridge = false;
            for (BridgeSpan span : bridges) {
                if (i >= span.startIndex() && i <= span.endIndex()) {
                    inBridge = true;
                    break;
                }
            }
            if (!inBridge) land.add(path.get(i));
        }
        return land;
    }

    private Direction getDirection(List<BlockPos> path, int index) {
        BlockPos curr = path.get(index);
        BlockPos next = (index < path.size() - 1) ? path.get(index + 1) : curr;
        BlockPos prev = (index > 0) ? path.get(index - 1) : curr;
        int dx = next.getX() - prev.getX();
        int dz = next.getZ() - prev.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
