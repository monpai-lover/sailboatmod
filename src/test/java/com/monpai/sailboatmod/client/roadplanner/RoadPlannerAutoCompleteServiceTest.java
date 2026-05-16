package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerAutoCompleteServiceTest {
    @Test
    void autoCompletesTownToTownNodes() {
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService();

        RoadPlannerAutoCompleteResult result = service.complete(
                new BlockPos(0, 64, 0),
                new BlockPos(96, 64, 0),
                List.of(),
                24
        );

        assertTrue(result.success());
        assertTrue(result.nodes().size() >= 5);
        assertEquals(new BlockPos(0, 64, 0), result.nodes().get(0));
        assertEquals(new BlockPos(96, 64, 0), result.nodes().get(result.nodes().size() - 1));
        assertFalse(result.segmentTypes().isEmpty());
    }

    @Test
    void continuesFromLastManualNode() {
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService();
        BlockPos manualStart = new BlockPos(0, 64, 0);
        BlockPos manualTurn = new BlockPos(32, 65, 16);

        RoadPlannerAutoCompleteResult result = service.complete(
                manualStart,
                new BlockPos(96, 64, 0),
                List.of(manualStart, manualTurn),
                24
        );

        assertTrue(result.success());
        assertEquals(manualStart, result.nodes().get(0));
        assertEquals(manualTurn, result.nodes().get(1));
        assertEquals(new BlockPos(96, 64, 0), result.nodes().get(result.nodes().size() - 1));
        assertEquals(result.nodes().size() - 1, result.segmentTypes().size());
    }

    @Test
    void usesInjectedPathfinderRunnerBeforeFallback() {
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService((from, to) -> List.of(
                from,
                new BlockPos(48, 70, 12),
                to
        ));

        RoadPlannerAutoCompleteResult result = service.complete(
                new BlockPos(0, 64, 0),
                new BlockPos(96, 64, 0),
                List.of(),
                24
        );

        assertTrue(result.success());
        assertEquals(new BlockPos(48, 70, 12), result.nodes().get(1));
        assertEquals(3, result.nodes().size());
    }

    @Test
    void injectedPathfinderRunnerEmptyResultFailsInsteadOfInterpolating() {
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService((from, to) -> List.of());

        RoadPlannerAutoCompleteResult result = service.complete(
                new BlockPos(0, 64, 0),
                new BlockPos(96, 64, 0),
                List.of(),
                24
        );

        assertFalse(result.success());
        assertTrue(result.nodes().isEmpty());
    }

    @Test
    void appliesInjectedBridgeThresholdClassifier() {
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService(
                (from, to) -> List.of(
                        from,
                        new BlockPos(4, 64, 0),
                        new BlockPos(8, 64, 0),
                        new BlockPos(12, 64, 0)
                ),
                nodes -> List.of(
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.BRIDGE_MAJOR
                )
        );

        RoadPlannerAutoCompleteResult result = service.complete(
                new BlockPos(0, 64, 0),
                new BlockPos(12, 64, 0),
                List.of(),
                4
        );

        assertTrue(result.success());
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(1));
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(2));
    }

    @Test
    void normalizesAutoCompleteBridgeRangeToIncludeLandAnchors() {
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService(
                (from, to) -> List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(8, 66, 0),
                        new BlockPos(16, 66, 0),
                        new BlockPos(24, 64, 0)
                ),
                nodes -> List.of(
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.ROAD
                ),
                (x, z) -> x == 0 || x == 24
        );

        RoadPlannerAutoCompleteResult result = service.complete(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0), List.of(), 4);

        assertTrue(result.success());
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(2));
    }

    @Test
    void usesThirtyTwoBlockSmallBridgeThreshold() {
        assertFalse(RoadPlannerBridgeThresholds.requiresMajorBridge(32));
        assertTrue(RoadPlannerBridgeThresholds.requiresMajorBridge(33));
    }

    @Test
    void terrainClassifierKeepsThirtyTwoWaterSamplesSmallBridge() {
        RoadPlannerTerrainSegmentClassifier classifier = new RoadPlannerTerrainSegmentClassifier(
                waterSamples(32),
                new BridgeConfig()
        );

        List<RoadPlannerSegmentType> types = classifier.classify(nodesWithTrailingLand(32));

        assertTrue(types.stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_SMALL));
        assertFalse(types.stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR));
    }

    @Test
    void terrainClassifierPromotesThirtyThreeWaterSamplesToMajorBridge() {
        RoadPlannerTerrainSegmentClassifier classifier = new RoadPlannerTerrainSegmentClassifier(
                waterSamples(33),
                new BridgeConfig()
        );

        List<RoadPlannerSegmentType> types = classifier.classify(nodesWithTrailingLand(33));

        assertTrue(types.stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR));
    }

    private static List<BlockPos> nodesWithTrailingLand(int waterSamples) {
        List<BlockPos> nodes = new ArrayList<>();
        for (int x = 0; x < waterSamples + 3; x++) {
            nodes.add(new BlockPos(x, 64, 0));
        }
        return nodes;
    }

    private static TerrainSamplingCache waterSamples(int waterSamples) {
        return new TerrainSamplingCache(null, PathfindingConfig.SamplingPrecision.NORMAL) {
            @Override
            public int getHeight(int x, int z) {
                return x < waterSamples ? 60 : 64;
            }

            @Override
            public boolean isWater(int x, int z) {
                return x < waterSamples;
            }

            @Override
            public int getWaterSurfaceY(int x, int z) {
                return 64;
            }

            @Override
            public int getOceanFloor(int x, int z) {
                return 60;
            }

            @Override
            public int getWaterDepth(int x, int z) {
                return isWater(x, z) ? 4 : 0;
            }
        };
    }
}
