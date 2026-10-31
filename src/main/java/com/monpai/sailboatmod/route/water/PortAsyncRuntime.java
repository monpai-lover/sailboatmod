package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded background work + a cooperative, globally budgeted server-thread mailbox.
 * Never use CallerRunsPolicy here: queue saturation must not run IO on the tick thread.
 * World mutations / live snapshots stay on the server thread; no future is awaited there.
 */
@EventBusSubscriber(modid = SailboatMod.MODID)
public final class PortAsyncRuntime {
    static final int MAIN_QUEUE_LIMIT = 256;
    static final int MAIN_STEPS_PER_TICK = 8;
    static final long MAIN_BUDGET_NANOS = 2_000_000L;
    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();
    private static final Set<MinecraftServer> STOPPED=Collections.newSetFromMap(new WeakHashMap<>());
    private PortAsyncRuntime() {}

    private static final class State {
        final ArrayBlockingQueue<Runnable> main = new ArrayBlockingQueue<>(MAIN_QUEUE_LIMIT);
        final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
        final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), r -> {
                    Thread t = new Thread(r, "PortIO-Worker"); t.setDaemon(true); return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        volatile boolean closed;
        State() { workers.allowCoreThreadTimeOut(true); }
    }
    private static synchronized State state(MinecraftServer server) {
        if(STOPPED.contains(server))throw new RejectedExecutionException("Server stopped");
        return STATES.computeIfAbsent(server, ignored -> new State());
    }
    public static <T> CompletableFuture<T> worker(MinecraftServer server, Supplier<T> work) {
        State s;try{s=state(server);}catch(RejectedExecutionException stopped){return CompletableFuture.failedFuture(stopped);}
        if (s.closed) return CompletableFuture.failedFuture(new CancellationException("Server stopping"));
        CompletableFuture<T> result = new CompletableFuture<>();
        FutureTask<Void> task = new FutureTask<>(() -> {
            if (!result.isDone() && !s.closed) {
                try { result.complete(work.get()); }
                catch (Throwable error) { result.completeExceptionally(error); }
            }
            return null;
        });
        s.pending.add(result);
        result.whenComplete((value, failure) -> {
            s.pending.remove(result);
            if (result.isCancelled()) {
                task.cancel(true); // unlike supplyAsync, cancellation reaches the running IO thread
                s.workers.remove(task);
            }
        });
        try { s.workers.execute(task); }
        catch (RejectedExecutionException full) { result.completeExceptionally(full); }
        return result;
    }
    public static <T> CompletableFuture<T> onMain(MinecraftServer server, Supplier<T> work) {
        State s;try{s=state(server);}catch(RejectedExecutionException stopped){return CompletableFuture.failedFuture(stopped);}
        CompletableFuture<T> future = new CompletableFuture<>();
        s.pending.add(future);
        future.whenComplete((v, e) -> s.pending.remove(future));
        Runnable step = () -> {
            if (future.isDone() || s.closed) return;
            try { future.complete(work.get()); }
            catch (Throwable error) { future.completeExceptionally(error); }
        };
        if (s.closed || !s.main.offer(step))
            future.completeExceptionally(new RejectedExecutionException("Port snapshot mailbox full"));
        return future;
    }
    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        State s;
        synchronized (PortAsyncRuntime.class) { s = STATES.get(event.getServer()); }
        if (s == null || s.closed) return;
        long deadline = System.nanoTime() + MAIN_BUDGET_NANOS;
        for (int i = 0; i < MAIN_STEPS_PER_TICK && System.nanoTime() < deadline; i++) {
            Runnable step = s.main.poll(); if (step == null) break; step.run();
        }
    }
    @SubscribeEvent
    public static synchronized void stop(ServerStoppingEvent event) {
        STOPPED.add(event.getServer());
        State s = STATES.remove(event.getServer());
        if (s == null) return;
        s.closed = true; s.workers.shutdownNow(); s.main.clear();
        s.pending.forEach(f -> f.cancel(false)); s.pending.clear();
    }
}
