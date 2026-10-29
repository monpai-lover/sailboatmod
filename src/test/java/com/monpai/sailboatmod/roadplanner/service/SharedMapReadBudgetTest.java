package com.monpai.sailboatmod.roadplanner.service;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapReadBudgetTest {
    @Test void droppingOrTimingOutAViewCannotFreeAnUnfinishedSharedReadSlot() {
        SharedMapReadBudget budget = new SharedMapReadBudget(16);
        List<CompletableFuture<Boolean>> started = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            assertNotNull(budget.request(2, index -> { var future = new CompletableFuture<Boolean>(); started.add(future); return future; }));
            // Drop the returned view reference. The underlying IO is still pending and must stay charged.
        }
        assertEquals(16, budget.inFlight());
        assertNull(budget.request(2, index -> { fail("detached pending reads still occupy the sixteen actual IO slots"); return null; }));
        started.getFirst().complete(true);
        assertEquals(15, budget.inFlight());
        assertNotNull(budget.request(1, index -> new CompletableFuture<Boolean>()));
        assertTrue(started.stream().noneMatch(CompletableFuture::isCancelled));
    }
    @Test void closeDoesNotCancelSharedFuturesOrMakeLateReleasesNegative() {
        SharedMapReadBudget budget = new SharedMapReadBudget(2);
        List<CompletableFuture<Boolean>> reads = budget.request(2, index -> new CompletableFuture<Boolean>());
        budget.close();
        assertNull(budget.request(1, index -> { fail("closed budgets cannot launch new IO"); return null; }));
        assertFalse(reads.get(0).isCancelled());
        reads.get(0).complete(true); reads.get(0).complete(false); reads.get(1).completeExceptionally(new IllegalStateException());
        assertEquals(0, budget.inFlight());
    }
}
