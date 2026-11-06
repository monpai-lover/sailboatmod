package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class LongDirectAnchorRepairTest {
    @Test @Timeout(30)
    void aHundredKiloblockDirectPrefixDoesNotBecomeAHundredKiloblockFineSearch() {
        for (int offset : new int[]{0, -120_000}) {
            Ocean ocean = new Ocean(offset, false);
            var planner = planner(ocean);
            complete(planner);
            assertVerified(ocean, planner);
            assertTrue(planner.directEdges().contains(true), "a healthy open-water prefix must retain its fast path");
            assertTrue(planner.directEdges().contains(false), "the coastal tail must be precisely rerouted");
            assertTrue(planner.progress().expandedNodes() < 8_192, "repair work must stay local");
            assertTrue(ocean.samples < 200_000, "do not resample the whole long ocean at fine resolution");
        }
    }

    @Test @Timeout(30)
    void aRaisedInternalWaterSurfaceIsDetouredInsteadOfForcingAnImpossibleHeightChange() {
        Ocean ocean = new Ocean(0, true);
        var planner = planner(ocean);
        complete(planner);
        assertVerified(ocean, planner);
        assertTrue(planner.path().stream().allMatch(p -> p.getY() == 65));
    }

    @Test @Timeout(30)
    void aLongOutgoingOceanSuffixIsRetainedWhenTheBadHandoffIsApproachedInReverse() {
        Ocean ocean = new Ocean(0, false);
        var planner = HierarchicalWaterRoutePlanner.withGuidance(ocean, ocean.goal(), ocean.start(), WaterRoutePolicy.nbtRefine(),
                UnaryOperator.identity(), List.of(
                        new GlobalWaterGridPlanner.Leg(ocean.goal(), ocean.after(), true),
                        new GlobalWaterGridPlanner.Leg(ocean.after(), ocean.hint(), false),
                        new GlobalWaterGridPlanner.Leg(ocean.hint(), ocean.start(), true)));
        complete(planner);
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, planner.progress().status(), planner.failureReason().name());
        assertEquals(ocean.goal(), planner.path().getFirst());
        assertEquals(ocean.start(), planner.path().getLast());
        assertTrue(planner.directEdges().contains(true));
        assertTrue(planner.directEdges().contains(false));
        assertTrue(planner.progress().expandedNodes() < 8_192, "outgoing ocean must not turn into a huge fine search");
    }

    @Test @Timeout(30)
    void anUnknownPrefixCutWaitsAndCannotPublishAnUncheckedShortcut() {
        Ocean ocean = new Ocean(0, false);
        ocean.cutReady.set(false);
        var planner = planner(ocean);
        for (int i = 0; i < 10 && planner.progress().status() == HierarchicalWaterRoutePlanner.Status.RUNNING; i++) planner.step(64);
        assertEquals(HierarchicalWaterRoutePlanner.Status.WAITING, planner.progress().status());
        assertEquals(0, planner.progress().completedSegments());
        assertTrue(planner.path().isEmpty());
        ocean.cutReady.set(true);
        complete(planner);
        assertVerified(ocean, planner);
    }

    @Test
    void anUnmodifiedMillionBlockOceanNeedsOnlyBoundaryTerrainSamples() {
        int[] samples = {0};
        WaterRouteWorld ocean = new WaterRouteWorld() {
            @Override public WaterColumn sample(int x, int z, WaterRoutePolicy p) {
                samples[0]++; return WaterColumn.passable(new BlockPos(x, 65, z), 0);
            }
            @Override public boolean canLoadMoreChunks(int n) { fail("no chunk generation"); return false; }
            @Override public int consumedChunkLoads() { return 0; }
        };
        BlockPos start = new BlockPos(-500_000, 65, -30_000), goal = new BlockPos(500_000, 65, -30_000);
        var planner = HierarchicalWaterRoutePlanner.withGuidance(ocean, start, goal, WaterRoutePolicy.nbtRefine(),
                UnaryOperator.identity(), List.of(new GlobalWaterGridPlanner.Leg(start, goal, true)));
        complete(planner);
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, planner.progress().status());
        assertEquals(List.of(start, goal), planner.path());
        assertEquals(List.of(true), planner.directEdges());
        assertTrue(samples[0] <= 8, "healthy long direct legs must not load a line of precise chunks");
    }

    private static HierarchicalWaterRoutePlanner planner(Ocean ocean) {
        return HierarchicalWaterRoutePlanner.withGuidance(ocean, ocean.start(), ocean.goal(), WaterRoutePolicy.nbtRefine(),
                UnaryOperator.identity(), List.of(
                        new GlobalWaterGridPlanner.Leg(ocean.start(), ocean.hint(), true),
                        new GlobalWaterGridPlanner.Leg(ocean.hint(), ocean.after(), false),
                        new GlobalWaterGridPlanner.Leg(ocean.after(), ocean.goal(), true)));
    }

    private static void complete(HierarchicalWaterRoutePlanner planner) {
        for (int i = 0; i < 20_000; i++) {
            var status = planner.step(64);
            if (status != HierarchicalWaterRoutePlanner.Status.RUNNING && status != HierarchicalWaterRoutePlanner.Status.WAITING) return;
        }
        fail("bounded local repair did not finish");
    }

    private static void assertVerified(Ocean ocean, HierarchicalWaterRoutePlanner planner) {
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, planner.progress().status(), planner.failureReason().name());
        assertEquals(ocean.start(), planner.path().getFirst());
        assertEquals(ocean.goal(), planner.path().getLast());
        assertFalse(planner.path().contains(ocean.hint()));
        for (int i = 1; i < planner.path().size(); i++) {
            BlockPos a = planner.path().get(i - 1), b = planner.path().get(i);
            assertTrue(WaterGridTraversal.all(a.getX(), a.getZ(), b.getX(), b.getZ(),
                    (x, z) -> ocean.actualWater(x, z) && ocean.actualWater(x + 1, z)
                            && ocean.actualWater(x, z + 1) && ocean.actualWater(x + 1, z + 1)));
        }
    }

    private static final class Ocean implements WaterRouteWorld {
        final int offset;
        final boolean raised;
        final AtomicBoolean cutReady = new AtomicBoolean(true);
        int samples;
        Ocean(int offset, boolean raised) { this.offset = offset; this.raised = raised; }
        BlockPos start() { return new BlockPos(offset, 65, -30_000); }
        BlockPos hint() { return new BlockPos(offset + 100_000, 65, -30_000); }
        BlockPos after() { return hint().offset(128, 0, 0); }
        BlockPos goal() { return hint().offset(256, 0, 0); }
        boolean patch(int x, int z) { return Math.abs(x - hint().getX()) <= 4 && Math.abs(z - hint().getZ()) <= 4; }
        boolean actualWater(int x, int z) { return !patch(x, z); }
        @Override public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
            samples++;
            if (!cutReady.get() && x == hint().getX() - HierarchicalWaterRoutePlanner.DETAIL_SEGMENT_BLOCKS && z == hint().getZ()) return null;
            Integer height = null;
            for (int dz = 0; dz <= 1; dz++) for (int dx = 0; dx <= 1; dx++) {
                boolean changed = patch(x + dx, z + dz);
                if (changed && !raised) return WaterColumn.blocked();
                int y = changed ? 66 : 65;
                if (height != null && height != y) return WaterColumn.blocked();
                height = y;
            }
            return WaterColumn.passable(new BlockPos(x, height, z), 0);
        }
        @Override public boolean canLoadMoreChunks(int n) { fail("no chunk generation"); return false; }
        @Override public int consumedChunkLoads() { return 0; }
    }
}
