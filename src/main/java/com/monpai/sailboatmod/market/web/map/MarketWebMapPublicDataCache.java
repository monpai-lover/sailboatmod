package com.monpai.sailboatmod.market.web.map;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Server-thread-confined cache for public map metadata only, never permissions or trading authority. */
final class MarketWebMapPublicDataCache<T> {
    private final long ttlNanos;
    private final LongSupplier clock;
    private Object context;
    private T value;
    private long builtAt;

    MarketWebMapPublicDataCache(long ttlNanos, LongSupplier clock) {
        this.ttlNanos = Math.max(1L, ttlNanos);
        this.clock = clock;
    }

    T get(Object requestedContext, Supplier<T> loader) {
        long now = clock.getAsLong();
        long age = now - builtAt;
        if (value == null || context != requestedContext || age < 0 || age >= ttlNanos) {
            T next = loader.get();
            value = next;
            context = requestedContext;
            builtAt = clock.getAsLong();
        }
        return value;
    }
}
