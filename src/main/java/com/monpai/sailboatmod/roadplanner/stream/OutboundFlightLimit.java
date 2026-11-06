package com.monpai.sailboatmod.roadplanner.stream;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounds packets handed to the transport, including tasks still waiting on its event loop. */
public final class OutboundFlightLimit {
    public static final int PER_PLAYER = 4, GLOBAL = 64;
    private final Map<UUID, Integer> players = new HashMap<>();
    private int total;
    private long generation;

    public synchronized boolean available(UUID player) {
        return player != null && total < GLOBAL && players.getOrDefault(player, 0) < PER_PLAYER;
    }

    public synchronized Permit acquire(UUID player) {
        if (!available(player)) return null;
        players.merge(player, 1, Integer::sum);
        total++;
        return new Permit(this, player, generation);
    }

    private synchronized void release(UUID player, long epoch) {
        if (generation != epoch) return;
        int count = players.getOrDefault(player, 0);
        if (count <= 0) return;
        if (count == 1) players.remove(player); else players.put(player, count - 1);
        total--;
    }

    /** Do not time out live permits: releasing before the actual write would permit unbounded transport backlog. */
    public synchronized void clear() { players.clear(); total = 0; generation++; }
    public synchronized int size() { return total; }

    public static final class Permit implements AutoCloseable {
        private final OutboundFlightLimit owner;
        private final UUID player;
        private final long generation;
        private final AtomicBoolean closed = new AtomicBoolean();
        private Permit(OutboundFlightLimit owner, UUID player, long generation) {
            this.owner = owner; this.player = player; this.generation = generation;
        }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) owner.release(player, generation);
        }
    }
}
