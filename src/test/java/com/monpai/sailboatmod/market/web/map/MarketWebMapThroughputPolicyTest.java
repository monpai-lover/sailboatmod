package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapThroughputPolicyTest {
    private static final String WORLD = "minecraft:overworld";

    @Test
    void saturatedBulkQueueReservesOnly64ExtraLoadedKeysWithoutEvictingAcceptedWork() throws Exception {
        Constructor<MarketWebMapRenderQueue> constructor;
        try { constructor = MarketWebMapRenderQueue.class.getConstructor(int.class, int.class); }
        catch (NoSuchMethodException exception) { fail("Foreground work needs a bounded reserve when all 4096 bulk slots are accepted"); return; }
        MarketWebMapRenderQueue queue = constructor.newInstance(4096, 64);
        for (int x = 0; x < 4096; x++) assertTrue(queue.enqueue(WORLD, x, 0, 0L, MarketWebMapTileQuality.SERVER_REGION_SCAN));
        for (int x = 1; x <= 64; x++) assertTrue(queue.enqueue(WORLD, -x, 0, 1L));
        assertFalse(queue.enqueue(WORLD, -65, 0, 2L));
        assertFalse(queue.enqueue(WORLD, 5000, 0, 2L, MarketWebMapTileQuality.SERVER_REGION_SCAN));
        assertEquals(0, queue.remainingCapacity(), "bulk capacity must stay clamped at zero while reserve is in use");
        assertEquals(4160, queue.snapshot().size());
        List<MarketWebMapRenderQueue.Task> claims = queue.pollTrackedLoaded(64, 3L);
        assertEquals(64, claims.size());
        assertFalse(queue.enqueue(WORLD, -65, 0, 4L), "claims still consume the bounded reserve");
        for (var task : claims) assertTrue(queue.enqueue(WORLD, task.chunkX(), task.chunkZ(), 4L, task.quality()));
        assertEquals(4160, queue.snapshot().size(), "DEFER requeue and claim count as one accepted coordinate");
        claims.forEach(queue::acknowledge);
        assertEquals(4160, queue.snapshot().size());
        var retried = queue.pollTrackedLoaded(64, 5L);
        retried.forEach(queue::acknowledge);
        assertEquals(4096, queue.snapshot().size());
        assertEquals(4096L, queue.snapshot().stream().filter(task -> task.quality() == MarketWebMapTileQuality.SERVER_REGION_SCAN).count());
    }

    @Test
    void foregroundSelectionRotatesCurrentReaderChunksWithBoundedInspection() throws Exception {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(64);
        MarketWebMapRenderService service = new MarketWebMapRenderService(queue);
        MarketWebMapRenderManager manager = (MarketWebMapRenderManager) get(service, "renderManager");
        try {
            Class<?> jobType = Class.forName(MarketWebMapRenderManager.class.getName() + "$RenderJob");
            Method full = jobType.getDeclaredMethod("full", List.class); full.setAccessible(true);
            set(manager, "activeJob", full.invoke(null, List.of(new MarketWebMapRegionScanService.RegionFile(0, 0, List.of(0)))));
            for (int x = 0; x < 32; x++) queue.enqueue(WORLD, x, 0, 0L);
            queue.enqueue(WORLD, 64, 0, 0L);
            ((MarketWebMapTickBudget) get(manager, "tickBudget")).beginTick(1L, 30_000_000_000L, 8, 4);
            Method poll;
            try { poll = service.getClass().getDeclaredMethod("pollForegroundTask", long.class, MarketWebMapRenderService.ChunkLoadedPredicate.class, int[].class); }
            catch (NoSuchMethodException exception) { fail("Foreground selection must exclude the fullreader region without scanning all queued work"); return; }
            poll.setAccessible(true);
            int[] firstPass = {0};
            assertNull(poll.invoke(service, 1L, (MarketWebMapRenderService.ChunkLoadedPredicate) (x, z) -> true, firstPass));
            assertEquals(32, firstPass[0]);
            assertEquals(33, queue.snapshot().size(), "excluded current-region tasks must remain recoverable");
            int[] nextPass = {0};
            MarketWebMapRenderQueue.Task visible = (MarketWebMapRenderQueue.Task) poll.invoke(service, 2L,
                    (MarketWebMapRenderService.ChunkLoadedPredicate) (x, z) -> true, nextPass);
            assertEquals(64, visible.chunkX());
            assertEquals(1, nextPass[0], "the bounded rotation must expose another region next time");
            queue.acknowledge(visible);
            assertEquals(32, queue.snapshot().size());
        } finally {
            service.stopRegionWatcher(null);
            manager.shutdown();
            ((MarketWebMapRenderManager) get(service, "renderManager")).shutdown();
        }
    }

    @Test
    void managerCheckpointsAtCoarseCadenceAndStopRequestsPendingStateImmediately() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        try {
            manager.markRegionDirty(0, 0);
            Method checkpoint = manager.getClass().getDeclaredMethod("checkpointBackgroundState", net.minecraft.server.level.ServerLevel.class, int.class);
            checkpoint.setAccessible(true);
            for (int tick = 1; tick <= 45; tick++) {
                set(manager, "tickCounter", tick);
                checkpoint.invoke(manager, null, 40);
            }
            var requests = (java.util.concurrent.atomic.AtomicLong) get(manager, "diagStateCheckpointRequests");
            assertEquals(1L, requests.get(), "faster pipeline passes cannot generate 4 Hz dirty JSON requests");
            manager.savePendingWork(null, null);
            assertEquals(2L, requests.get(), "stop must not wait for the next coarse checkpoint");
        } finally { manager.shutdown(); }
    }

    @Test
    void foregroundWriteForSameCoordinatesCannotCompleteFullReaderCheckpoint() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        try {
            Method newState = manager.getClass().getDeclaredMethod("newRegionState", String.class, int.class, int.class);
            newState.setAccessible(true);
            Object fullState = newState.invoke(manager, WORLD, 0, 0);
            Object foregroundState = newState.invoke(manager, WORLD, 0, 0);
            set(manager, "readerState", fullState); set(manager, "readerAwaitWriteValid", true);
            set(manager, "readerAwaitWriteKey", 0L); set(manager, "readerScheduled", true);
            Method complete;
            try { complete = manager.getClass().getDeclaredMethod("markReaderRegionWritten", long.class, fullState.getClass()); }
            catch (NoSuchMethodException exception) { fail("Fullreader completion must identify its own state, not only a shared tile coordinate"); return; }
            complete.setAccessible(true);
            complete.invoke(manager, 0L, foregroundState);
            assertEquals(false, get(manager, "readerRegionWritten"));
            complete.invoke(manager, 0L, fullState);
            assertEquals(true, get(manager, "readerRegionWritten"));
        } finally { manager.shutdown(); }
    }

    @Test
    void smallRegionBecomesReadyAfterFiveTicksDespiteOtherDirtyAndProbeWork() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        try {
            Method collect;
            try { collect = manager.getClass().getDeclaredMethod("collectTimedPartialRegions"); }
            catch (NoSuchMethodException exception) { fail("Small region flush must have a deadline independent of global pipeline idleness"); return; }
            collect.setAccessible(true);
            Method newState = manager.getClass().getDeclaredMethod("newRegionState", String.class, int.class, int.class);
            newState.setAccessible(true);
            set(manager, "snapshotTick", 1L);
            Object state = newState.invoke(manager, WORLD, 0, 0);
            Method put = state.getClass().getDeclaredMethod("put", MarketWebMapChunkSnapshot.class, MarketWebMapTileQuality.class);
            put.setAccessible(true);
            put.invoke(state, new MarketWebMapChunkSnapshot(WORLD, 0, 0, null), MarketWebMapTileQuality.SERVER_LOADED_CHUNK);
            @SuppressWarnings("unchecked") Map<Long, Object> states = (Map<Long, Object>) get(manager, "regionStates");
            states.put(0L, state);
            @SuppressWarnings("unchecked") Map<Long, Object> probes = (Map<Long, Object>) get(manager, "pendingProbe");
            probes.put(999L, null);
            ((MarketWebMapDirtyChunkQueue) get(manager, "dirtyChunks")).markDirty(WORLD, 100, 100);
            MarketWebMapTickBudget budget = (MarketWebMapTickBudget) get(manager, "tickBudget");
            set(manager, "snapshotTick", 5L); budget.beginTick(5L, 30_000_000_000L, 8, 4);
            assertEquals(List.of(), collect.invoke(manager));
            set(manager, "snapshotTick", 6L); budget.beginTick(6L, 30_000_000_000L, 8, 4);
            assertEquals(List.of(state), collect.invoke(manager));
            assertSame(state, states.get(0L), "ready work stays recoverable until render admission takes ownership");
        } finally { manager.shutdown(); }
    }

    @Test
    void sparseRegionCursorVisitsOnlySavedLocalChunks() throws Exception {
        Class<?> type = Class.forName(MarketWebMapRenderService.class.getName() + "$RegionCursor");
        Constructor<?> constructor;
        try { constructor = type.getDeclaredConstructor(int.class, int.class, List.class); }
        catch (NoSuchMethodException exception) { fail("Region cursors must retain generated local chunk evidence"); return; }
        constructor.setAccessible(true);
        Object cursor = constructor.newInstance(2, -1, List.of(0, 17, 1023));
        assertEquals(64, call(cursor, "chunkX")); assertEquals(-32, call(cursor, "chunkZ"));
        call(cursor, "advance");
        assertEquals(81, call(cursor, "chunkX"));
        call(cursor, "advance");
        assertEquals(95, call(cursor, "chunkX")); assertEquals(-1, call(cursor, "chunkZ"));
        call(cursor, "advance");
        assertEquals(true, call(cursor, "done"), "three saved chunks must not turn into 1024 candidate probes");
    }

    @Test
    void fastAdmissionDoesNotRaiseCheckpointFrequencyAndStopCanForcePendingState() throws Exception {
        Class<?> type;
        try { type = Class.forName(getClass().getPackageName() + ".MarketWebMapBackgroundCadence"); }
        catch (ClassNotFoundException exception) { fail("Fast admission and slow persistence need separate cadence"); return; }
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        Object cadence = constructor.newInstance();
        int saves = 0;
        for (long tick = 1; tick <= 40; tick++) {
            if ((boolean) call(cadence, "admissionDue", new Class<?>[]{long.class}, tick)) call(cadence, "markDirty");
            if ((boolean) call(cadence, "checkpointDue", new Class<?>[]{long.class, int.class}, tick, 40)) {
                saves++;
                call(cadence, "checkpointRequested");
            }
        }
        assertEquals(1, saves, "5-tick pipeline activity must not become 4 Hz JSON saves");
        call(cadence, "markDirty");
        assertEquals(false, call(cadence, "checkpointDue", new Class<?>[]{long.class, int.class}, 45L, 40));
        call(cadence, "checkpointRequested"); // shutdown saves immediately rather than waiting for tick 80
        assertEquals(false, call(cadence, "checkpointDue", new Class<?>[]{long.class, int.class}, 80L, 40));
    }

    @Test
    void loadedPriorityClaimSkipsBulkWorkAndSurvivesDeferralUpgradeAndClear() throws Exception {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(4098);
        for (int i = 0; i < 4096; i++) queue.enqueue(WORLD, i, 0, 0L, MarketWebMapTileQuality.SERVER_REGION_SCAN);
        queue.enqueue(WORLD, -1, 0, 1L);
        queue.enqueue(WORLD, -2, 0, 2L);
        Method poll;
        try { poll = queue.getClass().getDeclaredMethod("pollTrackedLoaded", int.class, long.class); }
        catch (NoSuchMethodException exception) { fail("Loaded work needs bounded indexed selection during a full render"); return; }
        @SuppressWarnings("unchecked") List<MarketWebMapRenderQueue.Task> loaded = (List<MarketWebMapRenderQueue.Task>) poll.invoke(queue, 2, 3L);
        assertEquals(List.of(-1, -2), loaded.stream().map(MarketWebMapRenderQueue.Task::chunkX).toList());
        assertEquals(4098, queue.snapshot().size(), "selected foreground tasks remain crash-recoverable claims");
        assertTrue(queue.enqueue(WORLD, -1, 0, 4L));
        assertEquals(List.of(), poll.invoke(queue, 2, 4L), "one coordinate cannot have two active submissions");
        loaded.forEach(queue::acknowledge);
        @SuppressWarnings("unchecked") List<MarketWebMapRenderQueue.Task> retry = (List<MarketWebMapRenderQueue.Task>) poll.invoke(queue, 2, 5L);
        assertEquals(-1, retry.getFirst().chunkX());
        queue.acknowledge(retry.getFirst());
        assertEquals(0, queue.poll(1, 6L).getFirst().chunkX(), "bulk FIFO fairness remains intact");
        queue.clear();
        queue.enqueue(WORLD, 5, 5, 7L);
        queue.enqueue(WORLD, 5, 5, 8L, MarketWebMapTileQuality.SERVER_REGION_SCAN);
        assertEquals(List.of(), poll.invoke(queue, 2, 9L), "a loaded task upgraded to explicit dirty work must leave the loaded index");
        queue.clear();
        assertEquals(List.of(), poll.invoke(queue, 2, 10L));
    }

    private static Object call(Object target, String name) throws Exception { return call(target, name, new Class<?>[0]); }
    private static Object call(Object target, String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameters); method.setAccessible(true);
        return method.invoke(target, arguments);
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
