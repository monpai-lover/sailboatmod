package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

class GlobalWaterGridPlannerTest {
    /**
     * A bounded sea split by a wall at x in [8_000, 10_000] with one 7-block strait at z in [3_012, 3_018]:
     * 32-block cell centres (2_992, 3_024) miss it, 16-block centres (3_016) fall inside.
     */
    private static final BiPredicate<Integer, Integer> STRAIT_SEA = (x, z) -> x >= -500 && x <= 22_000
            && z >= -3_000 && z <= 3_100 && (x < 8_000 || x > 10_000 || (z >= 3_012 && z <= 3_018));

    private static WaterRouteWorld world(BiPredicate<Integer, Integer> water, int[] samples) {
        return new WaterRouteWorld() {
            @Override public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
                samples[0]++;
                return water.test(x, z) ? WaterColumn.passable(new BlockPos(x, 63, z), 0) : WaterColumn.blocked();
            }
            @Override public boolean canLoadMoreChunks(int requested) { return false; }
            @Override public int consumedChunkLoads() { return 0; }
        };
    }

    @Test
    void openOceanBecomesAFewDirectLegsAndOnlyTheEndpointsAreDetailed() {
        BlockPos start = new BlockPos(5, 63, 5), goal = new BlockPos(200_003, 63, 30_001);
        GlobalWaterGridPlanner.Guidance g = GlobalWaterGridPlanner.plan(world((x, z) -> true, new int[1]), start, goal, 63);
        assertTrue(g.successful());
        assertEquals(start, g.legs().getFirst().from());
        assertEquals(goal, g.legs().getLast().to());
        assertTrue(g.legs().stream().filter(GlobalWaterGridPlanner.Leg::direct).count() <= 2, "open water is pulled straight");
        assertTrue(g.legs().size() <= 4);
        assertContinuous(g.legs());
    }

    @Test
    void routesAroundAContinentThroughANarrowStraitAtAFinerCell() {
        BiPredicate<Integer, Integer> water = STRAIT_SEA;
        BlockPos start = new BlockPos(0, 63, 0), goal = new BlockPos(21_000, 63, 0);
        GlobalWaterGridPlanner.Guidance g = GlobalWaterGridPlanner.plan(world(water, new int[1]), start, goal, 63, 32);
        assertTrue(g.successful(), "reason=" + g.failure());
        assertEquals(16, g.cellSize(), "the 32-block grid misses the strait, the 16-block grid finds it");
        assertContinuous(g.legs());
        for (GlobalWaterGridPlanner.Leg leg : g.legs()) {
            if (!leg.direct()) continue;
            int n = (int) Math.ceil(Math.sqrt(leg.from().distSqr(leg.to())));
            for (int s = 0; s <= n; s++) {
                double t = n == 0 ? 0 : s / (double) n;
                int x = (int) Math.round(leg.from().getX() + (leg.to().getX() - leg.from().getX()) * t);
                int z = (int) Math.round(leg.from().getZ() + (leg.to().getZ() - leg.from().getZ()) * t);
                assertTrue(water.test(x, z), "direct leg crosses land at " + x + "," + z);
            }
        }
        assertTrue(g.legs().stream().anyMatch(l -> !l.direct() && l.from().getX() < 10_100 && l.to().getX() > 7_900),
                "the 7-block strait is too tight for a direct leg and goes to the detailed search");
    }

    @Test
    void aNavigableStraitBetweenSixteenBlockCentresRetriesWithoutChangingTheInitialGrid() {
        // Like the Bosphorus on Tellus 1:100: nine water blocks wide, but neither
        // 16-block centre (8 or 24) lies in the channel. The 8-block centre at 12 does.
        BiPredicate<Integer, Integer> water = (x, z) -> x >= -80 && x <= 2_080 && z >= -80 && z <= 80
                && (x < 800 || x > 1_000 || (z >= 10 && z <= 18));
        BlockPos a = new BlockPos(0, 63, 0), b = new BlockPos(2_000, 63, 0);
        assertEquals(16, GlobalWaterGridPlanner.initialCell(a, b), "ordinary routes retain their original grid");
        for (List<BlockPos> endpoints : List.of(List.of(a, b), List.of(b, a))) {
            GlobalWaterGridPlanner.Guidance g = GlobalWaterGridPlanner.plan(world(water, new int[1]),
                    endpoints.getFirst(), endpoints.getLast(), 63);
            assertTrue(g.successful(), "a water-connected strait must not be rejected: " + g.failure());
            assertEquals(8, g.cellSize());
            assertEquals(endpoints.getFirst(), g.legs().getFirst().from());
            assertEquals(endpoints.getLast(), g.legs().getLast().to());
            assertContinuous(g.legs());
            assertTrue(g.legs().stream().anyMatch(l -> !l.direct() && Math.min(l.from().getX(), l.to().getX()) < 1_001
                    && Math.max(l.from().getX(), l.to().getX()) > 799), "the narrow channel still requires detailed verification");
        }
    }

    @Test
    void stillNarrowerWaterwaysCanRefineBelowTheEightBlockGrid() {
        BiPredicate<Integer, Integer> water = (x, z) -> x >= -80 && x <= 2_080 && z >= -80 && z <= 80
                && (x < 800 || x > 1_000 || (z >= 0 && z <= 3));
        for (boolean reverse : new boolean[]{false, true}) {
            BlockPos start = new BlockPos(reverse ? 2_000 : 0, 63, 20);
            BlockPos goal = new BlockPos(reverse ? 0 : 2_000, 63, 20);
            GlobalWaterGridPlanner.Guidance g = GlobalWaterGridPlanner.plan(world(water, new int[1]), start, goal, 63);
            assertTrue(g.successful(), "a four-block-wide waterway fits the boat: " + g.failure());
            assertEquals(4, g.cellSize());
            assertContinuous(g.legs());
        }
    }

    @Test
    void unavailableTerrainDoesNotMasqueradeAsADisconnectedSea() {
        WaterRouteWorld missing = new WaterRouteWorld() {
            @Override public WaterColumn sample(int x, int z, WaterRoutePolicy policy) { return null; }
            @Override public boolean canLoadMoreChunks(int requested) { return false; }
            @Override public int consumedChunkLoads() { return 0; }
        };
        GlobalWaterGridPlanner.Guidance result = GlobalWaterGridPlanner.plan(missing,
                new BlockPos(0, 63, 0), new BlockPos(2_000, 63, 0), 63);
        assertEquals(WaterRouteFailureReason.TERRAIN_NOT_READY, result.failure());
    }

    @Test
    void nativeFrontiersCannotMeetThroughTwoDryCorners() throws Exception {
        // The third cell keeps the forward frontier alive until the reverse
        // frontier can attempt its fast join to the already-closed source.
        WaterRouteWorld water = world((x, z) -> (z == 0 && (x == 0 || x == -1)) || (x == 1 && z == 1), new int[1]);
        var constructor = GlobalWaterGridPlanner.class.getDeclaredConstructor(
                WaterRouteWorld.class, GlobalWaterGridPlanner.SeaDistanceSource.class, int.class);
        constructor.setAccessible(true);
        GlobalWaterGridPlanner planner = constructor.newInstance(water, null, 63);
        Class<?> gridType = Class.forName(GlobalWaterGridPlanner.class.getName() + "$Grid");
        var gridConstructor = gridType.getDeclaredConstructor(GlobalWaterGridPlanner.class, int.class, int.class);
        gridConstructor.setAccessible(true);
        Object grid = gridConstructor.newInstance(planner, 1, 1_000);
        var search = gridType.getDeclaredMethod("search", BlockPos.class, BlockPos.class);
        search.setAccessible(true);
        assertNull(search.invoke(grid, new BlockPos(0, 63, 0), new BlockPos(1, 63, 1)),
                "both orthogonal neighbours are dry, including on a cross-frontier join");
    }

    @Test
    void directLegCertificationCannotSkipASingleBlockIslandBetweenSamples() {
        BiPredicate<Integer, Integer> water = (x, z) -> x != 10_001 || z != 8;
        GlobalWaterGridPlanner.Guidance g = GlobalWaterGridPlanner.plan(world(water, new int[1]),
                new BlockPos(8, 63, 8), new BlockPos(20_008, 63, 8), 63);
        assertTrue(g.successful());
        for (GlobalWaterGridPlanner.Leg leg : g.legs()) {
            if (!leg.direct()) continue;
            assertTrue(WaterGridTraversal.all(leg.from().getX(), leg.from().getZ(), leg.to().getX(), leg.to().getZ(),
                    (x, z) -> water.test(x, z) && water.test(x + 1, z)
                            && water.test(x, z + 1) && water.test(x + 1, z + 1)),
                    "a direct ocean leg bypasses detailed search and must certify every hull cell");
        }
    }

    @Test
    void anEnclosedStartFailsFastInsteadOfFloodingTheWholeOcean() {
        // The start sits in a 600x600 lake; the goal side is a vast open ocean.
        BiPredicate<Integer, Integer> water = (x, z) -> (x >= 0 && x < 600 && z >= 0 && z < 600) || x > 2_000;
        int[] samples = new int[1];
        GlobalWaterGridPlanner.Guidance g = GlobalWaterGridPlanner.plan(world(water, samples),
                new BlockPos(300, 63, 300), new BlockPos(400_000, 63, 300), 63);
        assertFalse(g.successful());
        assertTrue(g.failure() == WaterRouteFailureReason.NO_WATER_PATH
                || g.failure() == WaterRouteFailureReason.NODE_BUDGET_EXCEEDED);
        // Native-resolution attempts are now allowed, but their combined work
        // must remain bounded even when the opposite side is an infinite ocean.
        assertTrue(g.expandedNodes() < 20_000 + GlobalWaterGridPlanner.MAX_FINE_EXPANSIONS,
                "expanded=" + g.expandedNodes());
    }

    @Test
    void guidanceDrivesTheDetailedPlannerEndToEnd() {
        WaterRouteWorld w = world(STRAIT_SEA, new int[1]);
        BlockPos start = new BlockPos(0, 63, 0), goal = new BlockPos(21_000, 63, 0);
        GlobalWaterGridPlanner.Guidance g = GlobalWaterGridPlanner.plan(w, start, goal, 63, 32);
        HierarchicalWaterRoutePlanner planner = HierarchicalWaterRoutePlanner.withGuidance(w, start, goal,
                WaterRoutePolicy.nbtRefine(), java.util.function.UnaryOperator.identity(), g.legs());
        HierarchicalWaterRoutePlanner.Status status;
        int guard = 0;
        do status = planner.step(256); while (status == HierarchicalWaterRoutePlanner.Status.RUNNING && guard++ < 100_000);
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, status);
        List<BlockPos> path = planner.path();
        assertEquals(start.getX(), path.getFirst().getX());
        assertEquals(goal.getX(), path.getLast().getX());
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            int n = (int) Math.ceil(Math.sqrt(a.distSqr(b)));
            for (int s = 0; s <= n; s++) {
                double t = n == 0 ? 0 : s / (double) n;
                int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * t);
                int z = (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t);
                assertTrue(STRAIT_SEA.test(x, z), "route crosses land at " + x + "," + z);
            }
        }
    }

    private static void assertContinuous(List<GlobalWaterGridPlanner.Leg> legs) {
        for (int i = 1; i < legs.size(); i++) assertEquals(legs.get(i - 1).to(), legs.get(i).from());
    }
}
