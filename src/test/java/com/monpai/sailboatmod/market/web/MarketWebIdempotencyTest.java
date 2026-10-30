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

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Idempotency-Key handling for market writes (purchases, listings, buy orders, cancels, wallet). */
class MarketWebIdempotencyTest {
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

    private static void check(String name, boolean condition, String detail) {
        assertTrue(condition, name + " — " + detail);
    }
}
