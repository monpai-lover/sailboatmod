package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.Prediction;
import com.monpai.sailboatmod.route.terrain.PredictionBudget;
import com.monpai.sailboatmod.route.terrain.PrefetchGate;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Saved block columns can contradict a mask-certified direct leg at its coastal handoff. */
class DirectGuidanceReconciliationTest {
    private static final BlockPos START = new BlockPos(32308, 65, -50180);
    private static final BlockPos BAD_HINT = new BlockPos(32332, 65, -50204);
    private static final BlockPos NEXT_HINT = new BlockPos(32340, 65, -50212);
    private static final BlockPos GOAL = new BlockPos(32364, 65, -50236);

    private record Fixture(RealBlockWaterMap map, WaterRouteWorld world, AtomicInteger predictions) { }

    private static Fixture fixture(BiPredicate<Integer, Integer> water, BlockPos raised) {
        AtomicInteger predictions = new AtomicInteger();
        RealBlockWaterMap map = new RealBlockWaterMap(null, 63).enableOnDemand().retryUnknownPrediction()
                .withPredictor(new TerrainPredictor() {
                    @Override public Prediction predictChunk(int x, int z, long waitMillis) {
                        predictions.incrementAndGet();
                        return Prediction.ready(columns(x, z, (a, b) -> true, null));
                    }
                    @Override public PrefetchGate gate() { return PrefetchGate.ALWAYS_READY; }
                }, PredictionBudget.sixtySeconds());
        // Synthetic saved/snapshot columns cover the whole local repair. The all-ocean predictor
        // deliberately disagrees with the quay and must never overwrite these columns.
        for (int cz = -3150; cz <= -3125; cz++) for (int cx = 2010; cx <= 2035; cx++)
            map.seedChunk(cx, cz, columns(cx, cz, water, raised));
        return new Fixture(map, RealBlockWaterWorld.streaming(map, 65, 1, START, GOAL), predictions);
    }

    private static NbtChunkWaterReader.ChunkColumns columns(int cx, int cz,
            BiPredicate<Integer, Integer> water, BlockPos raised) {
        boolean[] wet = new boolean[256];
        int[] heights = new int[256];
        Arrays.fill(heights, 64);
        for (int i = 0; i < wet.length; i++) {
            int x = cx * 16 + (i & 15), z = cz * 16 + (i >> 4);
            wet[i] = water.test(x, z);
            if (raised != null && x >= raised.getX() && x <= raised.getX() + 1
                    && z >= raised.getZ() && z <= raised.getZ() + 1) heights[i] = 65;
        }
        return new NbtChunkWaterReader.ChunkColumns(wet, heights);
    }

    private static HierarchicalWaterRoutePlanner planner(WaterRouteWorld world, BlockPos start, BlockPos goal,
            List<GlobalWaterGridPlanner.Leg> legs) {
        return HierarchicalWaterRoutePlanner.withGuidance(world, start, goal, WaterRoutePolicy.nbtRefine(),
                java.util.function.UnaryOperator.identity(), legs);
    }

    private static void complete(HierarchicalWaterRoutePlanner planner) {
        for (int i = 0; i < 10_000; i++) {
            var status = planner.step(64);
            if (status != HierarchicalWaterRoutePlanner.Status.RUNNING
                    && status != HierarchicalWaterRoutePlanner.Status.WAITING) return;
        }
        fail("local coastal fixture exceeded its bounded search guard");
    }

    private static void assertRealWaterRoute(Fixture fixture, HierarchicalWaterRoutePlanner planner,
            BlockPos start, BlockPos goal) {
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, planner.progress().status());
        assertEquals(start, planner.path().getFirst());
        assertEquals(goal, planner.path().getLast());
        for (int i = 1; i < planner.path().size(); i++)
            assertTrue(fixture.map().segmentHullClear(planner.path().get(i - 1), planner.path().get(i), 1),
                    "the final route must clear every actual 2x2 hull anchor, edge " + i);
        assertEquals(0, fixture.predictions().get(), "saved real columns take precedence over ocean prediction");
        assertEquals(0, fixture.map().predictedHits());
        assertEquals(0, fixture.map().forcedHits(), "repair cannot force world generation");
    }

    @Test void loggedDirectToDetailHandoffDetoursActualCoastInBothDirections() {
        for (boolean reverse : List.of(false, true)) {
            Fixture fixture = fixture((x, z) -> !(x >= 32328 && x <= 32335
                    && z >= -50208 && z <= -50201), null);
            assertFalse(fixture.map().sampleHullStrict(BAD_HINT.getX(), BAD_HINT.getZ(), 1).passable());
            List<GlobalWaterGridPlanner.Leg> legs = new ArrayList<>(List.of(
                    new GlobalWaterGridPlanner.Leg(START, BAD_HINT, true),
                    new GlobalWaterGridPlanner.Leg(BAD_HINT, NEXT_HINT, false),
                    new GlobalWaterGridPlanner.Leg(NEXT_HINT, GOAL, true)));
            if (reverse) {
                List<GlobalWaterGridPlanner.Leg> reversed = new ArrayList<>();
                for (int i = legs.size() - 1; i >= 0; i--) {
                    var leg = legs.get(i);
                    reversed.add(new GlobalWaterGridPlanner.Leg(leg.to(), leg.from(), leg.direct()));
                }
                legs = reversed;
            }
            BlockPos start = reverse ? GOAL : START, goal = reverse ? START : GOAL;
            var planner = planner(fixture.world(), start, goal, legs);
            complete(planner);
            assertRealWaterRoute(fixture, planner, start, goal);
            assertFalse(planner.path().contains(BAD_HINT));
            assertTrue(planner.skippedGuidanceAnchors() > 0);
            assertTrue(planner.progress().expandedNodes() > 0, "a mask shortcut must undergo an actual-water detour");
        }
    }

    @Test void unknownDirectHandoffWaitsWithoutPublishingOrDeletingTheHint() {
        Fixture fixture = fixture((x, z) -> true, null);
        AtomicBoolean ready = new AtomicBoolean();
        WaterRouteWorld waiting = new WaterRouteWorld() {
            @Override public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
                return !ready.get() && x == BAD_HINT.getX() && z == BAD_HINT.getZ()
                        ? null : fixture.world().sample(x, z, policy);
            }
            @Override public boolean canLoadMoreChunks(int requested) {
                fail("planning cannot request chunk generation"); return false;
            }
            @Override public int consumedChunkLoads() { return 0; }
        };
        var planner = planner(waiting, START, GOAL, List.of(
                new GlobalWaterGridPlanner.Leg(START, BAD_HINT, true),
                new GlobalWaterGridPlanner.Leg(BAD_HINT, GOAL, false)));
        assertEquals(HierarchicalWaterRoutePlanner.Status.WAITING, planner.step(64));
        assertEquals(0, planner.progress().completedSegments());
        assertEquals(0, planner.skippedGuidanceAnchors());
        assertTrue(planner.path().isEmpty());
        ready.set(true);
        complete(planner);
        assertRealWaterRoute(fixture, planner, START, GOAL);
    }

    @Test void aDiagonalRepairCutRetainsTheOriginallyCertifiedStraightPrefix() {
        BlockPos start = new BlockPos(0, 65, 0), hint = new BlockPos(100_000, 65, 20_000);
        BlockPos goal = hint.offset(128, 0, 0);
        // The extra one-block obstacle misses the original direct hull and all of its
        // native-clearance samples, but lies under a naively rounded prefix cut.
        BiPredicate<Integer, Integer> wet = (x, z) -> !(x == 72650 && z == 14529)
                && !(Math.abs(x - hint.getX()) <= 4 && Math.abs(z - hint.getZ()) <= 4);
        WaterRouteWorld world = new WaterRouteWorld() {
            @Override public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
                for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++)
                    if (!wet.test(x + dx, z + dz)) return WaterColumn.blocked();
                return WaterColumn.passable(new BlockPos(x, 65, z), 0);
            }
            @Override public boolean canLoadMoreChunks(int n) { fail("no chunk generation"); return false; }
            @Override public int consumedChunkLoads() { return 0; }
        };
        var planner = planner(world, start, goal, List.of(
                new GlobalWaterGridPlanner.Leg(start, hint, true),
                new GlobalWaterGridPlanner.Leg(hint, goal, false)));
        complete(planner);
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, planner.progress().status());
        boolean retainedLongPrefix = false;
        for (int i = 1; i < planner.path().size(); i++) {
            BlockPos a = planner.path().get(i - 1), b = planner.path().get(i);
            assertTrue(WaterGridTraversal.all(a.getX(), a.getZ(), b.getX(), b.getZ(),
                    (x, z) -> world.sample(x, z, WaterRoutePolicy.nbtRefine()).passable()));
            if (planner.directEdges().get(i - 1) && a.distSqr(b) > 90_000.0 * 90_000) {
                retainedLongPrefix = true;
                assertEquals(0L, (long) b.getX() * hint.getZ() - (long) b.getZ() * hint.getX(),
                        "a preserved prefix cannot leave its mask-certified straight line");
            }
        }
        assertTrue(retainedLongPrefix, "keep the long certified prefix outside fine postprocessing");
    }

    @Test void aCoprimeChangedPrefixWaitsForNativeCertificationThenResumes() {
        AtomicBoolean ready = new AtomicBoolean();
        AtomicInteger certifications = new AtomicInteger();
        var nativeCertificate = GlobalWaterGridPlanner.directRecertifier(sampleWorld((x, z) -> ready.get()
                ? WaterColumn.passable(new BlockPos(x, 65, z), 0) : null), 65);
        var planner = coprimePlanner((a, b) -> {
            certifications.incrementAndGet();
            return nativeCertificate.test(a, b);
        }, System::nanoTime);
        assertEquals(HierarchicalWaterRoutePlanner.Status.WAITING, planner.step(64));
        assertEquals(1, certifications.get());
        assertEquals(0, planner.progress().completedSegments());
        assertTrue(planner.path().isEmpty());
        ready.set(true);
        complete(planner);
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS, planner.progress().status());
        assertTrue(certifications.get() > 1, "the changed geometry needs a successful native-mask certificate");
        assertTrue(planner.directEdges().getFirst());
        assertTrue(planner.path().getFirst().distSqr(planner.path().get(1)) > 90_000.0 * 90_000);
        var actual = coprimeActualWorld();
        for (int i = 1; i < planner.path().size(); i++) {
            BlockPos a = planner.path().get(i - 1), b = planner.path().get(i);
            assertTrue(WaterGridTraversal.all(a.getX(), a.getZ(), b.getX(), b.getZ(),
                    (x, z) -> actual.sample(x, z, WaterRoutePolicy.nbtRefine()).passable()));
        }
    }

    @Test void aRejectedNativeCertificateCannotAppendACutOrRetryManyLongLinesInOneSlice() {
        AtomicInteger certifications = new AtomicInteger();
        var nativeCertificate = GlobalWaterGridPlanner.directRecertifier(
                sampleWorld((x, z) -> WaterColumn.blocked()), 65);
        var planner = coprimePlanner((a, b) -> {
            certifications.incrementAndGet();
            return nativeCertificate.test(a, b);
        }, System::nanoTime);
        for (int poll = 1; poll <= 2; poll++) {
            assertEquals(HierarchicalWaterRoutePlanner.Status.RUNNING, planner.step(64));
            assertEquals(poll, certifications.get(), "at most one full native recertification per slice");
            assertEquals(0, planner.progress().completedSegments());
            assertTrue(planner.path().isEmpty());
        }
        planner.cancel();
        assertEquals(HierarchicalWaterRoutePlanner.Status.CANCELLED, planner.progress().status());
    }

    @Test void aNativeCertificateCompletingAfterTheTaskDeadlineCannotPublishItsPrefix() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger certifications = new AtomicInteger();
        var nativeCertificate = GlobalWaterGridPlanner.directRecertifier(
                sampleWorld((x, z) -> WaterColumn.passable(new BlockPos(x, 65, z), 0)), 65);
        var planner = coprimePlanner((a, b) -> {
            certifications.incrementAndGet();
            boolean clear = nativeCertificate.test(a, b);
            clock.addAndGet((HierarchicalWaterRoutePlanner.MAX_TASK_MILLIS + 1) * 1_000_000L);
            return clear;
        }, clock::get);
        assertEquals(HierarchicalWaterRoutePlanner.Status.FAILED, planner.step(64));
        assertEquals(WaterRouteFailureReason.TIMEOUT, planner.failureReason());
        assertEquals(1, certifications.get());
        assertEquals(0, planner.progress().completedSegments());
        assertTrue(planner.path().isEmpty());
        assertTrue(planner.directEdges().isEmpty());
    }

    private static WaterRouteWorld sampleWorld(BiFunction<Integer, Integer, WaterColumn> sampler) {
        return new WaterRouteWorld() {
            @Override public WaterColumn sample(int x, int z, WaterRoutePolicy p) { return sampler.apply(x, z); }
            @Override public boolean canLoadMoreChunks(int n) { fail("no chunk generation"); return false; }
            @Override public int consumedChunkLoads() { return 0; }
        };
    }

    private static WaterRouteWorld coprimeActualWorld() {
        return sampleWorld((x, z) -> {
            for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++)
                if (Math.abs(x + dx - 100_000) <= 4 && Math.abs(z + dz - 20_001) <= 4)
                    return WaterColumn.blocked();
            return WaterColumn.passable(new BlockPos(x, 65, z), 0);
        });
    }

    private static HierarchicalWaterRoutePlanner coprimePlanner(BiPredicate<BlockPos, BlockPos> nativeCertificate,
            LongSupplier clock) {
        BlockPos start = new BlockPos(0, 65, 0), hint = new BlockPos(100_000, 65, 20_001);
        BlockPos goal = hint.offset(128, 0, 0);
        return HierarchicalWaterRoutePlanner.withGuidance(coprimeActualWorld(), start, goal, WaterRoutePolicy.nbtRefine(),
                java.util.function.UnaryOperator.identity(), List.of(
                        new GlobalWaterGridPlanner.Leg(start, hint, true),
                        new GlobalWaterGridPlanner.Leg(hint, goal, false)), nativeCertificate, clock);
    }

    @Test void actualInitialBerthOnLandIsNeverMovedOrTrustedByADirectLeg() {
        assertInvalidRealEndpoint(START, false);
    }

    @Test void actualFinalBerthOnLandIsNeverDeletedByADirectLeg() {
        assertInvalidRealEndpoint(GOAL, false);
    }

    @Test void actualInitialBerthHeightIsNeverMovedByADirectLeg() {
        assertInvalidRealEndpoint(START, true);
    }

    @Test void actualFinalBerthHeightIsNeverMovedByADirectLeg() {
        assertInvalidRealEndpoint(GOAL, true);
    }

    private static void assertInvalidRealEndpoint(BlockPos invalid, boolean raised) {
        Fixture fixture = fixture((x, z) -> raised || x != invalid.getX() || z != invalid.getZ(),
                raised ? invalid : null);
        var planner = planner(fixture.world(), START, GOAL,
                List.of(new GlobalWaterGridPlanner.Leg(START, GOAL, true)));
        complete(planner);
        assertEquals(HierarchicalWaterRoutePlanner.Status.FAILED, planner.progress().status());
        assertEquals(WaterRouteFailureReason.NO_WATER_PATH, planner.failureReason());
        assertTrue(planner.path().isEmpty());
        assertEquals(0, fixture.predictions().get());
        assertEquals(0, fixture.map().predictedHits());
        assertEquals(0, fixture.map().forcedHits());
    }
}
