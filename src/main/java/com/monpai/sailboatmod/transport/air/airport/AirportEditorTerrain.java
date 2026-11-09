package com.monpai.sailboatmod.transport.air.airport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/** Ephemeral FULL terrain retention for an already-authorized airport layout. */
final class AirportEditorTerrain implements AutoCloseable {
    static final int MAX_CHUNKS = 625;
    static final long MAX_LOADING_TICKS = 2400;
    private static final int MAX_SERVER_CHUNKS = 2 * MAX_CHUNKS;
    private static final int MAX_SESSION_ADDITIONS_PER_TICK = 4;
    private static final int MAX_SERVER_ADDITIONS_PER_TICK = 8;
    private static final TicketType<UUID> EDITOR = TicketType.create("sailboatmod_airport_editor", UUID::compareTo);
    private static final Map<MinecraftServer, Budget> SERVER_BUDGETS = new WeakHashMap<>();

    interface Backend {
        long tick();
        boolean full(ChunkPos chunk);
        boolean validHeight(int y);
        void ticket(UUID id, ChunkPos chunk, boolean retain);
    }

    static final class Budget {
        private int reserved, additions;
        private long tick = Long.MIN_VALUE;

        boolean reserve(int count) {
            if (count > MAX_SERVER_CHUNKS - reserved) return false;
            reserved += count;
            return true;
        }

        boolean admit(long now) {
            if (tick != now) { tick = now; additions = 0; }
            if (additions >= MAX_SERVER_ADDITIONS_PER_TICK) return false;
            additions++;
            return true;
        }
    }

    private record Footprint(ChunkPos chunk, int minY) {
        BlockPos atHeight(int y) { return new BlockPos(chunk.getMinBlockX(), y, chunk.getMinBlockZ()); }
    }

    private final Backend backend;
    private final Budget budget;
    private final UUID lease = UUID.randomUUID();
    private List<Footprint> desired;
    private int reserved, retained, additions;
    private long started, lastTick = Long.MIN_VALUE;
    private BlockPos failurePosition;

    static AirportEditorTerrain forLevel(ServerLevel level) {
        Budget budget;
        synchronized (SERVER_BUDGETS) {
            budget = SERVER_BUDGETS.computeIfAbsent(level.getServer(), server -> new Budget());
        }
        return new AirportEditorTerrain(new Backend() {
            @Override public long tick() { return level.getServer().getTickCount(); }
            @Override public boolean full(ChunkPos chunk) { return level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null; }
            @Override public boolean validHeight(int y) { return !level.isOutsideBuildHeight(y); }
            @Override public void ticket(UUID id, ChunkPos chunk, boolean retain) {
                if (retain) level.getChunkSource().addRegionTicket(EDITOR, chunk, 0, id);
                else level.getChunkSource().removeRegionTicket(EDITOR, chunk, 0, id);
            }
        }, budget);
    }

    AirportEditorTerrain(Backend backend, Budget budget) {
        this.backend = backend;
        this.budget = budget;
    }

    @Nullable BlockPos failurePosition() { return failurePosition; }

    /** Called after the entire footprint passed its initial claim check. Never blocks on chunk I/O. */
    String advance(Map<Long, int[]> required) {
        long now = backend.tick();
        if (desired == null) {
            if (required.size() > MAX_CHUNKS) return "too_large";
            var footprint = new ArrayList<Footprint>(required.size());
            for (var entry : required.entrySet()) {
                int minY = entry.getValue()[0], maxY = entry.getValue()[1];
                var point = new Footprint(new ChunkPos(entry.getKey()), minY);
                if (!backend.validHeight(minY) || !backend.validHeight(maxY)) {
                    failurePosition = point.atHeight(backend.validHeight(minY) ? maxY : minY);
                    return "invalid_geometry";
                }
                footprint.add(point);
            }
            desired = List.copyOf(footprint);
            started = now;
        }
        if (desired.isEmpty()) return "ready";
        if (now - started >= MAX_LOADING_TICKS) {
            failurePosition = desired.stream().filter(point -> !backend.full(point.chunk()))
                    .findFirst().map(point -> point.atHeight(point.minY())).orElse(null);
            return "terrain_load_timeout";
        }
        if (reserved == 0) {
            if (!budget.reserve(desired.size())) return "pending";
            reserved = desired.size();
        }
        if (lastTick != now) { lastTick = now; additions = 0; }
        while (retained < desired.size() && additions < MAX_SESSION_ADDITIONS_PER_TICK && budget.admit(now)) {
            Footprint point = desired.get(retained);
            additions++;
            // Count ownership before calling the backend so a throwing add can also be released.
            retained++;
            try { backend.ticket(lease, point.chunk(), true); }
            catch (RuntimeException failure) {
                failurePosition = point.atHeight(point.minY());
                return "terrain_load_failed";
            }
        }
        return retained == desired.size() && desired.stream().allMatch(point -> backend.full(point.chunk())) ? "ready" : "pending";
    }

    @Override public void close() {
        if (reserved == 0) return;
        for (int i = 0; i < retained; i++) backend.ticket(lease, desired.get(i).chunk(), false);
        budget.reserved -= reserved;
        reserved = 0;
        retained = 0;
    }
}
