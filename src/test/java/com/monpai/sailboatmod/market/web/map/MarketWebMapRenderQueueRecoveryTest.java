package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapRenderQueueRecoveryTest {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    void trackedPollKeepsAcceptedWorkVisibleUntilSubmissionAcknowledgesIt() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(2);
        queue.enqueue(OVERWORLD, 1, 2, 0);
        var task = queue.pollTracked(1, 1).getFirst();
        assertEquals(0, queue.size());
        assertEquals(List.of(task), queue.snapshot(), "poll-to-submit gap must remain recoverable");
        queue.acknowledge(task);
        assertTrue(queue.snapshot().isEmpty());
    }

    @Test
    void trackedWorkCountsTowardCapacityAndDeferredWorkCanRequeueWithoutALossWindow() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(1);
        queue.enqueue(OVERWORLD, 1, 2, 0);
        var first = queue.pollTracked(1, 1).getFirst();
        assertEquals(0, queue.remainingCapacity());
        assertFalse(queue.enqueue(OVERWORLD, 9, 9, 1));
        assertTrue(queue.enqueue(OVERWORLD, first.chunkX(), first.chunkZ(), 2, first.quality()));
        assertEquals(1, queue.snapshot().size());
        assertTrue(queue.pollTracked(1, 2).isEmpty(), "the same key cannot have two active submissions");
        queue.acknowledge(first);
        assertEquals(1, queue.snapshot().size(), "ack must retain deferred queued work");
        var second = queue.pollTracked(1, 3).getFirst();
        queue.acknowledge(first);
        assertEquals(List.of(second), queue.snapshot(), "a stale acknowledgement must not release a newer claim");
        queue.acknowledge(second);
        assertEquals(1, queue.remainingCapacity());
    }

    @Test
    void clearDropsClaimsAndOrdinaryPollKeepsItsExistingSemantics() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(2);
        queue.enqueue(OVERWORLD, 1, 0, 0);
        queue.pollTracked(1, 0);
        queue.enqueue(OVERWORLD, 2, 0, 0);
        queue.clear();
        assertTrue(queue.snapshot().isEmpty());
        assertEquals(2, queue.remainingCapacity());
        queue.enqueue(OVERWORLD, 3, 0, 0);
        queue.poll(1, 0);
        assertTrue(queue.snapshot().isEmpty());
    }
}
