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
    void anEnclosedStartFailsFastInsteadOfFloodingTheWholeOcean() {
        // The start sits in a 600x600 lake; the goal side is a vast open ocean.
        BiPredicate<Integer, Integer> water = (x, z) -> (x >= 0 && x < 600 && z >= 0 && z < 600) || x > 2_000;
        int[] samples = new int[1];
        GlobalWaterGridPlanner.Guidance g = GlobalWaterGridPlanner.plan(world(water, samples),
                new BlockPos(300, 63, 300), new BlockPos(400_000, 63, 300), 63);
        assertFalse(g.successful());
        assertEquals(WaterRouteFailureReason.NO_WATER_PATH, g.failure());
        assertTrue(g.expandedNodes() < 20_000, "expanded=" + g.expandedNodes());
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
