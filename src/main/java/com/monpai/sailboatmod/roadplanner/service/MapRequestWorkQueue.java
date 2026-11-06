package com.monpai.sailboatmod.roadplanner.service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Bounded admission to the game-thread mailbox, even when that thread stops draining tasks. */
final class MapRequestWorkQueue {
    private final int maxPending;
    private final int maxPerPlayer;
    private final Map<UUID, Integer> players = new HashMap<>();
    private int pending;

    MapRequestWorkQueue(int maxPending, int maxPerPlayer) {
        if (maxPending < 1 || maxPerPlayer < 1) throw new IllegalArgumentException("Invalid mailbox limits");
        this.maxPending = maxPending;
        this.maxPerPlayer = maxPerPlayer;
    }

    boolean enqueue(UUID player, Function<Runnable, CompletableFuture<Void>> dispatcher, Runnable action) {
        if (player == null || dispatcher == null || action == null) return false;
        synchronized (this) {
            if (pending >= maxPending || players.getOrDefault(player, 0) >= maxPerPlayer) return false;
            pending++;
            players.merge(player, 1, Integer::sum);
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (!released.compareAndSet(false, true)) return;
            synchronized (this) {
                pending--;
                players.computeIfPresent(player, (ignored, count) -> count == 1 ? null : count - 1);
            }
        };
        try {
            CompletableFuture<Void> future = dispatcher.apply(() -> {
                if (released.get()) return;
                try { action.run(); }
                finally { release.run(); }
            });
            if (future == null) { release.run(); return false; }
            // Covers dispatch cancellation or failure before the Runnable is reached. Release is idempotent.
            future.whenComplete((ignored, failure) -> release.run());
            return true;
        } catch (RuntimeException rejected) {
            release.run();
            return false;
        }
    }

    synchronized int size() { return pending; }
}
