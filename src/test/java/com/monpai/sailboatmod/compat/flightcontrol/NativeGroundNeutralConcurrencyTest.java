package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutral.*;

class NativeGroundNeutralConcurrencyTest {
    @Test void aNativeWorldCallbackCannotBlockAnotherComputersRegistryLifecycle() throws Exception {
        Object first = new Object(), second = new Object(), body = new Object();
        Context context = new Context(new Object(), UUID.randomUUID(), new Object(), new Object(), body, "profile", 1, 1);
        long now = 1_000_000_000L;
        var original = request(first, context, now, true); assertNotNull(original);
        CountDownLatch insideNative = new CountDownLatch(1), releaseNative = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Future<Boolean> physics = workers.submit(() -> onPhysics(first,
                new PhysicsContext(body, "profile", 1, 1, 5, true, true, true), now + 1, .025,
                new NativeActions() {
                    public void stopControlOutput(boolean sync) {
                        insideNative.countDown();
                        try { if (!releaseNative.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test native callback timed out"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    }
                    public void clearLinkedSwivelMountLocks(double dt) { }
                    public void commandLinkedFlapBearingsNeutral(boolean active) { }
                }));
        try {
            assertTrue(insideNative.await(2, TimeUnit.SECONDS));
            Future<Boolean> otherLifecycle = workers.submit(() -> {
                var other = request(second, context, now + 2, true);
                if (other == null) return false;
                release(other);
                release(original);
                return true;
            });
            try { assertTrue(otherLifecycle.get(500, TimeUnit.MILLISECONDS)); }
            catch (TimeoutException blocked) { fail("registry lifecycle is blocked by another computer's native/world callback"); }
        } finally {
            releaseNative.countDown();
            assertTrue(physics.get(2, TimeUnit.SECONDS), "already owned processing remains handled even if released in flight");
            workers.shutdownNow();
        }
        assertFalse(inspect(original, context, now + 3).acknowledged(), "released generation must never be resurrected by late native completion");
    }
}
