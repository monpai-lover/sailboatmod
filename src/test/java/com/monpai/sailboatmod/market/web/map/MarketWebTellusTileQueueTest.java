package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebTellusTileQueueTest {
    private final Deque<Runnable> worker = new ArrayDeque<>();
    private final List<String> ran = new ArrayList<>();

    private MarketWebTellusTileQueue queue(int capacity) {
        return new MarketWebTellusTileQueue(capacity, worker::add);
    }

    private Runnable job(String name) {
        return () -> ran.add(name);
    }

    private void drain() {
        while (!worker.isEmpty()) {
            worker.poll().run();
        }
    }

    @Test
    void duplicateRequestIsDropped() {
        MarketWebTellusTileQueue q = queue(256);

        assertTrue(q.request(0, 1, 1, job("a")));
        assertFalse(q.request(0, 1, 1, job("again")));
        drain();

        assertEquals(List.of("a"), ran);
        assertTrue(q.request(0, 1, 1, job("later")), "a finished tile can be requested again");
    }

    @Test
    void newestRequestRunsFirst() {
        MarketWebTellusTileQueue q = queue(256);
        q.request(0, 0, 0, job("old"));
        q.request(0, 0, 1, job("new"));

        drain();

        assertEquals(List.of("new", "old"), ran);
    }

    @Test
    void reRequestingAPendingTileMovesItToTheFront() {
        MarketWebTellusTileQueue q = queue(256);
        q.request(0, 0, 0, job("revisited"));
        q.request(0, 0, 1, job("other"));
        assertFalse(q.request(0, 0, 0, job("duplicate")), "still deduplicated");

        drain();

        assertEquals(List.of("revisited", "other"), ran, "the view the player came back to renders first");
    }

    @Test
    void queueDropsOldestBeyond256() {
        MarketWebTellusTileQueue q = queue(256);
        for (int i = 0; i < 300; i++) {
            q.request(0, i, 0, job("t" + i));
        }
        drain();

        assertEquals(256, ran.size());
        assertTrue(ran.contains("t299"));
        assertFalse(ran.contains("t43"), "the oldest 44 requests were dropped");
        assertTrue(ran.contains("t44"));
    }

    @Test
    void burstUsesOneWorkerTaskAndOnlyRetainsTheNewest256Tiles() {
        MarketWebTellusTileQueue q = queue(256);
        for (int i = 0; i < 5_000; i++) {
            q.request(0, i, 0, job("t" + i));
        }

        assertEquals(1, worker.size(), "the executor backlog must stay bounded as requests overflow the tile queue");
        drain();
        assertEquals(256, ran.size());
        assertEquals("t4999", ran.get(0));
        assertEquals("t4744", ran.get(ran.size() - 1));
    }

    @Test
    void requestsWhileAWorkerIsRunningDoNotEnqueueExtraWorkerTasks() {
        MarketWebTellusTileQueue q = queue(256);
        AtomicInteger scheduledWhileRendering = new AtomicInteger();
        q.request(0, -1, 0, () -> {
            ran.add("running");
            for (int i = 0; i < 1_000; i++) {
                q.request(0, i, 0, job("t" + i));
            }
            scheduledWhileRendering.set(worker.size());
        });

        drain();

        assertEquals(0, scheduledWhileRendering.get(), "the active drain already owns work submitted during rendering");
        assertEquals(257, ran.size());
        assertEquals("running", ran.get(0));
        assertEquals("t999", ran.get(1));
        assertEquals("t744", ran.get(ran.size() - 1));
    }

    @Test
    void aNewBurstCanScheduleAWorkerAfterThePreviousDrainFinishes() {
        MarketWebTellusTileQueue q = queue(256);
        q.request(0, 1, 0, job("first"));
        drain();

        assertTrue(q.request(0, 2, 0, job("second")));
        assertEquals(1, worker.size());
        drain();

        assertEquals(List.of("first", "second"), ran);
    }

    @Test
    void failingJobDoesNotStopTheQueue() {
        MarketWebTellusTileQueue q = queue(256);
        q.request(0, 0, 0, job("ok"));
        q.request(0, 0, 1, () -> {
            throw new IllegalStateException("boom");
        });
        drain();

        assertEquals(List.of("ok"), ran);
        assertTrue(q.request(0, 0, 1, job("retry")), "a failed tile can be requested again");
    }

    @Test
    void shutdownStopsAcceptingRequests() {
        MarketWebTellusTileQueue q = queue(256);
        q.shutdown();

        assertFalse(q.request(0, 0, 0, job("x")));
        drain();
        assertTrue(ran.isEmpty());
    }
}
