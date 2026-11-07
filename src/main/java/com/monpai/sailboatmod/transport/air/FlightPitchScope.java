package com.monpai.sailboatmod.transport.air;

/** Invocation-local eligibility; a null nested invocation must hide an outer eligible one. */
public final class FlightPitchScope<T> {
    public interface Token extends AutoCloseable { @Override void close(); }
    private record Entry<T>(T value, Entry<T> previous) { }
    private final ThreadLocal<Entry<T>> entries = new ThreadLocal<>();
    public T current() { Entry<T> current = entries.get(); return current == null ? null : current.value(); }
    public Token enter(T value) {
        Entry<T> previous = entries.get(), entry = new Entry<>(value, previous);
        Thread owner = Thread.currentThread();
        entries.set(entry);
        return new Token() {
            private boolean closed;
            @Override public void close() {
                if (Thread.currentThread() != owner) throw new IllegalStateException("Scope closed on another thread");
                if (closed) return;
                if (entries.get() != entry) throw new IllegalStateException("Scope close order changed");
                if (previous == null) entries.remove(); else entries.set(previous);
                closed = true;
            }
        };
    }
}
