package com.monpai.sailboatmod.route.water;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** A route's cooperative token follows existing Supplier entrypoints without changing their API. */
public final class WaterRouteCancellation {
    private static final ThreadLocal<BooleanSupplier> CURRENT = new ThreadLocal<>();

    private WaterRouteCancellation() { }

    public static boolean cancelled() {
        BooleanSupplier token = CURRENT.get();
        return Thread.currentThread().isInterrupted() || (token != null && token.getAsBoolean());
    }

    public static void check() {
        if (cancelled()) throw new CancellationException("Water route cancelled");
    }

    static <T> T run(BooleanSupplier token, Supplier<T> work) {
        BooleanSupplier previous = CURRENT.get();
        CURRENT.set(token);
        try {
            check();
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
