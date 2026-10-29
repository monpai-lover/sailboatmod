package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.roadplanner.map.SharedMapTerrainRenderer;
import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class SharedMapPredictionCancellationTest {
    @Test void discardedQueuedTileNeverStartsAndCanBeRequestedAgain() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<String> rendered = new ArrayList<>();
        SharedMapPredictionQueue queue = new SharedMapPredictionQueue(4, workers::add);
        queue.request("discarded", () -> rendered.add("old"));
        cancel(queue, "discarded");
        assertEquals(0, queue.pendingCount());
        assertTrue(queue.request("discarded", () -> rendered.add("new")));
        workers.getFirst().run();
        assertEquals(List.of("new"), rendered);
    }

    @Test void finishingDiscardedRunningTileDoesNotForgetItsQueuedReplacement() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<String> rendered = new ArrayList<>();
        SharedMapPredictionQueue queue = new SharedMapPredictionQueue(4, workers::add);
        queue.request("same", () -> {
            try { cancel(queue, "same"); }
            catch (Exception failure) { throw new AssertionError(failure); }
            assertTrue(queue.request("same", () -> rendered.add("replacement")));
            queue.request("newer", () -> {
                assertFalse(queue.request("same", () -> fail("replacement lost its deduplication")));
                rendered.add("newer");
            });
        });
        workers.getFirst().run();
        assertEquals(List.of("newer", "replacement"), rendered);
    }

    @Test void fineRasterStopsBeforeAnotherPointWhenAnExternalSampleReturnsAfterCancellation() throws Exception {
        assertStopsAfterOneSample(1);
    }

    @Test void coarseRasterDoesNotStartNorthReliefOrAnotherPointAfterCancellation() throws Exception {
        assertStopsAfterOneSample(16);
    }

    @Test void alreadyDiscardedRasterDoesNotEnterTellusAtAll() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        int[] pixels = render((x, z, bps) -> {
            calls.incrementAndGet();
            return Optional.empty();
        }, 1, () -> true);
        assertEquals(0, calls.get());
        assertTrue(java.util.Arrays.stream(pixels).allMatch(pixel -> pixel == 0));
    }

    private static void assertStopsAfterOneSample(int bps) throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        render((x, z, spacing) -> {
            calls.incrementAndGet();
            cancelled.set(true); // Simulates an in-flight synchronous Tellus call returning after discard.
            return Optional.of(new SurfaceSample(63, null, true, 5));
        }, bps, cancelled::get);
        assertEquals(1, calls.get(), "an obsolete tile must not continue its remaining 65,536 pixels");
    }

    private static void cancel(SharedMapPredictionQueue queue, String key) throws Exception {
        try { queue.getClass().getMethod("cancel", String.class).invoke(queue, key); }
        catch (NoSuchMethodException missing) { fail("discard must remove queued Tellus predictions", missing); }
    }

    private static int[] render(TerrainSurfaceSampler sampler, int bps, BooleanSupplier cancelled) throws Exception {
        Method method;
        try {
            method = SharedMapTerrainRenderer.class.getMethod("render", TerrainSurfaceSampler.class,
                    int.class, int.class, int.class, int.class, int.class, BooleanSupplier.class);
        } catch (NoSuchMethodException missing) {
            return fail("fine and coarse Tellus rasters need cooperative cancellation at every sample", missing);
        }
        return (int[]) method.invoke(null, sampler, 0, 0, 256, 256, bps, cancelled);
    }
}
