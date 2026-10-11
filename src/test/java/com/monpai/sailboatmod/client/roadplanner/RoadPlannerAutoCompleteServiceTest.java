package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

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
    void longFlatLandAutoCompleteStaysRoad() {
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService();

        RoadPlannerAutoCompleteResult result = service.complete(
                new BlockPos(0, 64, 0),
                new BlockPos(160, 64, 0),
                List.of(),
                80
        );

        assertTrue(result.success());
        assertTrue(result.segmentTypes().stream().allMatch(type -> type == RoadPlannerSegmentType.ROAD));
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
    void usesInjectedPathfinderAndResamplesItsRoute() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos destination = new BlockPos(96, 64, 0);
        List<BlockPos> pathfinderNodes = List.of(start, new BlockPos(48, 70, 12), destination);
        AtomicBoolean runnerCalled = new AtomicBoolean();
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService((from, to) -> {
            runnerCalled.set(true);
            assertEquals(start, from);
            assertEquals(destination, to);
            return pathfinderNodes;
        });

        RoadPlannerAutoCompleteResult result = service.complete(
                start,
                destination,
                List.of(),
                24
        );

        assertTrue(runnerCalled.get());
        assertTrue(result.success());
        assertEquals(start, result.nodes().get(0));
        assertEquals(destination, result.nodes().get(result.nodes().size() - 1));
        assertTrue(result.nodes().size() > pathfinderNodes.size(), "the runner route should be resampled at the requested spacing");
        assertTrue(result.nodes().stream().anyMatch(node -> node.getZ() > 0 && node.getY() > start.getY()),
                "the resampled route should retain the runner's bend instead of taking the straight fallback");
        assertEquals(result.nodes().size() - 1, result.segmentTypes().size());
    }

    @Test
    void treeHeightSpikeOnLandDoesNotBecomeBridge() {
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService(
                (from, to) -> List.of(
                        from,
                        new BlockPos(16, 86, 0),
                        to
                ),
                null,
                (x, z) -> true
        );

        RoadPlannerAutoCompleteResult result = service.complete(
                new BlockPos(0, 64, 0),
                new BlockPos(32, 64, 0),
                List.of(),
                8
        );

        assertTrue(result.success());
        assertTrue(result.segmentTypes().stream().allMatch(type -> type == RoadPlannerSegmentType.ROAD));
    }

    @Test
    void smallPitOnLandDoesNotBecomeBridge() {
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService(
                (from, to) -> List.of(
                        from,
                        new BlockPos(16, 52, 0),
                        to
                ),
                null,
                (x, z) -> true
        );

        RoadPlannerAutoCompleteResult result = service.complete(
                new BlockPos(0, 64, 0),
                new BlockPos(32, 64, 0),
                List.of(),
                8
        );

        assertTrue(result.success());
        assertTrue(result.segmentTypes().stream().allMatch(type -> type == RoadPlannerSegmentType.ROAD));
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
                nodes -> IntStream.range(0, nodes.size() - 1)
                        .mapToObj(index -> nodes.get(index).getX() == 0 || nodes.get(index + 1).getX() == 24
                                ? RoadPlannerSegmentType.ROAD
                                : RoadPlannerSegmentType.BRIDGE_MAJOR)
                        .toList(),
                (x, z) -> x == 0 || x == 24
        );

        RoadPlannerAutoCompleteResult result = service.complete(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0), List.of(), 4);

        assertTrue(result.success());
        assertEquals(new BlockPos(0, 64, 0), result.nodes().get(0));
        assertEquals(new BlockPos(24, 64, 0), result.nodes().get(result.nodes().size() - 1));
        assertEquals(result.nodes().size() - 1, result.segmentTypes().size());
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(result.segmentTypes().size() - 1));
        assertTrue(result.segmentTypes().stream().allMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR));
    }

    @Test
    void usesOldMajorBridgeThreshold() {
        assertFalse(RoadPlannerBridgeThresholds.requiresMajorBridge(8));
        assertTrue(RoadPlannerBridgeThresholds.requiresMajorBridge(9));
    }
}
