package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HierarchicalWaterRoutePlannerTest {
    @Test
    void routeBeyondTheOldRadiusGoesAroundAContinentThenRefinesBoundedSegments() {
        CountingOcean world = new CountingOcean(12_000, 20_000, -4096, 4096);
        BlockPos start = new BlockPos(0, 63, 0);
        BlockPos goal = new BlockPos(32_768, 63, 0);
        HierarchicalWaterRoutePlanner planner = planner(world, start, goal);

        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, complete(planner));
        List<BlockPos> path = planner.path();
        assertEquals(start, path.getFirst());
        assertEquals(goal, path.getLast());
        assertTrue(path.stream().anyMatch(p -> Math.abs(p.getZ()) > 4096), "the global route must navigate around land");
        for (int i = 1; i < path.size(); i++) assertWaterEdge(world, path.get(i - 1), path.get(i));
        assertTrue(planner.progress().totalSegments() >= 8);
        assertEquals(planner.progress().totalSegments(), planner.progress().completedSegments());
        assertEquals(0, world.forceRequests, "planning must never ask for actual chunk generation");
    }

    @Test
    void eachAdvanceHasABoundedNodeAndSamplingBudget() {
        CountingOcean world = new CountingOcean(12_000, 20_000, -4096, 4096);
        HierarchicalWaterRoutePlanner planner = planner(world, new BlockPos(0, 63, 0), new BlockPos(32_768, 63, 0));
        for (int i = 0; i < 200; i++) {
            int nodesBefore = planner.progress().expandedNodes();
            int samplesBefore = world.samples;
            HierarchicalWaterRoutePlanner.Status status = planner.step(7);
            assertTrue(planner.progress().expandedNodes() - nodesBefore <= 7, "one step cannot consume a whole search");
            assertTrue(world.samples - samplesBefore <= 7 * HierarchicalWaterRoutePlanner.MAX_SAMPLES_PER_EXPANSION + 4);
            if (status == HierarchicalWaterRoutePlanner.Status.SUCCESS) break;
        }
        assertTrue(world.samples > 0);
        assertEquals(0, world.forceRequests);
    }

    @Test
    void cancellationStopsAllFurtherTerrainRequestsAndDoesNotPublishAPartialRoute() {
        CountingOcean world = new CountingOcean(12_000, 20_000, -4096, 4096);
        HierarchicalWaterRoutePlanner planner = planner(world, new BlockPos(0, 63, 0), new BlockPos(131_072, 63, 0));
        planner.step(8);
        planner.cancel();
        int stoppedAt = world.samples;
        for (int i = 0; i < 5; i++) assertEquals(HierarchicalWaterRoutePlanner.Status.CANCELLED, planner.step(32));
        assertEquals(stoppedAt, world.samples);
        assertTrue(planner.path().isEmpty());
    }

    @Test
    void unavailableTerrainWaitsAndCanResumeWithoutBeingInventedAsWater() {
        CountingOcean world = new CountingOcean(12_000, 20_000, -4096, 4096);
        world.ready = false;
        HierarchicalWaterRoutePlanner planner = planner(world, new BlockPos(0, 63, 0), new BlockPos(32_768, 63, 0));
        assertEquals(HierarchicalWaterRoutePlanner.Status.WAITING, planner.step(16));
        assertTrue(planner.path().isEmpty());
        world.ready = true;
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, complete(planner));
    }

    @Test
    void waitingForAFrontierTileRetainsSearchProgressAndDoesNotConsumeTheNodeBudgetAgain() {
        CountingOcean world = new CountingOcean(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0);
        world.pendingBand = true;
        HierarchicalWaterRoutePlanner planner = planner(world, new BlockPos(0, 63, 0), new BlockPos(32_768, 63, 0));
        HierarchicalWaterRoutePlanner.Status status = HierarchicalWaterRoutePlanner.Status.RUNNING;
        for (int i = 0; i < 100 && status == HierarchicalWaterRoutePlanner.Status.RUNNING; i++) status = planner.step(16);
        assertEquals(HierarchicalWaterRoutePlanner.Status.WAITING, status);
        int before = planner.progress().expandedNodes();
        for (int i = 0; i < 8; i++) assertEquals(HierarchicalWaterRoutePlanner.Status.WAITING, planner.step(16));
        assertEquals(before, planner.progress().expandedNodes(), "waiting must retain the frontier instead of redoing all known nodes");
        world.pendingBand = false;
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, complete(planner));
    }

    @Test
    void negativeCoordinatesAndAOneHundredKiloblockSeaHaveNoFixedDistanceCutoff() {
        CountingOcean world = new CountingOcean(Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0);
        BlockPos start = new BlockPos(-100_003, 63, -20_003);
        BlockPos goal = new BlockPos(31_069, 63, -20_003);
        HierarchicalWaterRoutePlanner planner = planner(world, start, goal);
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, complete(planner));
        assertEquals(start, planner.path().getFirst());
        assertEquals(goal, planner.path().getLast());
        assertTrue(planner.progress().totalSegments() >= 32);
        assertEquals(0, world.forceRequests);
    }

    private static HierarchicalWaterRoutePlanner planner(CountingOcean world, BlockPos start, BlockPos goal) {
        return new HierarchicalWaterRoutePlanner(world, world, start, goal, WaterRoutePolicy.nbtRefine());
    }

    private static HierarchicalWaterRoutePlanner.Status complete(HierarchicalWaterRoutePlanner planner) {
        HierarchicalWaterRoutePlanner.Status status = HierarchicalWaterRoutePlanner.Status.RUNNING;
        for (int i = 0; i < 30_000 && status == HierarchicalWaterRoutePlanner.Status.RUNNING; i++) status = planner.step(64);
        return status;
    }

    private static void assertWaterEdge(CountingOcean world, BlockPos from, BlockPos to) {
        int count = Math.max(1, (int) Math.ceil(Math.sqrt(from.distSqr(to)) / 2));
        for (int i = 0; i <= count; i++) {
            int x = (int) Math.round(from.getX() + (to.getX() - (double) from.getX()) * i / count);
            int z = (int) Math.round(from.getZ() + (to.getZ() - (double) from.getZ()) * i / count);
            assertFalse(world.land(x, z), "route crosses land at " + x + "," + z);
        }
    }

    private static final class CountingOcean implements WaterRouteWorld {
        private final int minX, maxX, minZ, maxZ;
        private int samples, forceRequests;
        private boolean ready = true, pendingBand;
        private CountingOcean(int minX, int maxX, int minZ, int maxZ) {
            this.minX = minX; this.maxX = maxX; this.minZ = minZ; this.maxZ = maxZ;
        }
        private boolean land(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
        @Override public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
            samples++;
            if (!ready || (pendingBand && x >= 4096 && x <= 8192)) return null;
            return land(x, z) ? WaterColumn.blocked() : WaterColumn.passable(new BlockPos(x, 63, z), 0);
        }
        @Override public boolean canLoadMoreChunks(int requested) { forceRequests += requested; return false; }
        @Override public int consumedChunkLoads() { return forceRequests; }
    }
}
