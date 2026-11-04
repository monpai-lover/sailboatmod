package com.monpai.sailboatmod.route.water.debug;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class RouteDebugJobManagerTest {
    @Test void submissionReturnsBeforeWorkAndPollingCachesTheHeavyResult() throws Exception {
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var jobs = new RouteDebugJobManager()) {
            var job = jobs.submit(cancelled -> { calls.incrementAndGet(); release.await(); return "x".repeat(1_000_000); });
            assertNotNull(jobs.get(job.jobId()));
            assertNull(jobs.get(job.jobId()).result());
            release.countDown();
            await(() -> jobs.get(job.jobId()).status() == RouteDebugJobManager.Status.SUCCEEDED);
            String cached = jobs.get(job.jobId()).result();
            assertEquals(1_000_000, cached.length());
            assertSame(cached, jobs.get(job.jobId()).result());
            assertEquals(1, calls.get());
        } finally { release.countDown(); }
    }

    @Test void boundsOneWorkerTwoQueuedAndRejectsAdditionalActiveWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var jobs = new RouteDebugJobManager()) {
            jobs.submit(cancelled -> { entered.countDown(); release.await(); return "a"; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            jobs.submit(cancelled -> "b");
            jobs.submit(cancelled -> "c");
            assertThrows(RouteDebugJobManager.BusyException.class, () -> jobs.submit(cancelled -> "d"));
        } finally { release.countDown(); }
    }

    @Test void queuedCancellationNeverRunsAndFreesTheQueueSlot() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var jobs = new RouteDebugJobManager()) {
            jobs.submit(cancelled -> { entered.countDown(); release.await(); return "a"; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var queued = jobs.submit(cancelled -> { calls.incrementAndGet(); return "b"; });
            jobs.submit(cancelled -> "c");
            assertEquals(RouteDebugJobManager.Status.CANCELLED, jobs.cancel(queued.jobId()).status());
            assertNotNull(jobs.submit(cancelled -> "replacement"));
            release.countDown();
            await(() -> jobs.get(queued.jobId()).status() == RouteDebugJobManager.Status.CANCELLED);
            assertEquals(0, calls.get());
        } finally { release.countDown(); }
    }

    @Test void runningCancellationInterruptsAndSignalsTheCooperativeToken() throws Exception {
        var entered = new CountDownLatch(1);
        var observed = new CountDownLatch(1);
        try (var jobs = new RouteDebugJobManager()) {
            var job = jobs.submit(cancelled -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException stopped) { if (cancelled.getAsBoolean()) observed.countDown(); }
                return "must not overwrite cancellation";
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(RouteDebugJobManager.Status.CANCELLED, jobs.cancel(job.jobId()).status());
            assertTrue(observed.await(2, TimeUnit.SECONDS));
            assertNull(jobs.get(job.jobId()).result());
            assertEquals(RouteDebugJobManager.Status.CANCELLED, jobs.get(job.jobId()).status());
        }
    }

    @Test void workIoFailureRemainsFailedWithItsOriginalMessage() throws Exception {
        try (var jobs = new RouteDebugJobManager()) {
            var job = jobs.submit(cancelled -> { throw new IOException("PNG encoding failed"); });
            await(() -> jobs.get(job.jobId()).status() == RouteDebugJobManager.Status.FAILED);
            assertEquals("PNG encoding failed", jobs.get(job.jobId()).message());
            assertNull(jobs.get(job.jobId()).result());
        }
    }

    @Test void workerErrorsBecomeTerminalFailuresAndDoNotBlockTheQueue() throws Exception {
        try (var jobs = new RouteDebugJobManager()) {
            var crashed = jobs.submit(cancelled -> { throw new AssertionError("Renderer fixture crashed"); });
            await(() -> jobs.get(crashed.jobId()).status() == RouteDebugJobManager.Status.FAILED);
            assertEquals("Renderer fixture crashed", jobs.get(crashed.jobId()).message());
            var next = jobs.submit(cancelled -> "still usable");
            await(() -> jobs.get(next.jobId()).status() == RouteDebugJobManager.Status.SUCCEEDED);
        }
    }

    @Test void fakeClockTimesOutWorkAndExpiresRetainedResults() throws Exception {
        var clock = new AtomicLong(1000);
        var entered = new CountDownLatch(1);
        try (var jobs = new RouteDebugJobManager(clock::get, 100, 100)) {
            var job = jobs.submit(cancelled -> { entered.countDown(); new CountDownLatch(1).await(); return "late"; });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            clock.addAndGet(101);
            assertEquals(RouteDebugJobManager.Status.TIMED_OUT, jobs.get(job.jobId()).status());
            clock.addAndGet(101);
            assertNull(jobs.get(job.jobId()));
        }
    }

    @Test void completedRecordsStayBoundedAndOldestTerminalRecordIsEvicted() throws Exception {
        try (var jobs = new RouteDebugJobManager()) {
            String first = null;
            for (int i = 0; i < 5; i++) {
                var job = jobs.submit(cancelled -> "cached");
                if (first == null) first = job.jobId();
                await(() -> jobs.get(job.jobId()).status() == RouteDebugJobManager.Status.SUCCEEDED);
            }
            assertNull(jobs.get(first));
        }
    }

    @Test void closingCancelsWorkAndRejectsNewSubmissions() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var jobs = new RouteDebugJobManager();
        var job = jobs.submit(cancelled -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException stopped) { interrupted.countDown(); }
            return "late";
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        jobs.close();
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertNull(jobs.get(job.jobId()));
        assertThrows(IllegalStateException.class, () -> jobs.submit(cancelled -> "new"));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "background task did not reach its expected state");
    }
}
