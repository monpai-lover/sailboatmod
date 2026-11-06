package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapPeriodicRegionScanTest {
    @Test
    void cursorCapacityAndPauseRetainCompletedPeriodicOutput(@TempDir Path directory) throws Exception {
        byte[] header = new byte[8192]; header[2] = 2; header[3] = 1;
        Files.write(directory.resolve("r.999.0.mca"), header);
        MarketWebMapRenderService service = new MarketWebMapRenderService(new MarketWebMapRenderQueue(8));
        Field scannerField = MarketWebMapRenderService.class.getDeclaredField("regionScanner"); scannerField.setAccessible(true);
        Field managerField = MarketWebMapRenderService.class.getDeclaredField("renderManager"); managerField.setAccessible(true);
        MarketWebMapRegionScanService scanner = (MarketWebMapRegionScanService) scannerField.get(service);
        MarketWebMapRenderManager manager = (MarketWebMapRenderManager) managerField.get(service);
        try {
            assertTrue(scanner.requestRepairScan(new MarketWebMapRegionScanService.RepairScanRequest(directory, List.of(), 4)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (scanner.peekRepairRegion() == null && System.nanoTime() < deadline) Thread.yield();
            assertNotNull(scanner.peekRepairRegion());
            Method add = MarketWebMapRenderService.class.getDeclaredMethod("addRegionCursor", int.class, int.class); add.setAccessible(true);
            for (int i = 0; i < 512; i++) { assertEquals(true, add.invoke(service, i, 0)); }
            Method drain = MarketWebMapRenderService.class.getDeclaredMethod("drainRepairScanResults"); drain.setAccessible(true);
            grantTestBudget(manager, 1L); drain.invoke(service);
            assertNotNull(scanner.peekRepairRegion(), "full cursor queues must leave completed output pending");
            Field cursors = MarketWebMapRenderService.class.getDeclaredField("regionCursors"); cursors.setAccessible(true);
            Map<?, ?> queued = (Map<?, ?>) cursors.get(service); queued.remove(queued.keySet().iterator().next());
            manager.pause(); grantTestBudget(manager, 2L); drain.invoke(service);
            assertNotNull(scanner.peekRepairRegion(), "pause must leave completed output pending");
            manager.resume(); grantTestBudget(manager, 3L); drain.invoke(service);
            assertNull(scanner.peekRepairRegion());
            assertEquals(512, queued.size());
        } finally {
            service.stopRegionWatcher(null);
            manager.shutdown();
            ((MarketWebMapRenderManager) managerField.get(service)).shutdown();
        }
    }

    @Test
    void blockedOldWorldScanCannotPublishOrQueueASecondScan(@TempDir Path directory) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread caller = Thread.currentThread();
        MarketWebMapRegionScanService scanner = new MarketWebMapRegionScanService(null, (request, active) -> {
            assertNotSame(caller, Thread.currentThread(), "directory/header IO must run away from the tick caller");
            entered.countDown();
            try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
            catch (InterruptedException exception) { throw new RuntimeException(exception); }
            return List.of(new MarketWebMapRegionScanService.RegionFile(5, 6, List.of(0)));
        });
        var request = new MarketWebMapRegionScanService.RepairScanRequest(directory, List.of(), 4);
        try {
            assertTrue(scanner.requestRepairScan(request));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertFalse(scanner.requestRepairScan(request));
            assertEquals(List.of(), scanner.invalidateAndDrainRepairResults());
            assertFalse(scanner.requestRepairScan(request), "old atomic IO still occupies the sole worker slot");
        } finally { release.countDown(); }
        awaitFinished(scanner);
        assertNull(scanner.peekRepairRegion(), "a late result from the previous generation must be discarded");
    }

    @Test
    void completedOutputRemainsQueuedWhilePausedAndCannotBeOverwritten(@TempDir Path directory) throws Exception {
        MarketWebMapRegionScanService scanner = new MarketWebMapRegionScanService(null,
                (request, active) -> List.of(new MarketWebMapRegionScanService.RegionFile(1, 2, List.of(0))));
        var request = new MarketWebMapRegionScanService.RepairScanRequest(directory, List.of(), 4);
        assertTrue(scanner.requestRepairScan(request));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (scanner.peekRepairRegion() == null && System.nanoTime() < deadline) Thread.yield();
        assertNotNull(scanner.peekRepairRegion());
        MarketWebMapSettings.setUpdatesEnabled(false);
        try {
            assertFalse(scanner.requestRepairScan(request));
            assertNotNull(scanner.peekRepairRegion());
        } finally { MarketWebMapSettings.setUpdatesEnabled(true); }
        var retained = scanner.peekRepairRegion();
        scanner.acceptRepairRegion(retained);
        assertNull(scanner.peekRepairRegion());
    }

    @Test
    void periodicScanRunsOffCallerAndRetainsBoundedResultsUntilAccepted(@TempDir Path directory) throws Exception {
        for (int i = 0; i < 12; i++) {
            byte[] header = new byte[8192];
            header[2] = 2; header[3] = 1;
            Files.write(directory.resolve("r." + i + ".0.mca"), header);
        }
        MarketWebMapRegionScanService scanner = new MarketWebMapRegionScanService(null);
        Object request = request(directory, 4);
        Method submit = method("requestRepairScan", request.getClass());
        Method peek = method("peekRepairRegion");
        assertEquals(true, submit.invoke(scanner, request));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (peek.invoke(scanner) == null && System.nanoTime() < deadline) Thread.yield();
        assertNotNull(peek.invoke(scanner));
        assertEquals(false, submit.invoke(scanner, request), "pending results apply backpressure to new directory scans");
        List<?> retained = (List<?>) method("invalidateAndDrainRepairResults").invoke(scanner);
        assertEquals(4, retained.size());
        assertEquals(0, ((MarketWebMapRegionScanService.RegionFile) retained.getFirst()).regionX());
    }

    @Test
    void scanCancellationDiscardsIncompleteDirectoryPass(@TempDir Path directory) throws Exception {
        AtomicBoolean active = new AtomicBoolean(false);
        Object request = request(directory, 4);
        Method scan = method("scanRepairRegions", request.getClass(), java.util.function.BooleanSupplier.class);
        assertEquals(List.of(), scan.invoke(null, request, (java.util.function.BooleanSupplier) active::get));
    }

    private static Object request(Path directory, int max) throws Exception {
        Class<?> requestType;
        try { requestType = Class.forName(MarketWebMapRegionScanService.class.getName() + "$RepairScanRequest"); }
        catch (ClassNotFoundException exception) { fail("Periodic scans require immutable path/center requests for a bounded worker"); return null; }
        Constructor<?> constructor = requestType.getDeclaredConstructor(Path.class, List.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(directory, List.of(new MarketWebMapRegionScanService.RegionCenter(0, 0)), max);
    }

    private static Method method(String name, Class<?>... parameters) throws Exception {
        Method method = MarketWebMapRegionScanService.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static void grantTestBudget(MarketWebMapRenderManager manager, long id) throws Exception {
        Field budget = MarketWebMapRenderManager.class.getDeclaredField("tickBudget"); budget.setAccessible(true);
        ((MarketWebMapTickBudget) budget.get(manager)).beginTick(id, TimeUnit.SECONDS.toNanos(30), 8, 4);
    }

    private static void awaitFinished(MarketWebMapRegionScanService scanner) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (scanner.pendingRepairWork() != 0 && System.nanoTime() < deadline) Thread.yield();
        assertEquals(0, scanner.pendingRepairWork());
    }
}
