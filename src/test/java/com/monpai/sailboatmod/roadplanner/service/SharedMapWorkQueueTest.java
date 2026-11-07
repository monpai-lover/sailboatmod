package com.monpai.sailboatmod.roadplanner.service;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapWorkQueueTest {
    @Test void stalledMainThreadCannotAccumulateUnboundedRequests() {
        MapRequestWorkQueue queue = new MapRequestWorkQueue(4, 2);
        List<Runnable> tasks = new ArrayList<>();
        Function<Runnable, CompletableFuture<Void>> stalled = task -> { tasks.add(task); return new CompletableFuture<>(); };
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(queue.enqueue(first, stalled, () -> {}));
        assertTrue(queue.enqueue(first, stalled, () -> {}));
        for (int i = 0; i < 1000; i++) assertFalse(queue.enqueue(first, stalled, () -> fail("over budget")));
        assertTrue(queue.enqueue(second, stalled, () -> {}));
        assertTrue(queue.enqueue(second, stalled, () -> {}));
        assertFalse(queue.enqueue(UUID.randomUUID(), stalled, () -> fail("global budget")));
        assertEquals(4, tasks.size());
        tasks.forEach(Runnable::run);
        assertEquals(0, queue.size());
        assertTrue(queue.enqueue(first, stalled, () -> {}));
    }

    @Test void dispatchFailureCancellationAndActionFailureReleaseExactlyOnce() {
        MapRequestWorkQueue queue = new MapRequestWorkQueue(1, 1);
        UUID player = UUID.randomUUID();
        assertFalse(queue.enqueue(player, task -> { throw new RejectedExecutionException(); }, () -> {}));
        assertEquals(0, queue.size());
        AtomicInteger calls = new AtomicInteger();
        List<Runnable> tasks = new ArrayList<>();
        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        assertTrue(queue.enqueue(player, task -> { tasks.add(task); return cancelled; }, calls::incrementAndGet));
        cancelled.cancel(false);
        tasks.get(0).run();
        assertEquals(0, calls.get());
        assertEquals(0, queue.size());
        CompletableFuture<Void> failed = new CompletableFuture<>();
        assertTrue(queue.enqueue(player, task -> { tasks.add(task); return failed; }, () -> { throw new IllegalStateException(); }));
        assertThrows(IllegalStateException.class, () -> tasks.get(1).run());
        failed.completeExceptionally(new IllegalStateException());
        assertEquals(0, queue.size());
    }
}
