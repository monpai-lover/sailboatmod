package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

class WaterAutoRouteSchedulingTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void readOnlyPortCaptureWaitsForServerTickEvenWhenMinecraftSubmitWouldInline() throws Exception {
        TestServer server = server(false);
        TestLevel level = level(server);
        AtomicBoolean cancelled = new AtomicBoolean();
        level.afterCapture = () -> cancelled.set(true);
        CountDownLatch entered = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CompletableFuture<WaterAutoRouteService.ReadOnlyNbtPlan> result = CompletableFuture.supplyAsync(() -> {
            entered.countDown();
            return WaterRouteCancellation.run(cancelled::get,
                    () -> WaterAutoRouteService.planReadOnlyNbt(level, zone(0), zone(64)));
        }, worker);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            awaitEnqueuedOrCompleted(server, result);
            assertFalse(result.isDone(), () -> "port metadata must wait for a server tick; early outcome=" + outcome(result));
            assertNull(level.captureThread, "the HTTP/worker thread must not read capture metadata");
            PortAsyncRuntime.tick(new ServerTickEvent.Post(() -> true, server));
            assertEquals(Thread.currentThread(), level.captureThread, "capture must run on the server thread");
            assertEquals(WaterRouteFailureReason.CANCELLED, result.get(2, TimeUnit.SECONDS).result().reason());
            assertEquals(0, server.inlineDispatches, "read-only capture must bypass Minecraft's inline-capable submit");
        } finally {
            result.cancel(true);
            worker.shutdownNow();
            PortAsyncRuntime.stop(new ServerStoppingEvent(server));
        }
    }

    @Test
    void callerCancellationCancelsQueuedCaptureWithoutWaitingForAnotherServerTick() throws Exception {
        TestServer server = server(false);
        TestLevel level = level(server);
        AtomicBoolean cancelled = new AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CompletableFuture<WaterAutoRouteService.ReadOnlyNbtPlan> result = CompletableFuture.supplyAsync(() ->
                WaterRouteCancellation.run(cancelled::get,
                        () -> WaterAutoRouteService.planReadOnlyNbt(level, zone(0), zone(64))), worker);
        try {
            awaitEnqueuedOrCompleted(server, result);
            assertFalse(result.isDone(), () -> "capture must be queued before cancellation; early outcome=" + outcome(result));
            cancelled.set(true);
            assertEquals(WaterRouteFailureReason.CANCELLED, result.get(2, TimeUnit.SECONDS).result().reason());
            PortAsyncRuntime.tick(new ServerTickEvent.Post(() -> true, server));
            assertNull(level.captureThread, "a cancelled mailbox item must never access world metadata later");
        } finally {
            result.cancel(true);
            worker.shutdownNow();
            PortAsyncRuntime.stop(new ServerStoppingEvent(server));
        }
    }

    @Test
    void stoppedServerIsRejectedBeforeCapturingOrQueuingWorldMetadata() throws Exception {
        TestServer server = server(true);
        TestLevel level = level(server);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> WaterAutoRouteService.planReadOnlyNbt(level, zone(0), zone(64)))
                    .get(2, TimeUnit.SECONDS);
            assertEquals(WaterRouteFailureReason.CANCELLED, result.result().reason());
            assertNull(level.captureThread);
            assertEquals(0, server.inlineDispatches);
            assertFalse(mailboxHasWork(server));
        } finally {
            worker.shutdownNow();
            PortAsyncRuntime.stop(new ServerStoppingEvent(server));
        }
    }

    private static void awaitEnqueuedOrCompleted(TestServer server, CompletableFuture<?> result) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!result.isDone() && !mailboxHasWork(server) && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertTrue(result.isDone() || mailboxHasWork(server), "worker did not reach metadata scheduling");
    }

    private static boolean mailboxHasWork(MinecraftServer server) throws Exception {
        Field states = PortAsyncRuntime.class.getDeclaredField("STATES");
        states.setAccessible(true);
        synchronized (PortAsyncRuntime.class) {
            Object state = ((Map<?, ?>) states.get(null)).get(server);
            if (state == null) return false;
            Field main = state.getClass().getDeclaredField("main");
            main.setAccessible(true);
            return !((java.util.Queue<?>) main.get(state)).isEmpty();
        }
    }

    private static String outcome(CompletableFuture<?> result) {
        try { return String.valueOf(result.join()); }
        catch (RuntimeException failure) { return failure.toString(); }
    }

    private static DockBerthResolver.DockZone zone(int x) {
        return new DockBerthResolver.DockZone(new BlockPos(x, 63, 0), x, x + 3, 0, 3);
    }

    private static TestServer server(boolean stopped) throws Exception {
        TestServer server = allocate(TestServer.class);
        server.owner = Thread.currentThread();
        server.stopped = stopped;
        return server;
    }

    private static TestLevel level(TestServer server) throws Exception {
        TestLevel level = allocate(TestLevel.class);
        level.server = server;
        level.chunks = allocate(TestChunks.class);
        level.chunks.generator = allocate(FlatLevelSource.class);
        return level;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((Unsafe) field.get(null)).allocateInstance(type);
    }

    /** Models the real stopped-server submit path, and inline-capable event-loop overrides. */
    private static final class TestServer extends DedicatedServer {
        Thread owner;
        boolean stopped;
        int inlineDispatches;
        private TestServer() { super(null, null, null, null, null, null, null, null); }
        @Override public Thread getRunningThread() { return owner; }
        @Override public boolean isStopped() { return stopped; }
        @Override public boolean scheduleExecutables() { inlineDispatches++; return false; }
    }

    private static final class TestLevel extends ServerLevel {
        TestServer server;
        TestChunks chunks;
        volatile Thread captureThread;
        Runnable afterCapture;
        private TestLevel() { super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null); }
        @Override public MinecraftServer getServer() { return server; }
        @Override public ServerChunkCache getChunkSource() { return chunks; }
        @Override public net.minecraft.resources.ResourceKey<Level> dimension() { return Level.OVERWORLD; }
        @Override public int getMinBuildHeight() {
            captureThread = Thread.currentThread();
            if (afterCapture != null) afterCapture.run();
            return -64;
        }
        @Override public int getHeight() { return 384; }
        @Override public int getSeaLevel() { return 63; }
    }

    private static final class TestChunks extends ServerChunkCache {
        ChunkGenerator generator;
        private TestChunks() { super(null, null, null, null, command -> { }, null, 0, 0, false, null, null, null); }
        @Override public ChunkGenerator getGenerator() { return generator; }
    }
}
