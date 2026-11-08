package com.monpai.sailboatmod.market.web;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.market.MarketRegistry;
import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData;
import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData.MarketTerminalEntry;
import com.monpai.sailboatmod.route.water.PortAsyncRuntime;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMarketResolverSchedulingTest {
    private static final String DIMENSION = "minecraft:overworld";

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void duplicateRequestsShareProbeAndOnlyVerifiedMarketActivatesOffThread(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            BlockPos position = fixture.register(0);
            fixture.pending(position);
            fixture.pending(position);
            assertEquals(1, fixture.chunks.disk.reads.size());
            assertEquals(0, fixture.chunks.activations.size());
            fixture.read(0).complete(Optional.of(savedMarket(position)));
            fixture.mailbox();
            awaitActivations(fixture, 1);
            assertNotEquals(Thread.currentThread(), fixture.chunks.activationThreads.getFirst());
            fixture.resolver.close();
            assertFalse(fixture.chunks.activations.getFirst().isCancelled(), "closing a request must leave vanilla chunk work intact");
        }
    }

    @Test
    void catalogValidationKeepsUnloadedMarketsWithoutActivatingThem(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            BlockPos first = fixture.register(0);
            BlockPos second = fixture.register(32);
            fixture.register(64);
            fixture.resolver.validateCatalog(fixture.server);
            assertEquals(2, fixture.chunks.disk.reads.size(), "catalog work is limited to two entries per interval");
            fixture.read(0).complete(Optional.of(savedMarket(first)));
            fixture.read(1).complete(Optional.of(savedMarket(second)));
            fixture.mailbox();
            assertEquals(0, fixture.chunks.activations.size());
            assertEquals(3, fixture.terminals.entries().size());
            assertFalse(fixture.resolver.isLoaded(fixture.server, DIMENSION, first));
        }
    }

    @Test
    void successfulAbsencePrunesOnlyTheCapturedRegistrationAndInvalidatesMaps(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            BlockPos missing = fixture.register(0);
            fixture.pending(missing);
            fixture.read(0).complete(Optional.empty());
            fixture.mailbox();
            assertNull(fixture.terminals.getEntry(DIMENSION, missing));
            assertEquals(1, fixture.invalidations.get());

            BlockPos replaced = fixture.register(32);
            fixture.pending(replaced);
            MarketTerminalEntry replacement = new MarketTerminalEntry(DIMENSION, replaced, "New registration", "", "");
            fixture.terminals.putEntry(replacement);
            fixture.read(1).complete(Optional.empty());
            fixture.mailbox();
            fixture.resolver.tick(fixture.server);
            assertSame(replacement, fixture.terminals.getEntry(DIMENSION, replaced));
            assertEquals(1, fixture.invalidations.get(), "a stale completion cannot remove a replacement registration");
        }
    }

    @Test
    void loadedMarketOverridesAnOlderAbsentDiskResult(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            BlockPos position = fixture.register(0);
            fixture.pending(position);
            TestChunk loaded = allocate(TestChunk.class);
            loaded.entity = allocate(MarketBlockEntity.class);
            fixture.chunks.loaded.put(new ChunkPos(position), loaded);
            fixture.read(0).complete(Optional.empty());
            fixture.mailbox();
            assertNotNull(fixture.terminals.getEntry(DIMENSION, position));
            assertSame(loaded.entity, fixture.resolver.resolve(fixture.server, DIMENSION, position).market());
            assertEquals(0, fixture.invalidations.get());
        }
    }

    @Test
    void transientFailureAndRequestTimeoutRetainRegistrationAndSharedIo(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            BlockPos failed = fixture.register(0);
            fixture.pending(failed);
            fixture.read(0).completeExceptionally(new IOException("temporary device failure"));
            fixture.mailbox();
            fixture.pending(failed);
            assertEquals(1, fixture.chunks.disk.reads.size(), "failed probes wait for backoff before retrying");
            assertNotNull(fixture.terminals.getEntry(DIMENSION, failed));

            BlockPos slow = fixture.register(32);
            fixture.pending(slow);
            expirePending(fixture.resolver);
            fixture.resolver.tick(fixture.server);
            assertFalse(fixture.read(1).isDone(), "request timeout must neither complete nor cancel shared disk IO");
            assertNotNull(fixture.terminals.getEntry(DIMENSION, slow));
            assertEquals(0, fixture.invalidations.get());
        }
    }

    @Test
    void pendingProbeLimitAndCloseLeaveVanillaReadsOwnedByVanilla(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            for (int i = 0; i < 33; i++) fixture.pending(fixture.register(i * 32));
            assertEquals(32, fixture.chunks.disk.reads.size());
            fixture.resolver.close();
            assertTrue(fixture.chunks.disk.reads.stream().noneMatch(CompletableFuture::isDone));
            fixture.chunks.disk.reads.getFirst().complete(Optional.empty());
            fixture.mailbox();
            assertEquals(33, fixture.terminals.entries().size(), "late results after close cannot prune registrations");
        }
    }

    @Test
    void activationLimitSurvivesRequesterTimeoutWhileVanillaStillLoads(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            List<BlockPos> positions = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                BlockPos position = fixture.register(i * 32);
                positions.add(position);
                fixture.pending(position);
            }
            for (int i = 0; i < 3; i++) fixture.read(i).complete(Optional.of(savedMarket(positions.get(i))));
            fixture.mailbox();
            awaitActivations(fixture, 2);
            expirePending(fixture.resolver);
            fixture.resolver.tick(fixture.server);
            assertEquals(2, fixture.chunks.activations.size(), "timed-out callers cannot free an underlying chunk-load slot");
            fixture.chunks.activations.getFirst().complete(ChunkResult.error("fixture load unavailable"));
            fixture.resolver.tick(fixture.server);
            awaitActivations(fixture, 3);
            assertEquals(3, fixture.terminals.entries().size());
        }
    }

    @Test
    void requestedMarketLeaseSurvivesReregistrationAndReleasesOnClose(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            BlockPos position = fixture.register(0);
            fixture.pending(position);
            assertTrue(fixture.chunks.tickets.isEmpty(), "unverified saved coordinates cannot acquire chunk tickets");
            fixture.read(0).complete(Optional.of(savedMarket(position)));
            fixture.mailbox();
            awaitActivations(fixture, 1);
            assertEquals(1, fixture.chunks.tickets.size(), "HTTP retries need a short FULL lease after saved-market verification");
            fixture.terminals.putEntry(new MarketTerminalEntry(DIMENSION, position, "Re-registered", "", ""));
            fixture.resolver.tick(fixture.server);
            assertEquals(1, fixture.chunks.tickets.size(), "physical onLoad re-registration must preserve the activation lease");
            fixture.resolver.close();
            assertTrue(fixture.chunks.tickets.isEmpty(), "closing the resolver releases every transient lease");
        }
    }

    @Test
    void timedOutCallerReusesTheSameStillRunningChunkActivationOnRetry(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            BlockPos position = fixture.register(0);
            fixture.pending(position);
            fixture.read(0).complete(Optional.of(savedMarket(position)));
            fixture.mailbox();
            awaitActivations(fixture, 1);
            expirePending(fixture.resolver);
            fixture.resolver.tick(fixture.server);
            Field requests = fixture.resolver.getClass().getDeclaredField("requests");
            requests.setAccessible(true);
            for (Object request : ((Map<?, ?>) requests.get(fixture.resolver)).values())
                set(request.getClass(), request, "retryAt", 0L);
            fixture.pending(position);
            fixture.read(1).complete(Optional.of(savedMarket(position)));
            fixture.mailbox();
            // A second worker would run promptly, so wait for an unrelated worker
            // barrier before asserting that the activation count stayed unchanged.
            PortAsyncRuntime.worker(fixture.server, () -> true).get(2, TimeUnit.SECONDS);
            assertEquals(1, fixture.chunks.activations.size(), "one market cannot occupy both underlying activation slots after requester timeout");
        }
    }

    @Test
    void catalogViewsDoNotPinMarketsOrRefreshExpiredOperationLeases(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            BlockPos position = fixture.register(0);
            TestChunk loaded = allocate(TestChunk.class);
            loaded.entity = allocate(MarketBlockEntity.class);
            fixture.chunks.loaded.put(new ChunkPos(position), loaded);
            assertTrue(fixture.resolver.isLoaded(fixture.server, DIMENSION, position));
            assertTrue(fixture.chunks.tickets.isEmpty());
            assertNotNull(fixture.resolver.resolve(fixture.server, DIMENSION, position));
            assertEquals(1, fixture.chunks.tickets.size());
            fixture.server.ticks = 201;
            fixture.resolver.tick(fixture.server);
            assertTrue(fixture.chunks.tickets.isEmpty(), "catalog validation cannot prolong an expired operation lease");
        }
    }

    @Test
    void timedOutProbeRetriesReuseTheBoundedUnderlyingVanillaReads(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            List<BlockPos> positions = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                BlockPos position = fixture.register(i * 32);
                positions.add(position);
                fixture.pending(position);
            }
            expirePending(fixture.resolver);
            fixture.resolver.tick(fixture.server);
            Field requests = fixture.resolver.getClass().getDeclaredField("requests");
            requests.setAccessible(true);
            for (Object request : ((Map<?, ?>) requests.get(fixture.resolver)).values())
                set(request.getClass(), request, "retryAt", 0L);
            for (BlockPos position : positions) fixture.pending(position);
            assertEquals(32, fixture.chunks.disk.reads.size(), "request deadlines cannot enqueue another batch while the original vanilla IO remains unresolved");
        }
    }

    @Test
    void negativeDiskResultWaitsForPendingUnloadToPersistItsLiveMarket(@TempDir Path directory) throws Exception {
        try (Fixture fixture = new Fixture(directory)) {
            BlockPos position = fixture.register(0);
            MarketRegistry.register(fixture.level, position);
            try {
                fixture.pending(position);
                fixture.read(0).complete(Optional.empty());
                fixture.mailbox();
                assertNotNull(fixture.terminals.getEntry(DIMENSION, position), "a chunk can leave getChunkNow before its pending unload saves the new market");
                assertEquals(0, fixture.invalidations.get());
            } finally { MarketRegistry.unregister(fixture.level, position); }
        }
    }

    private static CompoundTag savedMarket(BlockPos position) {
        CompoundTag chunk = new CompoundTag();
        chunk.putString("Status", "minecraft:full");
        chunk.putInt("xPos", position.getX() >> 4);
        chunk.putInt("zPos", position.getZ() >> 4);
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "sailboatmod:market");
        entity.putInt("x", position.getX());
        entity.putInt("y", position.getY());
        entity.putInt("z", position.getZ());
        ListTag entities = new ListTag();
        entities.add(entity);
        chunk.put("block_entities", entities);
        return chunk;
    }

    private static void expirePending(MarketWebMarketResolver resolver) throws Exception {
        Field requests = resolver.getClass().getDeclaredField("requests");
        requests.setAccessible(true);
        for (Object request : ((Map<?, ?>) requests.get(resolver)).values()) {
            Field task = request.getClass().getDeclaredField("task");
            task.setAccessible(true);
            if (task.get(request) != null) set(request.getClass(), request, "started", System.nanoTime() - TimeUnit.SECONDS.toNanos(6));
        }
    }

    private static void awaitActivations(Fixture fixture, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (fixture.chunks.activations.size() < expected && System.nanoTime() < deadline) LockSupport.parkNanos(100_000L);
        assertEquals(expected, fixture.chunks.activations.size());
    }

    private static final class Fixture implements AutoCloseable {
        final TestServer server;
        final TestLevel level;
        final TestChunks chunks;
        final MarketTerminalSavedData terminals;
        final AtomicInteger invalidations = new AtomicInteger();
        final MarketWebMarketResolver resolver = new MarketWebMarketResolver(invalidations::incrementAndGet);

        Fixture(Path directory) throws Exception {
            server = allocate(TestServer.class);
            server.owner = Thread.currentThread();
            level = allocate(TestLevel.class);
            level.server = server;
            level.storage = new DimensionDataStorage(directory.toFile(), null, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            chunks = allocate(TestChunks.class);
            chunks.loaded = new LinkedHashMap<>();
            chunks.activations = new CopyOnWriteArrayList<>();
            chunks.activationThreads = new CopyOnWriteArrayList<>();
            chunks.tickets = new LinkedHashMap<>();
            TestChunkMap disk = allocate(TestChunkMap.class);
            disk.reads = new ArrayList<>();
            chunks.disk = disk;
            set(ServerChunkCache.class, chunks, "chunkMap", disk);
            level.chunks = chunks;
            set(MinecraftServer.class, server, "levels", new LinkedHashMap<>(Map.of(Level.OVERWORLD, level)));
            terminals = MarketTerminalSavedData.get(level);
        }

        BlockPos register(int x) {
            BlockPos position = new BlockPos(x, 64, 0);
            terminals.putEntry(new MarketTerminalEntry(DIMENSION, position, "Market", "", ""));
            return position;
        }

        void pending(BlockPos position) {
            assertThrows(MarketWebMarketResolver.UnavailableException.class, () -> resolver.resolve(server, DIMENSION, position));
        }

        CompletableFuture<Optional<CompoundTag>> read(int index) { return chunks.disk.reads.get(index); }
        void mailbox() { PortAsyncRuntime.tick(new ServerTickEvent.Post(() -> true, server)); }

        @Override public void close() {
            resolver.close();
            chunks.activations.forEach(future -> future.complete(ChunkResult.error("fixture closed")));
            PortAsyncRuntime.stop(new ServerStoppingEvent(server));
        }
    }

    private static final class TestServer extends DedicatedServer {
        Thread owner;
        int ticks;
        private TestServer() { super(null, null, null, null, null, null, null, null); }
        @Override public Thread getRunningThread() { return owner; }
        @Override public boolean isStopped() { return false; }
        @Override public int getTickCount() { return ticks; }
    }

    private static final class TestLevel extends ServerLevel {
        TestServer server;
        TestChunks chunks;
        DimensionDataStorage storage;
        private TestLevel() { super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public ServerChunkCache getChunkSource() { return chunks; }
        @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public DimensionDataStorage getDataStorage() { return storage; }
        @Override public int getMinBuildHeight() { return -64; }
        @Override public int getHeight() { return 384; }
    }

    private static final class TestChunks extends ServerChunkCache {
        TestChunkMap disk;
        Map<ChunkPos, LevelChunk> loaded;
        List<CompletableFuture<ChunkResult<ChunkAccess>>> activations;
        List<Thread> activationThreads;
        Map<Object, ChunkPos> tickets;
        private TestChunks() { super(null, null, null, null, command -> { }, null, 0, 0, false, null, null, null); }
        @Override public LevelChunk getChunkNow(int x, int z) { return loaded.get(new ChunkPos(x, z)); }
        @Override public CompletableFuture<ChunkResult<ChunkAccess>> getChunkFuture(int x, int z, ChunkStatus status, boolean create) {
            CompletableFuture<ChunkResult<ChunkAccess>> future = new CompletableFuture<>();
            activationThreads.add(Thread.currentThread());
            activations.add(future);
            return future;
        }
        @Override public <T> void addRegionTicket(TicketType<T> type, ChunkPos position, int radius, T key) {
            tickets.put(key, position);
        }
        @Override public <T> void removeRegionTicket(TicketType<T> type, ChunkPos position, int radius, T key) {
            tickets.remove(key);
        }
    }

    private static final class TestChunkMap extends ChunkMap {
        List<CompletableFuture<Optional<CompoundTag>>> reads;
        private TestChunkMap() { super(null, null, null, null, command -> { }, null, null, null, null, null, () -> null, 0, false); }
        @Override public CompletableFuture<Optional<CompoundTag>> read(ChunkPos position) {
            CompletableFuture<Optional<CompoundTag>> future = new CompletableFuture<>();
            reads.add(future);
            return future;
        }
    }

    private static final class TestChunk extends LevelChunk {
        BlockEntity entity;
        private TestChunk() { super((Level) null, new ChunkPos(0, 0)); }
        @Override public BlockEntity getBlockEntity(BlockPos position) { return entity; }
    }

    private static void set(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((Unsafe) field.get(null)).allocateInstance(type);
    }
}
