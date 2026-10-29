package com.monpai.sailboatmod.transport.physical;

import net.minecraft.server.level.ServerLevel;
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
        void ticket(UUID owner, ChunkPos chunk, boolean retain);
        boolean structure(UUID physicalId, UUID registrationId, boolean retain);
    }
    private static final int MAX_CHUNKS = 128;
    private static final ExecutorService REQUEST_WORKER = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "RegisteredTransport-Chunks"); thread.setDaemon(true); return thread;
    });
    private static final Semaphore REQUEST_SLOTS = new Semaphore(16);
    private final Backend suppliedBackend;
    private Backend backend;
    private ServerLevel level;
    private UUID owner, physical;
    private final Set<ChunkPos> retained = new HashSet<>();
    private final Map<ChunkPos, CompletableFuture<Boolean>> pending = new HashMap<>();
    private final Map<ChunkPos, Long> retryAt = new HashMap<>();
    private long requestTick = Long.MIN_VALUE;
    private int requests;
    private String reason = "waiting_terrain";

    public TransportLoadingWindow() { this(null); }
    public TransportLoadingWindow(Backend backend) { this.suppliedBackend = backend; this.backend = backend; }

    public boolean update(ServerLevel mainLevel, UUID registrationId, UUID physicalId, AABB bounds, Vec3 nextWaypoint) {
        if (owner != null && (!owner.equals(registrationId) || !Objects.equals(physical, physicalId) || level != mainLevel)) close();
        level = mainLevel; owner = registrationId; physical = physicalId;
        if (backend == null && mainLevel != null) backend = new WorldBackend(mainLevel);
        if (backend == null || owner == null || physical == null) { reason = "missing_structure"; return false; }
        Set<ChunkPos> wanted = desiredChunks(bounds, nextWaypoint);
        if (wanted.isEmpty()) { close(); reason = "loading_window_too_large"; return false; }
        if (!backend.structure(physical, owner, true)) { reason = "waiting_structure"; return false; }
        for (ChunkPos chunk : new HashSet<>(retained)) {
            if (!wanted.contains(chunk)) { backend.ticket(owner, chunk, false); retained.remove(chunk); }
        }
        pending.keySet().retainAll(wanted); // Futures belong to the shared chunk system; never cancel them.
        retryAt.keySet().retainAll(wanted);
        long tick = backend.tick();
        if (requestTick != tick) { requestTick = tick; requests = 0; }
        boolean ready = true;
        for (ChunkPos chunk : wanted) {
            if (backend.isLoaded(chunk)) {
                pending.remove(chunk); retryAt.remove(chunk);
                if (retained.add(chunk)) backend.ticket(owner, chunk, true);
                continue;
            }
            ready = false;
            CompletableFuture<Boolean> future = pending.get(chunk);
            if (future != null && future.isDone()) {
                pending.remove(chunk);
                // A successful FULL future can precede getChunkNow visibility by a tick.
                retryAt.put(chunk, tick + (future.isCompletedExceptionally() || !future.getNow(false) ? 20 : 1));
            }
            if (!pending.containsKey(chunk) && retryAt.getOrDefault(chunk, Long.MIN_VALUE) <= tick
                    && requests < 4 && pending.size() < 16) {
                requests++;
                try { pending.put(chunk, backend.request(chunk)); }
                catch (RuntimeException failure) { retryAt.put(chunk, tick + 20); }
            }
        }
        reason = ready ? "" : "waiting_terrain";
        return ready;
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
            if (physical != null) backend.structure(physical, owner, false);
        }
        retained.clear(); pending.clear(); retryAt.clear(); owner = null; physical = null; level = null;
        backend = suppliedBackend; requestTick = Long.MIN_VALUE; requests = 0;
    }

    private record WorldBackend(ServerLevel level) implements Backend {
        public long tick() { return level.getGameTime(); }
        public boolean isLoaded(ChunkPos chunk) { return level.getChunkSource().getChunkNow(chunk.x, chunk.z) != null; }
        public CompletableFuture<Boolean> request(ChunkPos chunk) {
            if (!REQUEST_SLOTS.tryAcquire()) return CompletableFuture.completedFuture(false);
            // On the main thread getChunkFuture itself managedBlocks. Dispatch before calling it.
            return CompletableFuture.supplyAsync(() -> level.getChunkSource().getChunkFuture(chunk.x, chunk.z, ChunkStatus.FULL, true), REQUEST_WORKER)
                    .thenCompose(future -> future).thenApply(result -> result.isSuccess())
                    .whenComplete((result, failure) -> REQUEST_SLOTS.release());
        }
        public void ticket(UUID owner, ChunkPos chunk, boolean retain) {
            ModChunkLoading.forceChunk(level, SailboatMod.MODID, owner, chunk.x, chunk.z, retain, true);
        }
        public boolean structure(UUID physical, UUID owner, boolean retain) { return SableLoadingTickets.update(level, physical, owner, retain); }
    }
}
