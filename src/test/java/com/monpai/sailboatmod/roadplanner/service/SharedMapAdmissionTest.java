package com.monpai.sailboatmod.roadplanner.service;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapAdmissionTest {
    @Test void burstAndRefillArePerPlayer() {
        MapRequestLimiter gate = new MapRequestLimiter(4, 2, 8);
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        for (int i = 0; i < 4; i++) assertTrue(gate.allow(one, 0));
        for (int i = 0; i < 100; i++) assertFalse(gate.allow(one, 0));
        assertTrue(gate.allow(two, 0));
        assertTrue(gate.allow(one, 500_000_000L));
        assertFalse(gate.allow(one, 500_000_000L));
    }
    @Test void limiterStateExpiresAndNewIdsCannotEvictAnActiveBudget() {
        MapRequestLimiter gate = new MapRequestLimiter(1, 1, 2);
        UUID one = UUID.randomUUID(), two = UUID.randomUUID(), three = UUID.randomUUID();
        assertTrue(gate.allow(one, 0));
        assertTrue(gate.allow(two, 0));
        assertFalse(gate.allow(three, 0));
        assertFalse(gate.allow(one, 0));
        assertTrue(gate.allow(three, 61_000_000_000L));
        assertEquals(1, gate.size());
    }
    @Test void parallelRequestsCannotOverspendBurst() throws Exception {
        MapRequestLimiter gate = new MapRequestLimiter(16, 40, 8);
        UUID player = UUID.randomUUID();
        AtomicInteger admitted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 1000; i++) pool.submit(() -> { if (gate.allow(player, 0)) admitted.incrementAndGet(); });
        }
        assertEquals(16, admitted.get());
    }
}
