package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic cold terrain: both A* frontiers share just eight future slots. */
class BoundedTerrainFrontierTest {
    private static final class ColdWorld implements WaterRouteWorld {
        final Map<Long, WaterColumn> ready = new HashMap<>();
        final LinkedHashSet<Long> pending = new LinkedHashSet<>();
        final boolean missingNorth;
        int requests;
        ColdWorld(boolean missingNorth) { this.missingNorth = missingNorth; }
        public WaterColumn sample(int x, int z, WaterRoutePolicy p) {
            long k = ((long) x << 32) | (z & 0xffffffffL);
            WaterColumn old = ready.get(k);
            if (old != null) return old;
            if (pending.size() < 8 && pending.add(k)) requests++;
            return null;
        }
        void advanceIo() {
            var iterator = pending.iterator();
            while (iterator.hasNext()) {
                long k = iterator.next(); int x = (int) (k >> 32), z = (int) k;
                if (missingNorth && x == 0 && z == 128) continue;
                iterator.remove();
                ready.put(k, WaterColumn.passable(new BlockPos(x, 65, z), 0));
            }
        }
        public boolean canLoadMoreChunks(int n) { return false; }
        public int consumedChunkLoads() { return 0; }
    }
    private static void check(BlockPos start, BlockPos goal, boolean missingNorth) {
        ColdWorld world = new ColdWorld(missingNorth);
        WaterRoutePathfinder search = new WaterRoutePathfinder(world, start, goal,
                new WaterRoutePolicy(128, 2, 1, 3, 20_000, 32_000, 0, 64, 0, 1800), 16);
        int slices = 0;
        while (slices++ < 10_000 && (search.status() == WaterRoutePathfinder.Status.RUNNING
                || search.status() == WaterRoutePathfinder.Status.WAITING)) {
            world.advanceIo(); search.step(64, 0);
        }
        assertEquals(WaterRoutePathfinder.Status.SUCCESS, search.status(),
                "cold IO must not starve a frontier or flood the whole ocean: requests=" + world.requests);
        assertEquals(start, search.path().getFirst()); assertEquals(goal, search.path().getLast());
        assertTrue(search.forwardExpansions() > 1); assertTrue(search.backwardExpansions() > 1);
        assertTrue(search.expandedNodes() < 1000, "retain A* guidance under asynchronous data delivery");
        assertTrue(world.requests < 25_000);
    }
    @Test void coldSevenKilometreOceanAdvancesBothFrontiers() {
        check(new BlockPos(1, 65, 0), new BlockPos(7000, 65, 0), false);
    }
    @Test void reverseNegativeCoordinatesHaveTheSameIoFairness() {
        check(new BlockPos(-1, 65, 0), new BlockPos(-7000, 65, 0), false);
    }
    @Test void permanentlyUnavailableSideEdgeDoesNotBlockKnownRoute() {
        check(new BlockPos(0, 65, 0), new BlockPos(7000, 65, 0), true);
    }
}
