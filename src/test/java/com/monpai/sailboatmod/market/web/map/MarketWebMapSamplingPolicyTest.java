package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapSamplingPolicyTest {
    @Test
    void oneAtomicCaptureMayOverrunButPreventsFurtherCaptureAndProbeStarts() throws Exception {
        AtomicLong clock = new AtomicLong();
        Object budget = create("MarketWebMapTickBudget", new Class<?>[]{LongSupplier.class}, (LongSupplier) clock::get);
        call(budget, "beginTick", new Class<?>[]{long.class, long.class, int.class, int.class}, 3L, 2_000_000L, 8, 4);
        assertEquals(true, call(budget, "tryCapture", new Class<?>[0]));
        clock.set(3_000_000L);
        assertEquals(false, call(budget, "tryCapture", new Class<?>[0]));
        assertEquals(false, call(budget, "tryStartProbe", new Class<?>[]{int.class, int.class}, 0, 32));
    }

    @Test
    void sharedNanosecondDeadlineIncludesSubmillisecondCapturesAndDoesNotResetTwice() throws Exception {
        AtomicLong clock = new AtomicLong(100L);
        Object budget = create("MarketWebMapTickBudget", new Class<?>[]{LongSupplier.class}, (LongSupplier) clock::get);
        call(budget, "beginTick", new Class<?>[]{long.class, long.class, int.class, int.class}, 7L, 2_000_000L, 8, 4);
        for (int i = 0; i < 4; i++) {
            assertEquals(true, call(budget, "tryCapture", new Class<?>[0]));
            clock.addAndGet(500_000L);
        }
        assertEquals(false, call(budget, "tryCapture", new Class<?>[0]), "four half-millisecond operations exhaust 2 ms");
        call(budget, "beginTick", new Class<?>[]{long.class, long.class, int.class, int.class}, 7L, 2_000_000L, 8, 4);
        assertEquals(false, call(budget, "tryCapture", new Class<?>[0]), "drain and fullrender must share this tick's deadline");
        call(budget, "beginTick", new Class<?>[]{long.class, long.class, int.class, int.class}, 8L, 2_000_000L, 8, 4);
        assertEquals(true, call(budget, "tryCapture", new Class<?>[0]));
    }

    @Test
    void loadedNorthAndOfflineWorkUseSameCaptureCountAndProbeQuota() throws Exception {
        Object budget = create("MarketWebMapTickBudget", new Class<?>[]{LongSupplier.class}, (LongSupplier) () -> 0L);
        call(budget, "beginTick", new Class<?>[]{long.class, long.class, int.class, int.class}, 1L, 2_000_000L, 2, 1);
        assertEquals(true, call(budget, "tryCapture", new Class<?>[0]), "loaded capture");
        assertEquals(true, call(budget, "tryCapture", new Class<?>[0]), "north capture");
        assertEquals(false, call(budget, "tryCapture", new Class<?>[0]), "offline decode deferred intact");
        assertEquals(true, call(budget, "tryStartProbe", new Class<?>[]{int.class, int.class}, 0, 32));
        assertEquals(false, call(budget, "tryStartProbe", new Class<?>[]{int.class, int.class}, 1, 32));
    }

    @Test
    void completedLoadedCaptureCooldownAllowsDirtyInvalidationImmediately() throws Exception {
        Object cooldown = create("MarketWebMapRefreshCooldown", new Class<?>[]{int.class}, 16);
        call(cooldown, "record", new Class<?>[]{long.class, long.class}, 5L, 100L);
        assertEquals(false, call(cooldown, "ready", new Class<?>[]{long.class, long.class, long.class, boolean.class}, 5L, 101L, 30_000L, false));
        assertEquals(true, call(cooldown, "ready", new Class<?>[]{long.class, long.class, long.class, boolean.class}, 5L, 101L, 30_000L, true));
        assertEquals(true, call(cooldown, "ready", new Class<?>[]{long.class, long.class, long.class, boolean.class}, 5L, 30_100L, 30_000L, false));
    }

    private static Object create(String name, Class<?>[] types, Object... values) throws Exception {
        Class<?> type;
        try {
            type = Class.forName(MarketWebMapSamplingPolicyTest.class.getPackageName() + "." + name);
        } catch (ClassNotFoundException exception) {
            fail("Sampling policy helper is missing: " + name);
            return null;
        }
        Constructor<?> constructor = type.getDeclaredConstructor(types);
        constructor.setAccessible(true);
        return constructor.newInstance(values);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, values);
    }
}
