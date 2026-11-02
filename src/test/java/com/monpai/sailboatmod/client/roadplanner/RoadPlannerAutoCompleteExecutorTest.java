package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerAutoCompleteExecutorTest {
    @Test
    void workRunsOffTheCallerThreadAndANewerRequestSupersedesTheOlderOne() throws Exception {
        UUID player = UUID.randomUUID();
        Thread caller = Thread.currentThread();
        List<String> delivered = new CopyOnWriteArrayList<>();
        List<Thread> workers = new CopyOnWriteArrayList<>();
        CountDownLatch slowStarted = new CountDownLatch(1), done = new CountDownLatch(1);
        RoadPlannerAutoCompleteExecutor.submit(player, () -> {
            workers.add(Thread.currentThread());
            slowStarted.countDown();
            long end = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < end && !Thread.currentThread().isInterrupted()) Thread.onSpinWait();
            return "old";
        }, Runnable::run, delivered::add, () -> fail("busy"));
        assertTrue(slowStarted.await(5, TimeUnit.SECONDS));
        RoadPlannerAutoCompleteExecutor.submit(player, () -> { workers.add(Thread.currentThread()); return "new"; },
                Runnable::run, r -> { delivered.add(r); done.countDown(); }, () -> fail("busy"));
        assertTrue(done.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(List.of("new"), delivered, "only the latest request is delivered; the old one is interrupted");
        assertFalse(workers.contains(caller), "pathfinding never runs on the calling (server) thread");
    }

    @Test
    void requestsFromDifferentPlayersAreIndependent() throws Exception {
        CountDownLatch done = new CountDownLatch(2);
        List<String> delivered = new CopyOnWriteArrayList<>();
        for (String name : List.of("a", "b")) {
            RoadPlannerAutoCompleteExecutor.submit(UUID.randomUUID(), () -> name, Runnable::run,
                    r -> { delivered.add(r); done.countDown(); }, () -> fail("busy"));
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(2, delivered.size());
    }
}
