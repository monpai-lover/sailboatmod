package com.monpai.sailboatmod.road.pathfinding;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Request-local cooperative deadline, shared by search, terrain sampling and output processing.
 * Nested windows can shorten the allowance but cannot extend a caller's deadline. This cannot
 * preempt a third-party generator stuck inside one call; checks also run immediately on return.
 */
public final class RoadSearchBudget {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private record Frame(Frame parent, LongSupplier clock, long started, long nanos) { }
    public static final class DeadlineExceeded extends RuntimeException {
        private DeadlineExceeded() { super("Road planning time budget exhausted", null, false, false); }
    }
    private RoadSearchBudget() { }
    public static <T> T run(long millis, Supplier<T> work) { return run(millis, System::nanoTime, work); }
    public static <T> T run(long millis, LongSupplier clock, Supplier<T> work) {
        Objects.requireNonNull(clock); Objects.requireNonNull(work);
        Frame previous = CURRENT.get();
        CURRENT.set(new Frame(previous, clock, clock.getAsLong(), Math.clamp(millis, 1, 600_000) * 1_000_000L));
        try { check(); T value = work.get(); check(); return value; }
        finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
    public static void check() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Road request cancelled");
        for (Frame f = CURRENT.get(); f != null; f = f.parent())
            if (f.clock().getAsLong() - f.started() >= f.nanos()) throw new DeadlineExceeded();
    }
}
