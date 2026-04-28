package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.construction.bridge.BridgeRangeDetector;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class RoadPlannerTerrainSegmentClassifier implements RoadPlannerAutoCompleteService.SegmentClassifier {
    private final TerrainSamplingCache cache;
    private final BridgeRangeDetector bridgeDetector;

    public RoadPlannerTerrainSegmentClassifier(TerrainSamplingCache cache, BridgeConfig bridgeConfig) {
        this.cache = cache;
        this.bridgeDetector = new BridgeRangeDetector(bridgeConfig == null ? new BridgeConfig() : bridgeConfig);
    }

    @Override
    public List<RoadPlannerSegmentType> classify(List<BlockPos> nodes) {
        if (nodes == null || nodes.size() < 2 || cache == null) {
            return List.of();
        }
        List<RoadPlannerSegmentType> types = new ArrayList<>();
        for (int index = 1; index < nodes.size(); index++) {
            types.add(RoadPlannerSegmentType.ROAD);
        }

        List<BridgeSpan> spans = bridgeDetector.detect(nodes, cache);
        for (BridgeSpan span : spans) {
            RoadPlannerSegmentType bridgeType = RoadPlannerBridgeThresholds.requiresMajorBridge(span.length())
                    ? RoadPlannerSegmentType.BRIDGE_MAJOR
                    : RoadPlannerSegmentType.BRIDGE_SMALL;
            int start = Math.max(0, span.startIndex());
            int end = Math.min(types.size() - 1, Math.max(start, span.endIndex() - 1));
            for (int segmentIndex = start; segmentIndex <= end; segmentIndex++) {
                types.set(segmentIndex, bridgeType);
            }
        }
        return types;
    }
}
