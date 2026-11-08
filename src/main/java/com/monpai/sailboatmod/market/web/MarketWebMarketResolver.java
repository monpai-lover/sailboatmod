package com.monpai.sailboatmod.market.web;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.market.MarketRegistry;
import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData;
import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData.MarketTerminalEntry;
import com.monpai.sailboatmod.route.water.PortAsyncRuntime;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.io.EOFException;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.zip.ZipException;

/** Server-thread-owned market validation and activation. Disk and chunk futures are
 * retained between requests, with bounded work and private cancellation/deadlines. */
public final class MarketWebMarketResolver implements AutoCloseable {
    private static final int MAX_PENDING = 32;
    private static final int MAX_LOADS = 2;
    private static final int MAX_RECORDS = 256;
    private static final long TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long RETRY_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long VERIFIED_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final int MAX_LEASES = 32;
    private static final int LEASE_TICKS = 200;
    private static final TicketType<UUID> MARKET_WEB_TICKET = TicketType.create(
            "sailboatmod_market_web", Comparator.<UUID>naturalOrder(), LEASE_TICKS);
    private final Map<MarketKey, Request> requests = new LinkedHashMap<>();
    private final Map<MarketKey, Lease> leases = new LinkedHashMap<>();
    private final Map<MarketKey, DiskProbe> diskProbes = new ConcurrentHashMap<>();
    private final Map<MarketKey, Activation> activations = new ConcurrentHashMap<>();
    private final Runnable invalidated;
    private final Semaphore probeSlots = new Semaphore(MAX_PENDING);
    private final Semaphore loadSlots = new Semaphore(MAX_LOADS);
    private MinecraftServer owner;
    private volatile boolean closed;
    private int pending;
    private long nextCatalogTick = Long.MIN_VALUE;
    private List<MarketTerminalEntry> catalog = List.of();
    private int catalogCursor;

    private enum Phase { PROBING, VERIFIED, LOADING, UNAVAILABLE }
    enum SavedMarketStatus { PRESENT, MISSING, UNAVAILABLE }

    // Explicit coordinates retain Tellus heights beyond vanilla packed BlockPos limits.
    private record MarketKey(String dimension, BlockPos position) { }
    private record Lease(MarketKey key, ServerLevel level, ChunkPos chunk, UUID ticket, long expiresAt) { }
    private record DiskProbe(ServerLevel level, CompletableFuture<Optional<CompoundTag>> future) { }
    private record Activation(ServerLevel level, CompletableFuture<Boolean> future) { }

    private static final class Request {
        final MarketKey key;
        final MarketTerminalEntry registration;
        final ServerLevel level;
        Phase phase = Phase.UNAVAILABLE;
        CompletableFuture<?> task;
        long started;
        long retryAt;
        long verifiedUntil;
        boolean loadWanted;
        volatile boolean invalidated;
        String reason = "market_loading";

        Request(MarketKey key, MarketTerminalEntry registration, ServerLevel level) {
            this.key = key;
            this.registration = registration;
            this.level = level;
        }
    }

    public static final class UnavailableException extends RuntimeException {
        private final String reason;
        private final int retryAfterSeconds;

        public UnavailableException(String reason, String message, int retryAfterSeconds) {
            super(message);
            this.reason = reason;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public String reason() { return reason; }
        public int retryAfterSeconds() { return retryAfterSeconds; }
    }

    public MarketWebMarketResolver(Runnable invalidated) {
        this.invalidated = invalidated;
    }

    public MarketWebService.ResolvedMarket resolve(MinecraftServer server, String dimension, BlockPos position) {
        requireServer(server);
        MarketTerminalSavedData terminals = MarketTerminalSavedData.get(server.overworld());
        BlockPos canonical = terminals.canonicalPosition(dimension, position);
        MarketTerminalEntry registration = terminals.getEntry(dimension, canonical);
        if (registration == null) return null;
        ServerLevel level = level(server, dimension);
        if (level == null || level.isOutsideBuildHeight(canonical)) throw unavailable("market_dimension_unavailable");
        Request request = request(registration, level);
        LevelChunk loaded = level.getChunkSource().getChunkNow(canonical.getX() >> 4, canonical.getZ() >> 4);
        if (loaded != null) {
            MarketWebService.ResolvedMarket resolved = resolveLoaded(request, loaded);
            if (resolved != null) retainLease(request);
            return resolved;
        }
        request.loadWanted = true;
        if (terminals.hasUnverifiedPosition(dimension, canonical)) throw unavailable("market_position_unverified");
        if (leases.containsKey(request.key)) retainLease(request);
        advance(request, System.nanoTime());
        throw unavailable(request.reason);
    }

    /** Catalog queries inspect already-loaded chunks and schedule validation only. */
    public boolean isLoaded(MinecraftServer server, String dimension, BlockPos position) {
        if (!bind(server)) return false;
        MarketTerminalEntry registration = MarketTerminalSavedData.get(server.overworld()).getEntry(dimension, position);
        ServerLevel level = level(server, dimension);
        if (registration == null || level == null || level.isOutsideBuildHeight(position)) return false;
        LevelChunk loaded = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        if (loaded == null) return false;
        try { return resolveLoaded(request(registration, level), loaded) != null; }
        catch (UnavailableException unavailable) { return false; }
    }

    public void tick(MinecraftServer server) {
        if (!bind(server)) return;
        for (Lease lease : List.copyOf(leases.values())) {
            if (server.getTickCount() >= lease.expiresAt || server.getLevel(lease.level.dimension()) != lease.level) {
                releaseLease(lease.key);
                Request request = requests.get(lease.key);
                if (request != null) request.loadWanted = false;
            }
        }
        long now = System.nanoTime();
        int started = 0;
        for (Request request : List.copyOf(requests.values())) {
            if (!current(request)) { discard(request); continue; }
            if (request.task != null && now - request.started >= TIMEOUT_NANOS) {
                unavailable(request, "market_probe_timeout");
            }
            if (request.loadWanted && request.task == null && started < 2) {
                if (advance(request, now)) started++;
            }
        }
        validateCatalog(server);
    }

    /** Two registrations per second keep catalog cleanup independent of catalog size. */
    public void validateCatalog(MinecraftServer server) {
        if (!bind(server) || server.getTickCount() < nextCatalogTick) return;
        nextCatalogTick = (long) server.getTickCount() + 20;
        MarketTerminalSavedData terminals = MarketTerminalSavedData.get(server.overworld());
        if (catalogCursor >= catalog.size()) {
            catalog = terminals.entries();
            catalogCursor = 0;
        }
        for (int checked = 0; checked < 2 && catalogCursor < catalog.size(); checked++) {
            MarketTerminalEntry entry = catalog.get(catalogCursor++);
            if (terminals.getEntry(entry.dimensionId(), entry.marketPos()) != entry) continue;
            ServerLevel level = level(server, entry.dimensionId());
            if (level == null || level.isOutsideBuildHeight(entry.marketPos())) continue;
            Request request = request(entry, level);
            LevelChunk loaded = level.getChunkSource().getChunkNow(entry.marketPos().getX() >> 4, entry.marketPos().getZ() >> 4);
            if (loaded != null) {
                try { resolveLoaded(request, loaded); }
                catch (UnavailableException unavailable) { /* Unverified legacy coordinates remain registered. */ }
            } else if (!terminals.hasUnverifiedPosition(entry.dimensionId(), entry.marketPos())) {
                long now = System.nanoTime();
                if (request.task == null && now >= request.retryAt && now >= request.verifiedUntil) startProbe(request);
            }
        }
    }

    private boolean bind(MinecraftServer server) {
        if (closed || server == null || server.isStopped() || !server.isSameThread()) return false;
        if (owner == null) owner = server;
        return owner == server;
    }

    private void requireServer(MinecraftServer server) {
        if (!bind(server)) throw unavailable("market_server_unavailable");
    }

    private static ServerLevel level(MinecraftServer server, String dimension) {
        ResourceLocation location = ResourceLocation.tryParse(dimension);
        return location == null ? null : server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, location));
    }

    private Request request(MarketTerminalEntry entry, ServerLevel level) {
        MarketKey key = new MarketKey(entry.dimensionId(), entry.marketPos().immutable());
        Request previous = requests.get(key);
        if (previous != null && previous.registration == entry && previous.level == level) return previous;
        if (previous != null) discard(previous);
        if (requests.size() >= MAX_RECORDS) {
            Request oldest = requests.values().stream().filter(candidate -> candidate.task == null).findFirst().orElse(null);
            if (oldest == null) throw unavailable("market_probe_busy");
            discard(oldest);
        }
        Request request = new Request(key, entry, level);
        requests.put(key, request);
        return request;
    }

    private boolean advance(Request request, long now) {
        if (request.task != null || now < request.retryAt) return false;
        if (request.phase == Phase.VERIFIED && now < request.verifiedUntil) return startLoad(request);
        return startProbe(request);
    }

    private boolean startProbe(Request request) {
        if (pending >= MAX_PENDING) { request.reason = "market_probe_busy"; return false; }
        if (MarketTerminalSavedData.get(owner.overworld()).hasUnverifiedPosition(request.key.dimension, request.key.position)) {
            unavailable(request, "market_position_unverified");
            return false;
        }
        DiskProbe probe = diskProbes.get(request.key);
        if (probe != null && probe.level != request.level) { request.reason = "market_probe_busy"; return false; }
        if (probe == null) {
            if (!probeSlots.tryAcquire()) { request.reason = "market_probe_busy"; return false; }
            CompletableFuture<Optional<CompoundTag>> disk;
            try { disk = request.level.getChunkSource().chunkMap.read(new ChunkPos(request.key.position)); }
            catch (RuntimeException failure) {
                probeSlots.release();
                unavailable(request, "market_probe_failed");
                return false;
            }
            probe = new DiskProbe(request.level, disk);
            diskProbes.put(request.key, probe);
            DiskProbe admitted = probe;
            disk.whenComplete((raw, failure) -> {
                diskProbes.remove(request.key, admitted);
                probeSlots.release();
            });
        }
        request.phase = Phase.PROBING;
        request.reason = "market_loading";
        // Only this dependent owns a deadline. The original vanilla read remains
        // deduplicated and occupies its slot until actual IO completion.
        observe(request, probe.future.thenApply(raw -> raw), (raw, failure) -> completeProbe(request, raw, failure));
        return true;
    }

    private void completeProbe(Request request, Optional<CompoundTag> raw, Throwable failure) {
        if (resolveLoadedIfPresent(request)) return;
        if (failure != null) {
            if (isStructuralCorruption(failure)) prune(request);
            else unavailable(request, "market_probe_failed");
            return;
        }
        SavedMarketStatus status = inspectSavedMarket(raw, request.key.position);
        if (status == SavedMarketStatus.MISSING) { prune(request); return; }
        if (status == SavedMarketStatus.UNAVAILABLE) { unavailable(request, "market_saved_data_unavailable"); return; }
        request.phase = Phase.VERIFIED;
        request.reason = "market_loading";
        request.verifiedUntil = System.nanoTime() + VERIFIED_NANOS;
        request.retryAt = 0;
        if (request.loadWanted) startLoad(request);
    }

    private boolean startLoad(Request request) {
        if (pending >= MAX_PENDING) { request.reason = "market_loading"; return false; }
        Activation active = activations.get(request.key);
        if (active != null && active.level != request.level) { request.reason = "market_loading"; return false; }
        if (active == null) {
            if (!loadSlots.tryAcquire()) { request.reason = "market_loading"; return false; }
            // getChunkFuture itself managedBlocks on the server thread. Enter it
            // on a worker, then compose a dependent of the vanilla future.
            MinecraftServer server = owner;
            long admitted = System.nanoTime();
            CompletableFuture<Boolean> activation = PortAsyncRuntime.worker(server, () -> {
                        if (closed || request.invalidated || owner != server || System.nanoTime() - admitted >= TIMEOUT_NANOS)
                            throw new CancellationException("Market activation request expired");
                        return request.level.getChunkSource().getChunkFuture(request.key.position.getX() >> 4,
                                request.key.position.getZ() >> 4, ChunkStatus.FULL, true);
                    })
                    .thenCompose(shared -> shared.thenApply(result -> result.isSuccess()));
            active = new Activation(request.level, activation);
            activations.put(request.key, active);
            Activation captured = active;
            // A request timeout cannot free a slot or forget an activation which
            // vanilla still owns. Subsequent requests attach to the same work.
            activation.whenComplete((ready, failure) -> {
                activations.remove(request.key, captured);
                loadSlots.release();
            });
        }
        request.phase = Phase.LOADING;
        request.reason = "market_loading";
        if (!leases.containsKey(request.key)) retainLease(request);
        observe(request, active.future.thenApply(ready -> ready), (ready, failure) -> {
            if (resolveLoadedIfPresent(request)) return;
            if (failure != null || !Boolean.TRUE.equals(ready)) {
                unavailable(request, "market_activation_unavailable");
                return;
            }
            // FULL completion can precede getChunkNow visibility by one tick.
            request.phase = Phase.VERIFIED;
            request.retryAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
        });
        return true;
    }

    private <T> void observe(Request request, CompletableFuture<T> task, BiConsumer<T, Throwable> complete) {
        MinecraftServer server = owner;
        pending++;
        request.task = task;
        request.started = System.nanoTime();
        task.orTimeout(5, TimeUnit.SECONDS).whenComplete((value, failure) -> {
            if (closed) return;
            PortAsyncRuntime.onMain(server, () -> {
                if (!current(request) || request.task != task) return null;
                release(request);
                complete.accept(value, failure);
                return null;
            });
        });
    }

    private MarketWebService.ResolvedMarket resolveLoaded(Request request, LevelChunk chunk) {
        if (chunk.getBlockEntity(request.key.position) instanceof MarketBlockEntity market) {
            if (request.task != null) release(request);
            request.phase = Phase.VERIFIED;
            request.verifiedUntil = System.nanoTime() + VERIFIED_NANOS;
            request.loadWanted = false;
            request.retryAt = 0;
            return new MarketWebService.ResolvedMarket(request.level, market);
        }
        if (!prune(request)) throw unavailable("market_position_unverified");
        return null;
    }

    /** A loaded chunk always supersedes an earlier disk result, including deletion. */
    private boolean resolveLoadedIfPresent(Request request) {
        LevelChunk chunk = request.level.getChunkSource().getChunkNow(request.key.position.getX() >> 4, request.key.position.getZ() >> 4);
        if (chunk == null) return false;
        try { resolveLoaded(request, chunk); }
        catch (UnavailableException unavailable) { unavailable(request, unavailable.reason()); }
        return true;
    }

    private boolean prune(Request request) {
        if (!current(request)) return false;
        MarketTerminalSavedData terminals = MarketTerminalSavedData.get(owner.overworld());
        if (terminals.hasUnverifiedPosition(request.key.dimension, request.key.position)) {
            unavailable(request, "market_position_unverified");
            return false;
        }
        LevelChunk loaded = request.level.getChunkSource().getChunkNow(request.key.position.getX() >> 4, request.key.position.getZ() >> 4);
        // During pending unload a holder can disappear before its save is queued.
        // The loaded-market registry remains populated until Level.unload runs.
        if (loaded == null && MarketRegistry.get(request.level).contains(request.key.position)) {
            unavailable(request, "market_unloading");
            return false;
        }
        if (loaded != null && loaded.getBlockEntity(request.key.position) instanceof MarketBlockEntity) {
            request.phase = Phase.VERIFIED;
            request.verifiedUntil = System.nanoTime() + VERIFIED_NANOS;
            request.loadWanted = false;
            return false;
        }
        terminals.removeEntry(request.key.dimension, request.key.position);
        releaseLease(request.key);
        discard(request);
        invalidated.run();
        return true;
    }

    private boolean current(Request request) {
        return !closed && !request.invalidated && owner != null && requests.get(request.key) == request
                && owner.getLevel(request.level.dimension()) == request.level
                && MarketTerminalSavedData.get(owner.overworld()).getEntry(request.key.dimension, request.key.position) == request.registration;
    }

    private void unavailable(Request request, String reason) {
        release(request);
        request.phase = Phase.UNAVAILABLE;
        request.reason = reason;
        request.retryAt = System.nanoTime() + RETRY_NANOS;
        request.verifiedUntil = 0;
    }

    private static UnavailableException unavailable(String reason) {
        return new UnavailableException(reason, "Market is temporarily unavailable. Please retry shortly.", 5);
    }

    private void release(Request request) {
        CompletableFuture<?> task = request.task;
        if (task == null) return;
        request.task = null;
        pending--;
        if (!task.isDone()) task.cancel(false);
    }

    private void discard(Request request) {
        request.invalidated = true;
        release(request);
        requests.remove(request.key, request);
    }

    /** Leases belong to coordinates rather than a captured registry object, since
     * physical onLoad registration can replace that object during activation. */
    private void retainLease(Request request) {
        Lease previous = leases.get(request.key);
        if (previous != null && previous.level != request.level) {
            releaseLease(request.key);
            previous = null;
        }
        if (previous == null && leases.size() >= MAX_LEASES) releaseLease(leases.keySet().iterator().next());
        ChunkPos chunk = new ChunkPos(request.key.position);
        UUID ticket = previous == null ? UUID.randomUUID() : previous.ticket;
        request.level.getChunkSource().addRegionTicket(MARKET_WEB_TICKET, chunk, 0, ticket);
        leases.remove(request.key);
        leases.put(request.key, new Lease(request.key, request.level, chunk, ticket, (long) owner.getTickCount() + LEASE_TICKS));
    }

    private void releaseLease(MarketKey key) {
        Lease lease = leases.remove(key);
        if (lease != null) lease.level.getChunkSource().removeRegionTicket(MARKET_WEB_TICKET, lease.chunk, 0, lease.ticket);
    }

    @Override
    public void close() {
        closed = true;
        for (Request request : requests.values()) {
            request.invalidated = true;
            release(request);
        }
        requests.clear();
        diskProbes.clear();
        activations.clear();
        for (MarketKey key : List.copyOf(leases.keySet())) releaseLease(key);
        catalog = List.of();
        owner = null;
    }

    static SavedMarketStatus inspectSavedMarket(Optional<CompoundTag> raw, BlockPos position) {
        if (raw == null) return SavedMarketStatus.UNAVAILABLE;
        if (raw.isEmpty()) return SavedMarketStatus.MISSING;
        CompoundTag root = raw.get();
        CompoundTag data = root.contains("Level", Tag.TAG_COMPOUND) ? root.getCompound("Level") : root;
        if (!data.contains("Status", Tag.TAG_STRING)) return SavedMarketStatus.UNAVAILABLE;
        String status = data.getString("Status");
        if (!status.equals("full") && !status.equals("minecraft:full")) return SavedMarketStatus.UNAVAILABLE;
        if (!data.contains("xPos", Tag.TAG_ANY_NUMERIC) || !data.contains("zPos", Tag.TAG_ANY_NUMERIC)
                || data.getInt("xPos") != position.getX() >> 4 || data.getInt("zPos") != position.getZ() >> 4)
            return SavedMarketStatus.UNAVAILABLE;
        String entitiesKey = data.contains("block_entities") ? "block_entities" : "TileEntities";
        if (!(data.get(entitiesKey) instanceof ListTag entities)
                || (!entities.isEmpty() && entities.getElementType() != Tag.TAG_COMPOUND)) return SavedMarketStatus.UNAVAILABLE;
        boolean malformed = false;
        for (Tag rawEntity : entities) {
            CompoundTag entity = (CompoundTag) rawEntity;
            if (!entity.contains("x", Tag.TAG_ANY_NUMERIC) || !entity.contains("y", Tag.TAG_ANY_NUMERIC)
                    || !entity.contains("z", Tag.TAG_ANY_NUMERIC) || !entity.contains("id", Tag.TAG_STRING)) {
                malformed = true;
                continue;
            }
            if (entity.getInt("x") == position.getX() && entity.getInt("y") == position.getY()
                    && entity.getInt("z") == position.getZ() && entity.getString("id").equals("sailboatmod:market"))
                return SavedMarketStatus.PRESENT;
        }
        return malformed ? SavedMarketStatus.UNAVAILABLE : SavedMarketStatus.MISSING;
    }

    static boolean isStructuralCorruption(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++, failure = failure.getCause()) {
            if (failure instanceof EOFException || failure instanceof ZipException) return true;
        }
        return false;
    }
}
