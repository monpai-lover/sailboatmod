package com.monpai.sailboatmod.transport.board;

import static com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.*;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import com.mojang.logging.LogUtils;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Observational boundary: malformed board metadata must never interrupt transport movement. */
public final class TownVoyagePublisher {
    private static final PendingEndpoints PENDING = new PendingEndpoints();
    private static int warnings;
    private TownVoyagePublisher() { }
    private static void safely(ServerLevel level, Consumer<TownVoyageService> action) {
        if (level == null) return;
        try { TownVoyageService.existing(level.getServer()).ifPresent(action); }
        catch (RuntimeException ignored) { badMetadata(); }
    }
    private static void badMetadata() {
        if (warnings < 8) {
            warnings++;
            LogUtils.getLogger().warn("Skipped invalid town arrivals metadata; transport movement is unaffected");
        }
    }
    private static long now(ServerLevel level) { return Math.max(0, level.getServer().overworld().getGameTime()); }
    public static void observe(ServerLevel level, Observation observation) { safely(level, service -> service.publish(observation, now(level))); }
    public static void observeAir(ServerLevel level, Observation observation, RailAircraftPath path, double distance, int stand) {
        safely(level, service -> {
            long now = now(level); service.publish(observation, now);
            if (observation != null) service.requestAirEta(observation.key(), observation.geometryId(), path, distance, stand, now);
        });
    }
    public static void retire(ServerLevel level, CarrierKey carrier, UUID leg) { safely(level, service -> service.retire(carrier, leg)); }
    public static void retireCurrent(ServerLevel level, CarrierKey carrier) {
        safely(level, service -> service.currentKey(carrier).ifPresent(key -> service.retire(carrier, key.legId())));
    }
    public static void markUnloaded(ServerLevel level, CarrierKey carrier) { safely(level, service -> service.markUnloaded(carrier)); }
    public static void observeEndpoint(ServerLevel level, BlockPos pos, String townId, String displayName) {
        if (level == null || pos == null) return;
        try {
            MinecraftServer server = level.getServer();
            Endpoint endpoint = new Endpoint(level.dimension().location().toString(), pos.asLong(), townId == null ? "" : townId, displayName);
            Optional<TownVoyageService> service = TownVoyageService.existing(server);
            if (service.isPresent()) service.get().observeEndpoint(endpoint, true); else PENDING.observe(server, endpoint);
        } catch (RuntimeException ignored) { badMetadata(); }
    }
    public static void markEndpointUnloaded(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return;
        try {
            MinecraftServer server = level.getServer(); String dimension = level.dimension().location().toString();
            Optional<TownVoyageService> service = TownVoyageService.existing(server);
            if (service.isPresent()) service.get().markEndpointUnloaded(dimension, pos.asLong()); else PENDING.unloaded(server, dimension, pos.asLong());
        } catch (RuntimeException ignored) { badMetadata(); }
    }
    public static void markEndpointRemoved(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return;
        try {
            MinecraftServer server = level.getServer(); String dimension = level.dimension().location().toString();
            Optional<TownVoyageService> service = TownVoyageService.existing(server);
            if (service.isPresent()) service.get().markEndpointRemoved(dimension, pos.asLong()); else PENDING.removed(server, dimension, pos.asLong());
        } catch (RuntimeException ignored) { badMetadata(); }
    }
    public static Endpoint endpoint(ServerLevel level, BlockPos pos, String name) {
        if (level == null || pos == null) return Endpoint.unknown();
        try {
            String dimension = level.dimension().location().toString();
            return TownVoyageService.existing(level.getServer()).map(service -> service.endpoint(dimension, pos.asLong(), name))
                    .orElseGet(() -> new Endpoint(dimension, pos.asLong(), "", name));
        } catch (RuntimeException ignored) { badMetadata(); return Endpoint.unknown(); }
    }
    public static boolean reserveGroundGeometry(ServerLevel level, CarrierKey carrier, String geometry, int points) {
        if (level == null) return false;
        try { return TownVoyageService.existing(level.getServer()).map(service -> service.reserveGroundGeometry(carrier, geometry, points)).orElse(false); }
        catch (RuntimeException ignored) { badMetadata(); return false; }
    }
    public static void releaseGroundGeometry(ServerLevel level, CarrierKey carrier) { safely(level, service -> service.releaseGroundGeometry(carrier)); }
    public static State state(boolean paused, String reason) {
        if (paused) return State.PAUSED;
        String publicReason = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (publicReason.contains("fuel") || publicReason.contains("power")) return State.FUEL_BLOCKED;
        if (publicReason.contains("terrain") || publicReason.contains("chunk")) return State.WAITING_TERRAIN;
        if (publicReason.contains("recover")) return State.RECOVERING;
        if (publicReason.contains("resource") || publicReason.contains("material")) return State.WAITING_RESOURCE;
        return State.MOVING;
    }
    static void drain(MinecraftServer server, TownVoyageService.Index target) { PENDING.open(server); PENDING.drain(server, target); }
    static void discard(MinecraftServer server) { PENDING.discard(server); }

    /** Startup metadata only; no Level, block entity, transport, path, or business objects are retained. */
    static final class PendingEndpoints {
        private record Address(String dimension, long pos) { }
        private record Pending(Endpoint endpoint, boolean loaded, boolean removed, boolean observedMetadata) { }
        private static final class Buffer {
            final LinkedHashMap<Address, Pending> entries = new LinkedHashMap<>();
            boolean uncertain;
        }
        private final Map<Object, Buffer> sessions = new IdentityHashMap<>();
        private final Map<Object, Boolean> stopped = new WeakHashMap<>();
        synchronized void open(Object session) { stopped.remove(session); }
        synchronized void observe(Object session, Endpoint endpoint) {
            if (session == null || endpoint == null || endpoint.dimensionId().isEmpty() || stopped.containsKey(session)) return;
            put(session, new Address(endpoint.dimensionId(), endpoint.terminalPos()), new Pending(endpoint, true, false, true));
        }
        synchronized void unloaded(Object session, String dim, long pos) {
            if (session == null || stopped.containsKey(session)) return;
            Address address = new Address(dim, pos);
            Buffer buffer = sessions.get(session);
            Pending old = buffer == null ? null : buffer.entries.get(address);
            if (old != null) put(session, address, new Pending(old.endpoint(), false, old.removed(), old.observedMetadata()));
        }
        synchronized void removed(Object session, String dim, long pos) {
            if (session == null || stopped.containsKey(session)) return;
            Address address = new Address(dim, pos);
            Buffer buffer = sessions.get(session);
            Pending old = buffer == null ? null : buffer.entries.get(address);
            Endpoint endpoint = old == null ? new Endpoint(dim, pos, "", "Unavailable") : old.endpoint();
            put(session, address, new Pending(endpoint, false, true, old != null && old.observedMetadata()));
        }
        private void put(Object session, Address address, Pending entry) {
            Buffer buffer = sessions.computeIfAbsent(session, unused -> new Buffer());
            if (!buffer.entries.containsKey(address) && buffer.entries.size() >= 8192) {
                // An authority event may have been lost; no restored endpoint may retain implicit trust.
                buffer.uncertain = true;
                return;
            }
            buffer.entries.put(address, entry);
        }
        synchronized void drain(Object session, TownVoyageService.Index target) {
            Buffer buffer = sessions.remove(session);
            if (buffer == null) return;
            if (buffer.uncertain) target.invalidateCatalog();
            for (Pending pending : buffer.entries.values()) {
                // Removal placeholders carry an address only; they cannot overwrite restored last-known metadata.
                if (pending.observedMetadata()) target.observeEndpoint(pending.endpoint(), pending.loaded());
                if (pending.removed()) target.markEndpointRemoved(pending.endpoint().dimensionId(), pending.endpoint().terminalPos());
            }
        }
        synchronized void discard(Object session) { sessions.remove(session); if (session != null) stopped.put(session, true); }
        synchronized int size(Object session) { Buffer buffer = sessions.get(session); return buffer == null ? 0 : buffer.entries.size(); }
    }
}
