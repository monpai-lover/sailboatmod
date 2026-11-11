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

    private static List<BlockPos> gridStaircase() {
        List<BlockPos> path = new java.util.ArrayList<>();
        for (int i = 0; i <= 40; i++) {
            path.add(new BlockPos(i * 8, 64, (i / 2) * 8));
        }
        return path;
    }

    private static double sharpestTurnDegrees(List<BlockPos> path) {
        double sharpest = 0;
        for (int i = 1; i < path.size() - 1; i++) {
            double in = Math.atan2(path.get(i).getZ() - path.get(i - 1).getZ(), path.get(i).getX() - path.get(i - 1).getX());
            double out = Math.atan2(path.get(i + 1).getZ() - path.get(i).getZ(), path.get(i + 1).getX() - path.get(i).getX());
            sharpest = Math.max(sharpest, Math.abs(Math.toDegrees(Math.atan2(Math.sin(out - in), Math.cos(out - in)))));
        }
        return sharpest;
    }

    @Test
    void aSearchedGridPathIsDeliveredWithoutItsDoglegs() {
        List<BlockPos> searched = gridStaircase();
        var flat = new com.monpai.sailboatmod.roadplanner.corridor.RoadRouteRefiner.Terrain() {
            @Override public int height(int x, int z) { return 64; }
            @Override public boolean water(int x, int z) { return false; }
            @Override public boolean blocked(int x, int z) { return false; }
        };
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService((from, to) -> searched).withRouteTerrain(flat);

        RoadPlannerAutoCompleteResult result = service.complete(searched.get(0), searched.get(searched.size() - 1), List.of(), 24);

        assertTrue(result.success());
        assertTrue(sharpestTurnDegrees(searched) >= 45);
        assertTrue(sharpestTurnDegrees(result.nodes()) < 5, "sharpest turn left in the route: " + sharpestTurnDegrees(result.nodes()));
    }

    @Test
    void aRouteIsStillSmoothedWhenTerrainChecksRunOutOfTime() {
        List<BlockPos> searched = gridStaircase();
        var slow = new com.monpai.sailboatmod.roadplanner.corridor.RoadRouteRefiner.Terrain() {
            @Override public int height(int x, int z) {
                long until = System.nanoTime() + 2_000_000L;
                while (System.nanoTime() < until) Thread.onSpinWait();
                return 64;
            }
            @Override public boolean water(int x, int z) { return false; }
            @Override public boolean blocked(int x, int z) { return false; }
        };
        RoadPlannerAutoCompleteService service = new RoadPlannerAutoCompleteService((from, to) -> searched)
                .withRouteTerrain(slow).withRefineBudgetMillis(20);

        RoadPlannerAutoCompleteResult result = service.complete(searched.get(0), searched.get(searched.size() - 1), List.of(), 24);

        assertTrue(result.success(), result.message());
        assertTrue(sharpestTurnDegrees(result.nodes()) < 5);
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
