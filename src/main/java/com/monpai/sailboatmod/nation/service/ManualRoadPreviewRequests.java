package com.monpai.sailboatmod.nation.service;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Server-thread ownership for manual preview callbacks; computation never owns live item state. */
final class ManualRoadPreviewRequests {
    record Context(Object level, String dimension, String target, String mode, Object stack) {
        boolean matches(Context other) {
            return other != null && level == other.level && stack == other.stack
                    && Objects.equals(dimension, other.dimension) && Objects.equals(target, other.target)
                    && Objects.equals(mode, other.mode);
        }
    }
    record Ticket(UUID player, Context context) { }
    private final Map<UUID, Ticket> latest = new HashMap<>();
    private final Map<Object, Ticket> pendingOwners = new IdentityHashMap<>();

    Ticket begin(UUID player, Context context) {
        Ticket ticket = new Ticket(player, context);
        latest.put(player, ticket);
        pendingOwners.put(context.stack(), ticket);
        return ticket;
    }
    void invalidate(UUID player) { latest.remove(player); }

    <T> void watch(Ticket ticket, CompletableFuture<T> future, Executor server,
                   Supplier<Context> currentContext, Runnable clearOwnPending,
                   BiConsumer<T, Throwable> apply) {
        future.whenCompleteAsync((value, error) -> {
            // A retired request may still own another item's pending flag.
            // Identity ownership also protects a newer request on the same item.
            if (pendingOwners.get(ticket.context().stack()) == ticket) {
                pendingOwners.remove(ticket.context().stack());
                clearOwnPending.run();
            }
            if (latest.get(ticket.player()) != ticket) return;
            latest.remove(ticket.player());
            if (ticket.context().matches(currentContext.get())) apply.accept(value, error);
        }, server);
    }
}
