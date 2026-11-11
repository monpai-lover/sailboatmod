package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.structure.RoadCenterlinePoint;
import com.monpai.sailboatmod.roadplanner.structure.RoadSpanType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.WallBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadCorridorCompilerTest {
    private static final List<BlockPos> WINDING = List.of(
            new BlockPos(20, 0, -90), new BlockPos(60, 0, -60), new BlockPos(60, 0, -30),
            new BlockPos(110, 0, 30), new BlockPos(170, 0, 40), new BlockPos(215, 0, 85),
            new BlockPos(300, 0, 85), new BlockPos(340, 0, 40));
    private static final List<RoadPlannerSegmentType> ALL_ROAD = List.of(
            RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD,
            RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD,
            RoadPlannerSegmentType.ROAD);

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static RoadCorridorCompiler.Result build(SyntheticRoadWorld world, List<BlockPos> nodes,
                                                     List<RoadPlannerSegmentType> types, RoadPlannerBuildSettings settings) {
        RoadCorridorCompiler.Result result = RoadCorridorCompiler.compile(RoadAlignment.of(nodes, types), settings, world);
        world.apply(result.steps());
        return result;
    }

    /** Columns carrying a running surface, mapped to the Y of that surface block. */
    private static Map<Long, Integer> surface(List<BuildStep> steps) {
        Map<Long, Integer> surface = new HashMap<>();
        for (BuildStep step : steps) {
            if (step.state().isAir()) {
                continue;
            }
            if (step.phase() == BuildPhase.SURFACE || step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK) {
                surface.merge(column(step.pos().getX(), step.pos().getZ()), step.pos().getY(), Math::max);
            }
        }
        return surface;
    }

    private static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @Test
    void theRunningSurfaceHasNoGapsAnywhereAlongAWindingRoute() {
        for (int width : new int[]{3, 5, 7}) {
            SyntheticRoadWorld world = SyntheticRoadWorld.hillsRiverAndRavine();
            RoadCorridorCompiler.Result result = build(world, WINDING, ALL_ROAD, new RoadPlannerBuildSettings(width, "smooth_stone", false));
            Map<Long, Integer> surface = surface(result.steps());
            int reach = width / 2;
            // The road is cut square at both ends, so the few points nearest them have nothing behind them.
            List<RoadCenterlinePoint> inner = result.centerline().subList(4, result.centerline().size() - 4);
            for (RoadCenterlinePoint point : inner) {
                for (int dx = -reach; dx <= reach; dx++) {
                    for (int dz = -reach; dz <= reach; dz++) {
                        if (Math.hypot(dx, dz) > width / 2.0D - 0.8D) {
                            continue;
                        }
                        assertTrue(surface.containsKey(column(point.pos().getX() + dx, point.pos().getZ() + dz)),
                                "width " + width + ": hole in the road beside " + point.pos() + " at offset " + dx + "," + dz);
                    }
                }
            }
        }
    }

    @Test
    void neighbouringSurfaceBlocksNeverDifferByMoreThanHalfABlock() {
        SyntheticRoadWorld world = SyntheticRoadWorld.hillsRiverAndRavine();
        RoadCorridorCompiler.Result result = build(world, WINDING, ALL_ROAD, RoadPlannerBuildSettings.DEFAULTS);
        Map<Long, Integer> surface = surface(result.steps());
        int worst = 0;
        for (Map.Entry<Long, Integer> entry : surface.entrySet()) {
            int x = (int) (entry.getKey() >> 32);
            int z = (int) (long) entry.getKey();
            int here = world.walkableHalves(x, z, entry.getValue());
            for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                Integer other = surface.get(column(x + d[0], z + d[1]));
                if (other != null) {
                    worst = Math.max(worst, Math.abs(here - world.walkableHalves(x + d[0], z + d[1], other)));
                }
            }
        }
        assertTrue(worst <= 1, "the road must be walkable without jumping; largest step was " + worst / 2.0 + " blocks");
    }

    @Test
    void theRoadIsClearOverheadAndSolidUnderfoot() {
        SyntheticRoadWorld world = SyntheticRoadWorld.hillsRiverAndRavine();
        RoadCorridorCompiler.Result result = build(world, WINDING, ALL_ROAD, RoadPlannerBuildSettings.DEFAULTS);
        for (Map.Entry<Long, Integer> entry : surface(result.steps()).entrySet()) {
            int x = (int) (entry.getKey() >> 32);
            int z = (int) (long) entry.getKey();
            int y = entry.getValue();
            for (int up = 1; up <= 3; up++) {
                assertTrue(world.block(x, y + up, z).isAir(), "headroom blocked above " + x + "," + (y + up) + "," + z);
            }
            assertFalse(world.block(x, y - 1, z).isAir(), "surface floats on air at " + x + "," + y + "," + z);
        }
    }

    @Test
    void waterIsCrossedOnADeckThatStaysDryAndStandsOnPiers() {
        SyntheticRoadWorld world = SyntheticRoadWorld.hillsRiverAndRavine();
        RoadCorridorCompiler.Result result = build(world, WINDING, ALL_ROAD, RoadPlannerBuildSettings.DEFAULTS);
        int wetCentres = 0;
        for (RoadCenterlinePoint point : result.centerline()) {
            int x = point.pos().getX();
            int z = point.pos().getZ();
            if (world.solidTop(x, z) >= SyntheticRoadWorld.SEA) {
                continue;
            }
            wetCentres++;
            assertTrue(world.walkableHalves(x, z, 120) >= (SyntheticRoadWorld.SEA + 1) * 2,
                    "the deck must stand clear of the water at " + x + "," + z);
        }
        assertTrue(wetCentres > 20, "the route must actually cross water");
        assertTrue(result.spans().stream().anyMatch(span -> span.type() == RoadSpanType.BRIDGE));
        // A pier shaft is a tall stack of masonry; the short stacks beside it are the arch springing from it.
        Map<Long, List<Integer>> masonry = new HashMap<>();
        for (BuildStep step : result.steps()) {
            if (step.phase() == BuildPhase.PIER) {
                masonry.computeIfAbsent(column(step.pos().getX(), step.pos().getZ()), key -> new java.util.ArrayList<>()).add(step.pos().getY());
            }
        }
        int shafts = 0;
        for (Map.Entry<Long, List<Integer>> entry : masonry.entrySet()) {
            if (entry.getValue().size() < 6) {
                continue;
            }
            shafts++;
            int x = (int) (entry.getKey() >> 32);
            int z = (int) (long) entry.getKey();
            int lowest = entry.getValue().stream().mapToInt(Integer::intValue).min().orElseThrow();
            assertEquals(world.solidTop(x, z), lowest, "a pier must stand on the river bed at " + x + "," + z);
        }
        assertTrue(shafts > 0, "a river crossing needs at least one pier");
    }

    @Test
    void everyBridgeHasAParapetOnBothSides() {
        SyntheticRoadWorld world = SyntheticRoadWorld.hillsRiverAndRavine();
        RoadCorridorCompiler.Result result = build(world, WINDING, ALL_ROAD, RoadPlannerBuildSettings.DEFAULTS);
        Set<Long> walls = new HashSet<>();
        for (BuildStep step : result.steps()) {
            if (step.state().getBlock() instanceof WallBlock) {
                walls.add(column(step.pos().getX(), step.pos().getZ()));
            }
        }
        Map<Long, Integer> surface = surface(result.steps());
        for (BuildStep step : result.steps()) {
            if (step.phase() != BuildPhase.DECK) {
                continue;
            }
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int x = step.pos().getX() + d[0];
                int z = step.pos().getZ() + d[1];
                long beside = column(x, z);
                // Where a bridge lands, the bank itself closes the edge.
                boolean bank = world.walkableHalves(x, z, step.pos().getY() + 1) >= step.pos().getY() * 2;
                assertTrue(surface.containsKey(beside) || walls.contains(beside) || bank,
                        "open edge on a bridge deck beside " + step.pos());
            }
        }
    }

    @Test
    void aNarrowRavineIsSpannedLevelInsteadOfTheRoadDivingIntoIt() {
        SyntheticRoadWorld world = new SyntheticRoadWorld((x, z) -> Math.abs(x - 100) < 7 ? 70 : 90);
        RoadCorridorCompiler.Result result = build(world, List.of(new BlockPos(0, 0, 0), new BlockPos(200, 0, 0)),
                List.of(RoadPlannerSegmentType.ROAD), RoadPlannerBuildSettings.DEFAULTS);
        for (RoadCenterlinePoint point : result.centerline()) {
            assertEquals(90, point.targetY(), "the road should cross at rim height at x=" + point.pos().getX());
        }
        assertTrue(result.steps().stream().anyMatch(step -> step.phase() == BuildPhase.PIER && step.pos().getY() == 70),
                "a pier should reach the ravine floor");
    }

    @Test
    void aLoneBumpIsCutThroughInsteadOfClimbed() {
        SyntheticRoadWorld world = new SyntheticRoadWorld((x, z) -> Math.abs(x - 100) < 3 ? 76 : 70);
        RoadCorridorCompiler.Result result = build(world, List.of(new BlockPos(0, 0, 0), new BlockPos(200, 0, 0)),
                List.of(RoadPlannerSegmentType.ROAD), RoadPlannerBuildSettings.DEFAULTS);
        for (RoadCenterlinePoint point : result.centerline()) {
            assertEquals(70, point.targetY());
        }
        for (int y = 70; y < 76; y++) {
            assertTrue(world.block(100, y, 0).isAir(), "the bump must be cut away to the sky, not left as a roof, at y=" + y);
        }
    }

    @Test
    void aLongSlopeIsClimbedAtNoMoreThanTheGradeLimit() {
        // A 20 block face at 45 degrees: three times steeper than a road may be.
        SyntheticRoadWorld world = new SyntheticRoadWorld((x, z) -> x < 100 ? 70 : x < 120 ? 70 + (x - 100) : 90);
        RoadCorridorCompiler.Result result = build(world, List.of(new BlockPos(0, 0, 0), new BlockPos(260, 0, 0)),
                List.of(RoadPlannerSegmentType.ROAD), RoadPlannerBuildSettings.DEFAULTS);
        List<RoadCenterlinePoint> line = result.centerline();
        for (int i = 12; i < line.size(); i++) {
            int rise = Math.abs(world.walkableHalves(line.get(i).pos().getX(), 0, 130)
                    - world.walkableHalves(line.get(i - 12).pos().getX(), 0, 130));
            assertTrue(rise / 2.0D <= 12 * RoadCorridorProfile.MAX_GRADE + 0.5D,
                    "too steep near x=" + line.get(i).pos().getX() + ": " + rise / 2.0D + " blocks in 12");
        }
    }

    @Test
    void lampsStandBesideTheRoadNeverOnIt() {
        SyntheticRoadWorld world = SyntheticRoadWorld.hillsRiverAndRavine();
        RoadCorridorCompiler.Result result = build(world, WINDING, ALL_ROAD, RoadPlannerBuildSettings.DEFAULTS);
        Map<Long, Integer> surface = surface(result.steps());
        long lamps = 0;
        for (BuildStep step : result.steps()) {
            if (step.state().getBlock() instanceof LanternBlock) {
                lamps++;
                assertFalse(surface.containsKey(column(step.pos().getX(), step.pos().getZ())), "lamp on the carriageway at " + step.pos());
                assertFalse(world.block(step.pos().getX(), step.pos().getY() - 1, step.pos().getZ()).isAir(), "lantern has no post");
            }
        }
        assertTrue(lamps >= 10, "a 500 block road should be lit along its length");
        RoadCorridorCompiler.Result dark = RoadCorridorCompiler.compile(RoadAlignment.of(WINDING, ALL_ROAD),
                new RoadPlannerBuildSettings(5, "smooth_stone", false), SyntheticRoadWorld.hillsRiverAndRavine());
        assertTrue(dark.steps().stream().noneMatch(step -> step.state().getBlock() instanceof LanternBlock));
    }

    @Test
    void aRoadEndsSquareAtItsFirstAndLastNode() {
        SyntheticRoadWorld world = SyntheticRoadWorld.flat(70);
        RoadCorridorCompiler.Result result = build(world, List.of(new BlockPos(0, 0, 0), new BlockPos(40, 0, 0)),
                List.of(RoadPlannerSegmentType.ROAD), new RoadPlannerBuildSettings(5, "smooth_stone", false));
        Map<Long, Integer> surface = surface(result.steps());
        assertEquals(41 * 5, surface.size());
        for (long key : surface.keySet()) {
            int x = (int) (key >> 32);
            assertTrue(x >= 0 && x <= 40, "paving beyond the end of the road at x=" + x);
        }
        assertEquals(new BlockPos(0, 70, 0), result.centerline().get(0).pos());
        assertEquals(new BlockPos(40, 70, 0), result.centerline().get(result.centerline().size() - 1).pos());
    }
}
