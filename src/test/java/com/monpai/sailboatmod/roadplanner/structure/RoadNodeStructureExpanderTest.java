package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadNodeStructureExpanderTest {
    private static final RoadTerrainSampler SEA = new RoadTerrainSampler() {
        @Override public int terrainY(int x, int z) { return x < 20 || x > 80 ? 66 : 63; }
        @Override public int oceanFloorY(int x, int z) { return x < 20 || x > 80 ? 66 : 50; }
        @Override public boolean isWater(int x, int y, int z) { return x >= 20 && x <= 80 && y >= 50 && y < 63; }
    };

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RoadNodeExpansionResult expand(List<BlockPos> nodes, List<RoadPlannerSegmentType> types,
                                                  RoadPlannerBuildSettings settings, RoadTerrainSampler terrain) {
        return RoadNodeStructureExpander.expand(nodes, types, settings, terrain, RoadStructureMode.PREVIEW);
    }

    @Test
    void normalizesMissingSegmentsAndSkipsDuplicateSegmentTypes() {
        RoadNodeExpansionResult result = expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), new BlockPos(16, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS, RoadTerrainSampler.flat(64));

        assertFalse(result.hasErrors());
        assertEquals(List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), new BlockPos(16, 64, 0)), result.canonicalNodes());
        assertEquals(List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD), result.canonicalSegmentTypes());
    }

    @Test
    void fewerThanTwoUsableNodesIsReportedNotBuilt() {
        RoadNodeExpansionResult result = expand(List.of(new BlockPos(0, 64, 0)), List.of(),
                RoadPlannerBuildSettings.DEFAULTS, RoadTerrainSampler.flat(64));

        assertTrue(result.hasErrors());
        assertTrue(result.buildSteps().isEmpty());
    }

    @Test
    void aLevelRoadReplacesTheTopLayerOfGroundAndFollowsTheChosenWidthAndMaterial() {
        RoadNodeExpansionResult result = expand(List.of(new BlockPos(0, 64, 0), new BlockPos(30, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                new RoadPlannerBuildSettings(7, "stone_bricks", false), RoadTerrainSampler.flat(64));

        List<BuildStep> surface = result.buildSteps().stream().filter(step -> step.phase() == BuildPhase.SURFACE).toList();
        assertEquals(31 * 7, surface.size());
        for (BuildStep step : surface) {
            assertEquals(63, step.pos().getY(), "a road on level ground lies flush with it");
            assertTrue(step.state().is(Blocks.STONE_BRICKS));
            assertTrue(Math.abs(step.pos().getZ()) <= 3);
        }
        assertEquals(64, result.centerline().get(0).targetY());
    }

    @Test
    void everyPositionIsWrittenOnceAndStepsAreNumberedInBuildOrder() {
        RoadNodeExpansionResult result = expand(
                List.of(new BlockPos(0, 66, 0), new BlockPos(50, 66, 0), new BlockPos(100, 66, 30)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS, SEA);

        Set<BlockPos> seen = new HashSet<>();
        for (int index = 0; index < result.buildSteps().size(); index++) {
            BuildStep step = result.buildSteps().get(index);
            assertEquals(index, step.order());
            assertTrue(seen.add(step.pos()), "two steps write " + step.pos());
        }
    }

    @Test
    void previewShowsTheStructureButNotEarthworksOrClearing() {
        RoadNodeExpansionResult result = expand(List.of(new BlockPos(0, 66, 0), new BlockPos(100, 66, 0)),
                List.of(RoadPlannerSegmentType.ROAD), RoadPlannerBuildSettings.DEFAULTS, SEA);

        assertFalse(result.previewBlocks().isEmpty());
        for (RoadPreviewBlock block : result.previewBlocks()) {
            assertFalse(block.state().isAir());
            assertTrue(block.phase() != BuildPhase.FOUNDATION);
        }
        long visibleSteps = result.buildSteps().stream()
                .filter(step -> !step.state().isAir() && step.phase() != BuildPhase.FOUNDATION).count();
        assertEquals(visibleSteps, result.previewBlocks().size());
    }

    @Test
    void waterUnderAPlainRoadIsBridgedAndReportedAsABridgeSpan() {
        RoadNodeExpansionResult result = expand(List.of(new BlockPos(0, 66, 0), new BlockPos(100, 66, 0)),
                List.of(RoadPlannerSegmentType.ROAD), RoadPlannerBuildSettings.DEFAULTS, SEA);

        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == BuildPhase.PIER && step.pos().getY() == 50),
                "piers stand on the sea bed");
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == BuildPhase.RAILING));
        assertEquals(List.of(RoadSpanType.ROAD, RoadSpanType.BRIDGE, RoadSpanType.ROAD),
                result.spans().stream().map(RoadSpan::type).toList());
        RoadSpan bridge = result.spans().get(1);
        assertTrue(result.centerline().get(bridge.startIndex()).pos().getX() <= 22);
        assertTrue(result.centerline().get(bridge.endIndex()).pos().getX() >= 78);
    }

    private static RoadTerrainSampler river(int from, int to) {
        return new RoadTerrainSampler() {
            @Override public int terrainY(int x, int z) { return x < from || x > to ? 65 : 63; }
            @Override public int oceanFloorY(int x, int z) { return x < from || x > to ? 65 : 55; }
            @Override public boolean isWater(int x, int y, int z) { return x >= from && x <= to && y >= 55 && y < 63; }
        };
    }

    private static RoadNodeExpansionResult crossing(RoadPlannerSegmentType type, RoadTerrainSampler river) {
        return expand(List.of(new BlockPos(0, 65, 0), new BlockPos(200, 65, 0)), List.of(type),
                new RoadPlannerBuildSettings(5, "smooth_stone", false), river);
    }

    private static int surfaceHeightAt(RoadNodeExpansionResult result, int x) {
        return result.centerline().stream().filter(point -> point.pos().getX() == x).findFirst().orElseThrow().targetY();
    }

    @Test
    void aSmallRiverIsCrossedLowWithoutRaisingTheRoad() {
        RoadNodeExpansionResult result = crossing(RoadPlannerSegmentType.ROAD, river(95, 105));

        for (RoadCenterlinePoint point : result.centerline()) {
            assertTrue(point.targetY() >= 65 && point.targetY() <= 66,
                    "a stream does not warrant a hump; height at x=" + point.pos().getX() + " was " + point.targetY());
        }
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
    }

    @Test
    void wideWaterIsCrossedHighEnoughForBoatsToPassBeneath() {
        RoadNodeExpansionResult result = crossing(RoadPlannerSegmentType.ROAD, river(60, 140));

        // Piers stand every 16 blocks; x=104 is midway between two of them.
        int lowestStructure = result.buildSteps().stream()
                .filter(step -> !step.state().isAir() && step.pos().getX() == 104 && step.pos().getZ() == 0)
                .mapToInt(step -> step.pos().getY()).min().orElseThrow();
        assertTrue(lowestStructure - 63 >= 5, "boats need more than five clear blocks; found " + (lowestStructure - 63));
        for (int x = 64; x <= 136; x++) {
            int column = x;
            int lowest = result.buildSteps().stream()
                    .filter(step -> !step.state().isAir() && step.phase() != BuildPhase.PIER
                            && step.pos().getX() == column && step.pos().getZ() == 0)
                    .mapToInt(step -> step.pos().getY()).min().orElseThrow();
            assertTrue(lowest - 63 >= 6, "the deck must hold its height across the channel, x=" + x);
        }
        assertEquals(65, surfaceHeightAt(result, 10), "the road is back on the ground well before the far bank");
        assertEquals(65, surfaceHeightAt(result, 190));
    }

    @Test
    void theClimbToAHighBridgeStaysWithinTheGradeLimit() {
        RoadNodeExpansionResult result = crossing(RoadPlannerSegmentType.ROAD, river(60, 140));

        List<RoadCenterlinePoint> line = result.centerline();
        for (int i = 9; i < line.size(); i++) {
            assertTrue(Math.abs(line.get(i).targetY() - line.get(i - 9).targetY()) <= 4,
                    "ramp too steep near x=" + line.get(i).pos().getX());
        }
    }

    @Test
    void aLegMarkedAsAMajorBridgeIsRaisedForBoatsEvenOverNarrowWater() {
        RoadTerrainSampler narrow = river(92, 108);
        int plain = surfaceHeightAt(crossing(RoadPlannerSegmentType.ROAD, narrow), 100);
        int small = surfaceHeightAt(crossing(RoadPlannerSegmentType.BRIDGE_SMALL, narrow), 100);
        int major = surfaceHeightAt(crossing(RoadPlannerSegmentType.BRIDGE_MAJOR, narrow), 100);
        int blocked = surfaceHeightAt(crossing(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE, narrow), 100);

        assertTrue(plain <= 66 && small <= 67, "unmarked and small bridges stay low: " + plain + ", " + small);
        assertEquals(63 + 8, major);
        assertEquals(major, blocked, "a leg the planner flagged as needing a bridge is built as a major one");
    }

    @Test
    void aMarkedBridgeFliesLevelBetweenItsEndNodesOverAValleyTooWideToSpanAutomatically() {
        RoadTerrainSampler valley = (x, z) -> x < 40 || x > 160 ? 90 : 70;
        List<BlockPos> nodes = List.of(new BlockPos(0, 90, 0), new BlockPos(40, 90, 0), new BlockPos(160, 90, 0), new BlockPos(200, 90, 0));

        RoadNodeExpansionResult bridged = expand(nodes,
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS, valley);
        RoadNodeExpansionResult plain = expand(nodes,
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS, valley);

        for (RoadCenterlinePoint point : bridged.centerline()) {
            assertEquals(90, point.targetY(), "the viaduct holds rim height at x=" + point.pos().getX());
        }
        assertTrue(bridged.buildSteps().stream().anyMatch(step -> step.phase() == BuildPhase.PIER && step.pos().getY() == 70),
                "viaduct piers reach the valley floor");
        assertTrue(plain.centerline().stream().anyMatch(point -> point.targetY() <= 72),
                "an unmarked road descends into a valley this wide instead");
    }

    @Test
    void aNullNodeSplitsTheRouteIntoSeparateRoads() {
        RoadNodeExpansionResult result = expand(
                Arrays.asList(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0), null, new BlockPos(40, 64, 0), new BlockPos(50, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD),
                new RoadPlannerBuildSettings(3, "smooth_stone", false), RoadTerrainSampler.flat(64));

        Set<Integer> paved = new HashSet<>();
        result.buildSteps().stream().filter(step -> step.phase() == BuildPhase.SURFACE).forEach(step -> paved.add(step.pos().getX()));
        assertTrue(paved.contains(0) && paved.contains(10) && paved.contains(40) && paved.contains(50));
        for (int x = 11; x < 40; x++) {
            assertFalse(paved.contains(x), "nothing may be built across the gap at x=" + x);
        }
        assertEquals(22, result.centerline().size());
    }

    @Test
    void withoutTerrainTheNodeHeightsStandInForTheGround() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 70, 0), new BlockPos(20, 70, 0)), List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS, (RoadTerrainSampler) null, RoadStructureMode.BUILD);

        assertTrue(result.buildSteps().stream().filter(step -> step.phase() == BuildPhase.SURFACE)
                .allMatch(step -> step.pos().getY() == 69));
    }
}
