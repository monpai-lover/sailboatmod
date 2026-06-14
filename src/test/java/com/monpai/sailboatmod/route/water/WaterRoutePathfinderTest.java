package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterRoutePathfinderTest {
    @Test
    void continuousWaterFindsRoute() {
        TestWaterWorld world = new TestWaterWorld().waterRect(0, -8, 64, 8);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(
                world, new BlockPos(0, 64, 0), new BlockPos(64, 64, 0), testPolicy());

        WaterRoutePathfinder.Status status = runToCompletion(pathfinder);

        assertEquals(WaterRoutePathfinder.Status.SUCCESS, status);
        assertFalse(pathfinder.path().isEmpty());
        assertEquals(new BlockPos(0, 64, 0), pathfinder.path().get(0));
        assertEquals(new BlockPos(64, 64, 0), pathfinder.path().get(pathfinder.path().size() - 1));
    }

    @Test
    void landBarrierFails() {
        TestWaterWorld world = new TestWaterWorld().waterRect(0, -8, 64, 8).landRect(24, -8, 40, 8);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(
                world, new BlockPos(0, 64, 0), new BlockPos(64, 64, 0), testPolicy());

        WaterRoutePathfinder.Status status = runToCompletion(pathfinder);

        assertEquals(WaterRoutePathfinder.Status.FAILED, status);
        assertEquals(WaterRouteFailureReason.NO_WATER_PATH, pathfinder.failureReason());
    }

    @Test
    void nodeBudgetCanPauseSearch() {
        TestWaterWorld world = new TestWaterWorld().waterRect(0, -16, 96, 16);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(
                world, new BlockPos(0, 64, 0), new BlockPos(96, 64, 0), testPolicy());

        WaterRoutePathfinder.Status first = pathfinder.step(1, 64);

        assertEquals(WaterRoutePathfinder.Status.RUNNING, first);
        assertTrue(pathfinder.expandedNodes() <= 1);
    }

    @Test
    void chunkLoadBudgetLimitsLoadsWithinSingleStep() {
        CountingChunkWaterWorld world = new CountingChunkWaterWorld().waterRect(-64, -64, 160, 64);
        WaterRoutePolicy policy = new WaterRoutePolicy(16, 2, 1, 2, 256, 5000, 512, 64, 1, 200);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(
                world, new BlockPos(0, 64, 0), new BlockPos(128, 64, 0), policy);
        world.resetTickLoads();

        WaterRoutePathfinder.Status status = pathfinder.step(64, 1);

        assertEquals(WaterRoutePathfinder.Status.RUNNING, status);
        assertTrue(world.tickChunkLoads() <= 1, "chunkLoadsPerTick must cap loads, got " + world.tickChunkLoads());
    }

    @Test
    void segmentValidationBlocksEightBlockJumpThroughShore() {
        TestWaterWorld world = new TestWaterWorld()
                .waterPoint(0, 0)
                .waterPoint(8, 0);
        WaterRoutePolicy policy = new WaterRoutePolicy(8, 2, 1, 2, 32, 5000, 512, 64, 64, 200);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(
                world, new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), policy);

        WaterRoutePathfinder.Status status = runToCompletion(pathfinder);

        assertEquals(WaterRoutePathfinder.Status.FAILED, status);
        assertEquals(WaterRouteFailureReason.NO_WATER_PATH, pathfinder.failureReason());
    }

    @Test
    void goalToleranceStillRequiresTargetColumnToBeWater() {
        TestWaterWorld world = new TestWaterWorld()
                .waterPoint(0, 0)
                .waterPoint(2, 0)
                .waterPoint(4, 0)
                .waterPoint(6, 0);
        WaterRoutePolicy policy = new WaterRoutePolicy(8, 2, 1, 2, 32, 5000, 512, 64, 64, 200);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(
                world, new BlockPos(0, 64, 0), new BlockPos(8, 64, 0), policy);

        WaterRoutePathfinder.Status status = runToCompletion(pathfinder);

        assertEquals(WaterRoutePathfinder.Status.FAILED, status);
        assertEquals(WaterRouteFailureReason.NO_WATER_PATH, pathfinder.failureReason());
    }

    @Test
    void maxExpandedNodesFailsWithNodeBudgetReason() {
        WaterRoutePolicy policy = new WaterRoutePolicy(8, 2, 1, 2, 256, 2, 512, 64, 64, 200);
        TestWaterWorld world = new TestWaterWorld().waterRect(0, -16, 96, 16);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(
                world, new BlockPos(0, 64, 0), new BlockPos(96, 64, 0), policy);

        WaterRoutePathfinder.Status status = runToCompletion(pathfinder);

        assertEquals(WaterRoutePathfinder.Status.FAILED, status);
        assertEquals(WaterRouteFailureReason.NODE_BUDGET_EXCEEDED, pathfinder.failureReason());
    }

    private static WaterRoutePathfinder.Status runToCompletion(WaterRoutePathfinder pathfinder) {
        WaterRoutePathfinder.Status status = WaterRoutePathfinder.Status.RUNNING;
        int guard = 0;
        while (status == WaterRoutePathfinder.Status.RUNNING && guard++ < 2000) {
            status = pathfinder.step(64, 64);
        }
        return status;
    }

    private static WaterRoutePolicy testPolicy() {
        return new WaterRoutePolicy(8, 2, 1, 2, 256, 5000, 512, 64, 64, 200);
    }

    private static final class TestWaterWorld implements WaterRouteWorld {
        private final Set<Long> water = new HashSet<>();

        TestWaterWorld waterRect(int minX, int minZ, int maxX, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    water.add(key(x, z));
                }
            }
            return this;
        }

        TestWaterWorld waterPoint(int x, int z) {
            water.add(key(x, z));
            return this;
        }

        TestWaterWorld landRect(int minX, int minZ, int maxX, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    water.remove(key(x, z));
                }
            }
            return this;
        }

        @Override
        public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
            return water.contains(key(x, z))
                    ? WaterColumn.passable(new BlockPos(x, 64, z), 0.0D)
                    : WaterColumn.blocked();
        }

        @Override
        public boolean canLoadMoreChunks(int requested) {
            return true;
        }

        @Override
        public int consumedChunkLoads() {
            return 0;
        }

        private static long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }
    }

    private static final class CountingChunkWaterWorld implements WaterRouteWorld {
        private final Set<Long> water = new HashSet<>();
        private final Set<Long> loadedChunks = new HashSet<>();
        private int tickChunkLoads;
        private int totalChunkLoads;

        CountingChunkWaterWorld waterRect(int minX, int minZ, int maxX, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    water.add(key(x, z));
                }
            }
            return this;
        }

        void resetTickLoads() {
            tickChunkLoads = 0;
        }

        int tickChunkLoads() {
            return tickChunkLoads;
        }

        @Override
        public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
            long chunk = key(x >> 4, z >> 4);
            if (loadedChunks.add(chunk)) {
                tickChunkLoads++;
                totalChunkLoads++;
            }
            return water.contains(key(x, z))
                    ? WaterColumn.passable(new BlockPos(x, 64, z), 0.0D)
                    : WaterColumn.blocked();
        }

        @Override
        public boolean canLoadMoreChunks(int requested) {
            return requested > 0;
        }

        @Override
        public int consumedChunkLoads() {
            return totalChunkLoads;
        }

        private static long key(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }
    }
}
