package com.monpai.sailboatmod.roadplanner.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class SharedMapPredictionQueueTest {
    @Test
    void queueDeduplicatesBoundsBacklogAndSchedulesOnlyOneWorker() throws Exception {
        List<Runnable> workers = new ArrayList<>();
        List<Integer> rendered = new ArrayList<>();
        Object queue = queue(4, workers::add);
        Method request = queue.getClass().getMethod("request", String.class, Runnable.class);
        Method pending = queue.getClass().getMethod("pendingCount");
        for (int i = 0; i < 100; i++) {
            int tile = i;
            request.invoke(queue, "tile-" + i, (Runnable) () -> rendered.add(tile));
        }
        assertEquals(4, pending.invoke(queue));
        assertEquals(1, workers.size(), "bounded pending jobs must not become an unbounded executor backlog");
        assertEquals(false, request.invoke(queue, "tile-99", (Runnable) () -> rendered.add(-1)));
        workers.get(0).run();
        assertEquals(List.of(99, 98, 97, 96), rendered, "the current viewport takes priority over old queued work");
        assertEquals(0, pending.invoke(queue));
        queue.getClass().getMethod("shutdown").invoke(queue);
        assertEquals(false, request.invoke(queue, "closed", (Runnable) () -> fail("closed queue ran work")));
    }

    private static Object queue(int capacity, Executor worker) throws Exception {
        try {
            return Class.forName("com.monpai.sailboatmod.roadplanner.service.SharedMapPredictionQueue")
                    .getConstructor(int.class, Executor.class).newInstance(capacity, worker);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            return fail("Tellus work needs a bounded deduplicated background queue");
        }
    }
}
