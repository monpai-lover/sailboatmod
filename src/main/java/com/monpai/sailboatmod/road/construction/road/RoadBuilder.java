package com.monpai.sailboatmod.road.construction.road;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.bridge.BridgeBuilder;
import com.monpai.sailboatmod.road.construction.bridge.BridgeRangeDetector;
import com.monpai.sailboatmod.road.model.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class RoadBuilder {
    private final RoadConfig config;
    private final RoadSegmentPaver paver;
    private final StreetlightPlacer streetlightPlacer;
    private final BridgeRangeDetector bridgeDetector;
    private final BridgeBuilder bridgeBuilder;
    private final BiomeMaterialSelector materialSelector;

    public RoadBuilder(RoadConfig config) {
        this.config = config;
        this.materialSelector = new BiomeMaterialSelector();
        this.paver = new RoadSegmentPaver(materialSelector);
        this.streetlightPlacer = new StreetlightPlacer(config.getAppearance(), materialSelector);
        this.bridgeDetector = new BridgeRangeDetector(config.getBridge());
        this.bridgeBuilder = new BridgeBuilder(config.getBridge());
    }

    public RoadData buildRoad(String roadId, List<BlockPos> centerPath, int width,
                               TerrainSamplingCache cache) {
        List<BridgeSpan> bridgeSpans = bridgeDetector.detect(centerPath, cache);

        List<BuildStep> allSteps = new ArrayList<>();
        int order = 0;

        List<BuildStep> landSteps = paver.pave(
            filterLandPath(centerPath, bridgeSpans), width, cache);
        for (BuildStep step : landSteps) {
            allSteps.add(new BuildStep(order++, step.pos(), step.state(), step.phase()));
        }

        RoadMaterial defaultMaterial = materialSelector.select(
            cache.getBiome(centerPath.get(0).getX(), centerPath.get(0).getZ()));
        for (BridgeSpan span : bridgeSpans) {
            List<BuildStep> bridgeSteps = bridgeBuilder.build(
                span, centerPath, width, defaultMaterial, order);
            allSteps.addAll(bridgeSteps);
            order += bridgeSteps.size();
        }

        List<BuildStep> lights = streetlightPlacer.placeLights(
            centerPath, width, bridgeSpans, cache, order);
        allSteps.addAll(lights);

        return new RoadData(roadId, width, List.of(), bridgeSpans, defaultMaterial, allSteps, centerPath);
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
}
