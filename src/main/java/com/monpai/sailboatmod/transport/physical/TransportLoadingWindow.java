package com.monpai.sailboatmod.transport.physical;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.*;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.*;
import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.util.ModChunkLoading;
import com.monpai.sailboatmod.compat.aeronautics.SableLoadingTickets;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** A bounded moving real-terrain window. The distant route never becomes a world-generation rectangle. */
public final class TransportLoadingWindow implements AutoCloseable {
    public interface Backend {
        long tick();
        boolean isLoaded(ChunkPos chunk);
        CompletableFuture<Boolean> request(ChunkPos chunk);
        /** Admission and its callback are synchronous; a denied attempt must not acquire a lease. */
        default Optional<CompletableFuture<Boolean>> tryRequest(ChunkPos chunk, Runnable onAdmitted) {
            onAdmitted.run();
            return Optional.of(request(chunk));
        }
        void ticket(UUID owner, ChunkPos chunk, boolean retain);
        /** Ephemeral FULL terrain, including provisional cold moving/metadata requests. */
        default void airportTicket(UUID owner, ChunkPos chunk, boolean retain) {
            throw new UnsupportedOperationException("Ephemeral airport tickets unavailable");
        }
        boolean structure(UUID physicalId, UUID registrationId, boolean retain);
    }
    private static final int MAX_CHUNKS = 128;
    public static final int MAX_AIRPORT_CHUNKS = 4096;
    private static final TicketType<UUID> AIRPORT_TERRAIN = TicketType.create("sailboatmod_airport_transport", UUID::compareTo);
    private static final ExecutorService REQUEST_WORKER = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "RegisteredTransport-Chunks"); thread.setDaemon(true); return thread;
    });
    private static final Semaphore REQUEST_SLOTS = new Semaphore(16);
    private final Backend suppliedBackend;
    private Backend backend;
    private ServerLevel level;
    private UUID owner, physical;
    private final Set<ChunkPos> retained = new HashSet<>();
    private final Set<ChunkPos> retainedAirport = new HashSet<>();
    private final Set<ChunkPos> provisionalFullTerrain = new HashSet<>();
    private final Set<ChunkPos> pinnedEndpoints = new LinkedHashSet<>();
    private final Set<ChunkPos> airportPins = new LinkedHashSet<>();
    private Set<ChunkPos> movingWanted = Set.of();
    private boolean airportPinsInvalid;
    private final Map<ChunkPos, CompletableFuture<Boolean>> pending = new HashMap<>();
    private final Map<ChunkPos, Long> retryAt = new HashMap<>();
    private final List<ChunkPos> requestOrder = new ArrayList<>();
    private int requestCursor;
    private long requestTick = Long.MIN_VALUE;
    private int requests;
    private String reason = "waiting_terrain";

    public TransportLoadingWindow() { this(null); }
    public TransportLoadingWindow(Backend backend) { this.suppliedBackend = backend; this.backend = backend; }

    /** Read-only admission for an optional shortcut; never changes leases, pending requests or retry state. */
    public boolean isWindowLoaded(ServerLevel mainLevel, AABB bounds, Vec3 nextWaypoint) {
        Backend probe = suppliedBackend != null ? suppliedBackend : mainLevel == null ? null : new WorldBackend(mainLevel);
        if (probe == null || airportPinsInvalid) return false;
        Set<ChunkPos> moving = new LinkedHashSet<>(desiredChunks(bounds, nextWaypoint));
        if (!moving.isEmpty()) moving.addAll(pinnedEndpoints);
        if (moving.isEmpty() || moving.size() > MAX_CHUNKS) return false;
        moving.addAll(airportPins);
        return moving.stream().allMatch(probe::isLoaded);
    }

    /** Bind before configuring pins, so a replacement body cannot inherit an old owner's lease. */
    public boolean bind(ServerLevel mainLevel, UUID registrationId, UUID physicalId) {
        if (owner != null && (!owner.equals(registrationId) || !Objects.equals(physical, physicalId) || level != mainLevel)) close();
        level = mainLevel; owner = registrationId; physical = physicalId;
        if (backend == null && mainLevel != null) backend = new WorldBackend(mainLevel);
        if (backend == null || owner == null || physical == null) { reason = "missing_structure"; return false; }
        return true;
    }

    public boolean update(ServerLevel mainLevel, UUID registrationId, UUID physicalId, AABB bounds, Vec3 nextWaypoint) {
        if (!bind(mainLevel, registrationId, physicalId)) return false;
        Set<ChunkPos> moving = new LinkedHashSet<>(desiredChunks(bounds, nextWaypoint));
        if (!moving.isEmpty()) moving.addAll(pinnedEndpoints);
        if (moving.isEmpty() || moving.size() > MAX_CHUNKS) { close(); reason = "loading_window_too_large"; return false; }
        movingWanted = Set.copyOf(moving);
        Set<ChunkPos> wanted = new LinkedHashSet<>(moving);
        wanted.addAll(airportPins); // Separate bounds: moving <=128, configured airport union <=4096.
        if (!backend.structure(physical, owner, true)) { reason = "waiting_structure"; return false; }
        for (ChunkPos chunk : new HashSet<>(retained)) {
            if (!moving.contains(chunk)) { backend.ticket(owner, chunk, false); retained.remove(chunk); }
        }
        for (ChunkPos chunk : new HashSet<>(retainedAirport)) {
            if (!airportPins.contains(chunk)) { backend.airportTicket(owner, chunk, false); retainedAirport.remove(chunk); }
        }
        for (ChunkPos chunk : new HashSet<>(provisionalFullTerrain)) {
            if (!wanted.contains(chunk)) releaseProvisional(chunk);
        }
        pending.keySet().retainAll(wanted); // Futures belong to the shared chunk system; never cancel them.
        retryAt.keySet().retainAll(wanted);
        reconcileRequestOrder(wanted);
        long tick = backend.tick();
        if (requestTick != tick) { requestTick = tick; requests = 0; }
        boolean ready = true;
        for (ChunkPos chunk : wanted) {
            if (backend.isLoaded(chunk)) {
                // A throwing handoff keeps its provisional lease, and retries the ordinary ticket
                // before any later update may release that FULL protection.
                if (moving.contains(chunk) && (retained.add(chunk) || provisionalFullTerrain.contains(chunk)))
                    backend.ticket(owner, chunk, true);
                if (airportPins.contains(chunk)) {
                    retainAirport(chunk);
                } else releaseProvisional(chunk); // Moving31 has already taken over.
                pending.remove(chunk); retryAt.remove(chunk);
                continue;
            }
            ready = false;
            CompletableFuture<Boolean> future = pending.get(chunk);
            if (future != null && future.isDone()) {
                if (future.isCompletedExceptionally() || !Boolean.TRUE.equals(future.getNow(false))) {
                    releaseProvisional(chunk);
                    pending.remove(chunk);
                    retryAt.put(chunk, tick + 20);
                }
                // Successful generation may precede getChunkNow visibility. Keep both its lease
                // and local pending slot until actual FULL rather than replaying the request.
            }
        }
        int examined = 0;
        while (examined++ < requestOrder.size() && requests < 4 && pending.size() < 16) {
            ChunkPos chunk = requestOrder.get(requestCursor);
            requestCursor = (requestCursor + 1) % requestOrder.size();
            if (backend.isLoaded(chunk) || pending.containsKey(chunk)
                    || retryAt.getOrDefault(chunk, Long.MIN_VALUE) > tick) continue;
            requests++; // Denials also share the four-attempt budget for this actual tick.
            boolean alreadyProvisional = provisionalFullTerrain.contains(chunk);
            try {
                var accepted = backend.tryRequest(chunk, () -> acquireProvisional(chunk));
                if (accepted.isPresent()) pending.put(chunk, Objects.requireNonNull(accepted.orElseThrow()));
                else if (!alreadyProvisional) releaseProvisional(chunk);
            } catch (RuntimeException failure) {
                try { if (!alreadyProvisional) releaseProvisional(chunk); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); throw failure; }
                retryAt.put(chunk, tick + 20);
            } catch (Error failure) {
                try { if (!alreadyProvisional) releaseProvisional(chunk); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        reason = airportPinsInvalid ? "airport_loading_too_large" : ready ? "" : "waiting_terrain";
        return ready && !airportPinsInvalid;
    }

    public boolean setAirportPins(Collection<ChunkPos> chunks) {
        if (chunks == null || chunks.size() > MAX_AIRPORT_CHUNKS) { rejectAirportPins(); return false; }
        Set<ChunkPos> proposed = new LinkedHashSet<>();
        for (ChunkPos chunk : chunks) {
            if (chunk == null || Math.abs((long)chunk.x) > 1_875_001 || Math.abs((long)chunk.z) > 1_875_001) {
                rejectAirportPins(); return false;
            }
            proposed.add(chunk);
        }
        airportPins.clear(); airportPins.addAll(proposed); airportPinsInvalid = false;
        releaseUnwantedAirportChunks();
        return true;
    }

    public void clearAirportPins() {
        airportPins.clear(); airportPinsInvalid = false;
        releaseUnwantedAirportChunks();
    }

    public void rejectAirportPins() {
        clearAirportPins(); airportPinsInvalid = true; reason = "airport_loading_too_large";
    }

    private void releaseUnwantedAirportChunks() {
        Set<ChunkPos> keep = new HashSet<>(movingWanted); keep.addAll(airportPins);
        for (ChunkPos chunk : new HashSet<>(retainedAirport)) if (!airportPins.contains(chunk)) {
            backend.airportTicket(owner, chunk, false); retainedAirport.remove(chunk);
        }
        for (ChunkPos chunk : new HashSet<>(provisionalFullTerrain)) if (!keep.contains(chunk))
            releaseProvisional(chunk);
        pending.keySet().retainAll(keep); retryAt.keySet().retainAll(keep);
    }

    private void acquireProvisional(ChunkPos chunk) {
        if (retainedAirport.contains(chunk) || !provisionalFullTerrain.add(chunk)) return;
        try { backend.airportTicket(owner, chunk, true); }
        catch (RuntimeException | Error failure) {
            try { releaseProvisional(chunk); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private void releaseProvisional(ChunkPos chunk) {
        if (!provisionalFullTerrain.contains(chunk)) return;
        backend.airportTicket(owner, chunk, false);
        provisionalFullTerrain.remove(chunk);
    }

    private void retainAirport(ChunkPos chunk) {
        if (retainedAirport.contains(chunk)) return;
        if (provisionalFullTerrain.remove(chunk)) {
            retainedAirport.add(chunk); // Same native ticket; no remove/add gap.
            return;
        }
        // Already FULL chunks need retention, not an extra provisional admission slot.
        retainedAirport.add(chunk);
        try { backend.airportTicket(owner, chunk, true); }
        catch (RuntimeException | Error failure) {
            try { backend.airportTicket(owner, chunk, false); retainedAirport.remove(chunk); }
            catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Preserve the next surviving key across reconfiguration; equal pins never reset fairness. */
    private void reconcileRequestOrder(Set<ChunkPos> wanted) {
        if (requestOrder.size() == wanted.size() && wanted.containsAll(requestOrder)) return;
        ChunkPos next = null;
        for (int i = 0; i < requestOrder.size(); i++) {
            ChunkPos candidate = requestOrder.get((requestCursor + i) % requestOrder.size());
            if (wanted.contains(candidate)) { next = candidate; break; }
        }
        requestOrder.removeIf(chunk -> !wanted.contains(chunk));
        Set<ChunkPos> present = new HashSet<>(requestOrder);
        for (ChunkPos chunk : wanted) if (present.add(chunk)) requestOrder.add(chunk);
        requestCursor = next == null ? 0 : requestOrder.indexOf(next);
    }

    /** At most two airport metadata chunks share this window's owner, ticket lifecycle and total budget. */
    public void setPinnedEndpoints(Collection<BlockPos> endpoints) {
        if (endpoints == null || endpoints.size() > 2) throw new IllegalArgumentException("Invalid endpoint pins");
        Set<ChunkPos> proposed = new LinkedHashSet<>();
        for (BlockPos endpoint : endpoints) {
            if (endpoint == null || Math.abs((long)endpoint.getX()) > 30_000_000 || Math.abs((long)endpoint.getZ()) > 30_000_000)
                throw new IllegalArgumentException("Invalid endpoint pin");
            proposed.add(new ChunkPos(endpoint));
        }
        pinnedEndpoints.clear(); pinnedEndpoints.addAll(proposed);
    }

    public String reason() { return reason; }

    public static Set<ChunkPos> desiredChunks(AABB bounds, Vec3 nextWaypoint) {
        if (bounds == null || nextWaypoint == null || !Double.isFinite(bounds.minX) || !Double.isFinite(bounds.maxX)
                || !Double.isFinite(bounds.minZ) || !Double.isFinite(bounds.maxZ)
                || bounds.minX < Integer.MIN_VALUE + 32.0 || bounds.minZ < Integer.MIN_VALUE + 32.0
                || bounds.maxX > Integer.MAX_VALUE - 32.0 || bounds.maxZ > Integer.MAX_VALUE - 32.0
                || !Double.isFinite(nextWaypoint.x) || !Double.isFinite(nextWaypoint.z)) return Set.of();
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        if (!addRectangle(chunks, bounds.minX - 16, bounds.minZ - 16, bounds.maxX + 16, bounds.maxZ + 16)) return Set.of();
        Vec3 center = bounds.getCenter();
        double dx = nextWaypoint.x - center.x, dz = nextWaypoint.z - center.z;
        double distance = Math.hypot(dx, dz), reach = Math.min(160, distance);
        if (distance > 0.001) {
            int steps = (int) Math.ceil(reach / 16);
            for (int i = 1; i <= steps; i++) {
                double length = Math.min(reach, i * 16.0), x = center.x + dx / distance * length, z = center.z + dz / distance * length;
                if (!addRectangle(chunks, x - 16, z - 16, x + 16, z + 16)) return Set.of();
            }
        }
        return Collections.unmodifiableSet(chunks);
    }

    private static boolean addRectangle(Set<ChunkPos> result, double minX, double minZ, double maxX, double maxZ) {
        int x0 = (int) Math.floor(minX / 16), x1 = (int) Math.floor(maxX / 16);
        int z0 = (int) Math.floor(minZ / 16), z1 = (int) Math.floor(maxZ / 16);
        if (((long) x1 - x0 + 1) * ((long) z1 - z0 + 1) > MAX_CHUNKS) return false;
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            result.add(new ChunkPos(x, z)); if (result.size() > MAX_CHUNKS) return false;
        }
        return true;
    }

    @Override public void close() {
        if (backend != null && owner != null) {
            for (ChunkPos chunk : retained) backend.ticket(owner, chunk, false);
            for (ChunkPos chunk : retainedAirport) backend.airportTicket(owner, chunk, false);
            for (ChunkPos chunk : provisionalFullTerrain) backend.airportTicket(owner, chunk, false);
            if (physical != null) backend.structure(physical, owner, false);
        }
        retained.clear(); retainedAirport.clear(); provisionalFullTerrain.clear(); pinnedEndpoints.clear(); airportPins.clear(); movingWanted = Set.of(); airportPinsInvalid = false;
        pending.clear(); retryAt.clear(); owner = null; physical = null; level = null;
        requestOrder.clear(); requestCursor = 0;
        backend = suppliedBackend; requestTick = Long.MIN_VALUE; requests = 0;
    }

    private record WorldBackend(ServerLevel level) implements Backend {
        public long tick() { return level.getGameTime(); }
        public boolean isLoaded(ChunkPos chunk) { return level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null; }
        public CompletableFuture<Boolean> request(ChunkPos chunk) {
            return tryRequest(chunk, () -> { }).orElseGet(() -> CompletableFuture.completedFuture(false));
        }
        @Override public Optional<CompletableFuture<Boolean>> tryRequest(ChunkPos chunk, Runnable onAdmitted) {
            if (!REQUEST_SLOTS.tryAcquire()) return Optional.empty();
            var held = new java.util.concurrent.atomic.AtomicBoolean(true);
            Runnable release = () -> { if (held.compareAndSet(true, false)) REQUEST_SLOTS.release(); };
            try {
                onAdmitted.run(); // Server thread: own FULL33 lease precedes any native scheduling.
                // getChunkFuture itself managedBlocks on the main thread, so keep native work here.
                return Optional.of(CompletableFuture.supplyAsync(
                                () -> level.getChunkSource().getChunkFuture(chunk.x, chunk.z, ChunkStatus.FULL, true), REQUEST_WORKER)
                        .thenCompose(future -> future).thenApply(result -> result.isSuccess())
                        .whenComplete((result, failure) -> release.run()));
            } catch (RuntimeException | Error failure) {
                release.run();
                throw failure;
            }
        }
        public void ticket(UUID owner, ChunkPos chunk, boolean retain) {
            ModChunkLoading.forceChunk(level, SailboatMod.MODID, owner, chunk.x, chunk.z, retain, true);
        }
        public void airportTicket(UUID owner, ChunkPos chunk, boolean retain) {
            // Distance 0 is native FULL (33), sufficient for terrain reads. Unlike forceChunk,
            // region tickets are not saved; moving/metadata ticks retain their existing policy.
            if (retain) level.getChunkSource().addRegionTicket(AIRPORT_TERRAIN, chunk, 0, owner);
            else level.getChunkSource().removeRegionTicket(AIRPORT_TERRAIN, chunk, 0, owner);
        }
        public boolean structure(UUID physical, UUID owner, boolean retain) { return SableLoadingTickets.update(level, physical, owner, retain); }
    }
}
