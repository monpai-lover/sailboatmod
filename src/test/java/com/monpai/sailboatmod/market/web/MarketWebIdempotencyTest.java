package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Idempotency-Key handling for market writes (purchases, listings, buy orders, cancels, wallet). */
class MarketWebIdempotencyTest {
    @Test
    void capacityRejectsNewKeysWithoutEvictingPendingOrCompletedRequests() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000);
        MarketWebIdempotency idem = new MarketWebIdempotency(60_000, clock::get, "busy");
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<MarketWebIdempotency.StoredResponse> pending = pool.submit(() -> idem.execute("player /purchase", "pending", () -> {
            runs.incrementAndGet();
            entered.countDown();
            awaitRelease(release);
            return ok("original order");
        }));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            MarketWebIdempotency.StoredResponse completed = idem.execute("player /purchase", "completed", () -> ok("completed order"));
            for (int index = 2; index < MarketWebIdempotency.MAX_SLOTS; index++) {
                idem.execute("player /purchase", "filler-" + index, () -> ok("filler"));
            }
            AtomicInteger overflowRuns = new AtomicInteger();
            MarketWebIdempotency.StoredResponse overflow = idem.execute("player /purchase", "overflow", () -> ok("unexpected " + overflowRuns.incrementAndGet()));
            idem.execute("player /purchase", "overflow-2", () -> ok("unexpected " + overflowRuns.incrementAndGet()));
            MarketWebIdempotency.StoredResponse duplicate = idem.execute("player /purchase", "pending", () -> ok("duplicate " + runs.incrementAndGet()));
            assertEquals(409, duplicate.status(), "Capacity pressure must retain an in-flight purchase's key");
            assertEquals(1, runs.get());
            assertSame(completed, idem.execute("player /purchase", "completed", () -> ok("duplicate completed order")));
            assertEquals(503, overflow.status());
            assertEquals(0, overflowRuns.get(), "Rejected requests must not run their actions");
            assertEquals(MarketWebIdempotency.MAX_SLOTS, idem.size());
            release.countDown();
            MarketWebIdempotency.StoredResponse result = pending.get(5, TimeUnit.SECONDS);
            assertSame(result, idem.execute("player /purchase", "pending", () -> ok("duplicate after completion")));
            clock.addAndGet(61_000);
            assertEquals(200, idem.execute("player /purchase", "overflow", () -> ok("admitted after expiry")).status());
            assertEquals(1, idem.size());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void pendingRequestDoesNotExpireAndCompletionStartsItsReplayTtl() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000);
        MarketWebIdempotency idem = new MarketWebIdempotency(60_000, clock::get, "busy");
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<MarketWebIdempotency.StoredResponse> pending = pool.submit(() -> idem.execute("player /purchase", "pending", () -> {
            runs.incrementAndGet();
            entered.countDown();
            awaitRelease(release);
            return ok("original order");
        }));
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            clock.addAndGet(61_000);
            MarketWebIdempotency.StoredResponse duplicate = idem.execute("player /purchase", "pending", () -> ok("duplicate " + runs.incrementAndGet()));
            assertEquals(409, duplicate.status(), "TTL applies to completed responses, never to a running action");
            assertEquals(1, runs.get());
            release.countDown();
            MarketWebIdempotency.StoredResponse result = pending.get(5, TimeUnit.SECONDS);
            clock.addAndGet(59_000);
            assertSame(result, idem.execute("player /purchase", "pending", () -> ok("duplicate after completion")));
            clock.addAndGet(2_000);
            idem.execute("player /purchase", "pending", () -> ok("after expiry " + runs.incrementAndGet()));
            assertEquals(2, runs.get());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void writesRunOncePerKeyAndPlayer() throws Exception {
        AtomicLong clock = new AtomicLong(1_000_000);
        MarketWebIdempotency idem = new MarketWebIdempotency(60_000, clock::get, "busy");
        AtomicInteger runs = new AtomicInteger();

        MarketWebIdempotency.StoredResponse first = idem.execute("p1 /purchase", "k1", () -> ok("order-" + runs.incrementAndGet()));
        MarketWebIdempotency.StoredResponse second = idem.execute("p1 /purchase", "k1", () -> ok("order-" + runs.incrementAndGet()));
        check("I1 same key runs once and replays the stored response",
                runs.get() == 1 && new String(second.body(), StandardCharsets.UTF_8).equals("order-1") && first == second,
                "runs=" + runs.get());

        idem.execute("p2 /purchase", "k1", () -> ok("other-" + runs.incrementAndGet()));
        check("I2 the same key from another player is independent", runs.get() == 2, "runs=" + runs.get());

        AtomicInteger noKeyRuns = new AtomicInteger();
        idem.execute("p1 /purchase", null, () -> ok("x" + noKeyRuns.incrementAndGet()));
        idem.execute("p1 /purchase", "  ", () -> ok("x" + noKeyRuns.incrementAndGet()));
        check("I3 requests without a key always run", noKeyRuns.get() == 2, "runs=" + noKeyRuns.get());

        AtomicInteger serverErrors = new AtomicInteger();
        idem.execute("p1 /purchase", "k5", () -> new MarketWebIdempotency.StoredResponse(503, "application/json", new byte[0]));
        idem.execute("p1 /purchase", "k5", () -> ok("retried-" + serverErrors.incrementAndGet()));
        check("I4 5xx is not stored, a retry runs again", serverErrors.get() == 1, "retries=" + serverErrors.get());

        AtomicInteger afterThrow = new AtomicInteger();
        try {
            idem.execute("p1 /purchase", "k6", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            // the action failed; its key must not stay blocked
        }
        idem.execute("p1 /purchase", "k6", () -> ok("after-" + afterThrow.incrementAndGet()));
        check("I5 an exception releases the key", afterThrow.get() == 1, "runs=" + afterThrow.get());

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<MarketWebIdempotency.StoredResponse> slow = pool.submit(() -> idem.execute("p1 /purchase", "k7", () -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ok("slow");
        }));
        entered.await(5, TimeUnit.SECONDS);
        MarketWebIdempotency.StoredResponse duplicate = idem.execute("p1 /purchase", "k7", () -> ok("should-not-run"));
        release.countDown();
        MarketWebIdempotency.StoredResponse slowResult = slow.get(5, TimeUnit.SECONDS);
        MarketWebIdempotency.StoredResponse afterwards = idem.execute("p1 /purchase", "k7", () -> ok("should-not-run"));
        pool.shutdownNow();
        check("I6 a concurrent duplicate gets 409 while the first runs, then the stored result",
                duplicate.status() == 409
                        && new String(duplicate.body(), StandardCharsets.UTF_8).contains("duplicate_request")
                        && new String(slowResult.body(), StandardCharsets.UTF_8).equals("slow")
                        && new String(afterwards.body(), StandardCharsets.UTF_8).equals("slow"),
                "duplicate=" + duplicate.status());

        AtomicInteger expiredRuns = new AtomicInteger();
        idem.execute("p3 /purchase", "k8", () -> ok("a" + expiredRuns.incrementAndGet()));
        clock.addAndGet(61_000);
        idem.execute("p3 /purchase", "k8", () -> ok("b" + expiredRuns.incrementAndGet()));
        check("I7 entries expire after the TTL", expiredRuns.get() == 2, "runs=" + expiredRuns.get());

        String longKey = "x".repeat(MarketWebIdempotency.MAX_KEY_LENGTH + 1);
        AtomicInteger longRuns = new AtomicInteger();
        idem.execute("p1 /purchase", longKey, () -> ok("l" + longRuns.incrementAndGet()));
        idem.execute("p1 /purchase", longKey, () -> ok("l" + longRuns.incrementAndGet()));
        check("I8 oversized keys are ignored (no unbounded memory)", longRuns.get() == 2, "runs=" + longRuns.get());
    }

    private static MarketWebIdempotency.StoredResponse ok(String body) {
        return new MarketWebIdempotency.StoredResponse(200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            release.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void check(String name, boolean condition, String detail) {
        assertTrue(condition, name + " — " + detail);
    }
}
