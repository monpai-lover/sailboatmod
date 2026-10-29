package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainPatchPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainRequestPacket;
import com.monpai.sailboatmod.roadplanner.map.MapBlockColors;
import com.monpai.sailboatmod.roadplanner.map.SharedMapSavedChunk;
import com.monpai.sailboatmod.roadplanner.map.SharedMapTerrainRenderer;
import com.monpai.sailboatmod.roadplanner.map.SharedMapTileGrid;
import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import com.monpai.sailboatmod.util.OfflineChunkNbtReader;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.BooleanSupplier;
import java.util.function.ToLongFunction;

/** Main-mod shared terrain streaming. Viewing maps never loads or generates a Minecraft chunk. */
public final class SharedMapTerrainService {
    private static volatile SharedMapTerrainService global = new SharedMapTerrainService();
    private static final int MAX_TILES = 256;
    private static final int CHUNKS_PER_LEVEL_TICK = 8;
    private static final int MAX_READS = 16;
    private static final long READ_TIMEOUT_TICKS = 100;
    private static final long WORK_TTL_TICKS = 600;
    private final LinkedHashMap<TileKey, TileWork> work = new LinkedHashMap<>();
    private final LinkedHashMap<TileKey, int[]> predictionCache = new LinkedHashMap<>();
    private final LinkedHashMap<ChunkKey, int[]> savedColors = new LinkedHashMap<>();
    private final LinkedHashMap<ChunkKey, Long> absentUntil = new LinkedHashMap<>();
    private final Map<ServerLevel, Optional<TerrainSurfaceSampler>> samplers = new LinkedHashMap<>();
    private final ArrayBlockingQueue<Result> completed = new ArrayBlockingQueue<>(128);
    private final ArrayBlockingQueue<LoadedChunk> changedChunks = new ArrayBlockingQueue<>(256);
    private final ExecutorService predictionWorker = Executors.newSingleThreadExecutor(task -> daemon(task, "SharedMap-Tellus"));
    private final SharedMapPredictionQueue predictions = new SharedMapPredictionQueue(MAX_TILES, predictionWorker);
    private final ThreadPoolExecutor decodeWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> daemon(task, "SharedMap-NbtDecode"), new ThreadPoolExecutor.AbortPolicy());
    private MinecraftServer server;
    private final SharedMapReadBudget readBudget = new SharedMapReadBudget(MAX_READS);
    private volatile long tick;
    private volatile boolean closed;

    private record TileKey(String dimension, int x, int z, int bps) { }
    private record ChunkKey(String dimension, int x, int z) { }
    private record Result(TileKey key, TileWork work, int localChunk, int[] pixels, boolean prediction) { }
    private record LoadedChunk(ServerLevel level, int x, int z) { }
    private record ChunkReads(CompletableFuture<Optional<CompoundTag>> current,
                              CompletableFuture<Optional<CompoundTag>> north) {
        boolean complete() { return current.isDone() && north.isDone(); }
    }

    private static final class TileWork {
        final TileKey key;
        final ServerLevel level;
        final LinkedHashMap<UUID, String> subscribers = new LinkedHashMap<>();
        final AtomicInteger decoding = new AtomicInteger();
        final List<Integer> order;
        final int origin;
        int cursor;
        ChunkReads pending;
        long started;
        volatile long touched;
        volatile boolean predictionCancelled;
        long lastAdvanced = Long.MIN_VALUE;

        TileWork(TileKey key, ServerLevel level, ServerPlayer player, long tick) {
            this.key = key;
            this.level = level;
            this.touched = tick;
            order = new ArrayList<>();
            if (key.bps == 1) for (int i = 0; i < 256; i++) order.add(i);
            int cx = player.chunkPosition().x;
            int cz = player.chunkPosition().z;
            order.sort(Comparator.comparingInt(index -> Math.abs(key.x * 16 + index % 16 - cx)
                    + Math.abs(key.z * 16 + index / 16 - cz)));
            long span = SharedMapTileGrid.span(key.bps);
            int px = (int) Math.max(0, Math.min(255, Math.floorDiv(player.getBlockX() - key.x * span, key.bps)));
            int pz = (int) Math.max(0, Math.min(255, Math.floorDiv(player.getBlockZ() - key.z * span, key.bps)));
            origin = pz * 256 + px;
        }
        int limit() { return key.bps == 1 ? 256 : 65536; }
        int local() { return key.bps == 1 ? order.get(cursor) : (origin + cursor) & 65535; }
    }

    public static SharedMapTerrainService global() { return global; }

    public static void start(MinecraftServer server) {
        global.close();
        global = new SharedMapTerrainService();
        global.server = server;
    }

    public static void stop() { global.close(); }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }

    public void request(ServerPlayer player, SharedMapTerrainRequestPacket packet) {
        if (closed || player == null || packet == null || server == null
                || !SharedMapTileGrid.validTile(packet.tileX(), packet.tileZ(), packet.blocksPerPixel())) return;
        ServerLevel level = player.serverLevel();
        String dimension = level.dimension().location().toString();
        if (!dimension.equals(packet.dimensionId()) || packet.worldId() == null || packet.worldId().isBlank()) return;
        TileKey key = new TileKey(dimension, packet.tileX(), packet.tileZ(), packet.blocksPerPixel());
        TileWork active = work.remove(key);
        if (active == null) active = new TileWork(key, level, player, tick);
        else if (active.cursor >= active.limit() && active.decoding.get() == 0) active.cursor = 0;
        active.touched = tick;
        active.subscribers.put(player.getUUID(), packet.worldId());
        while (active.subscribers.size() > 64) active.subscribers.remove(active.subscribers.keySet().iterator().next());
        work.put(key, active);
        while (work.size() > MAX_TILES) discard(work.keySet().iterator().next());
        int[] cached = predictionCache.get(key);
        if (cached != null) {
            send(player, packet.worldId(), key, 0, cached, true);
        }
        if (cached == null || SharedMapTerrainRenderer.hasUnknownPixels(cached)) {
            TerrainSurfaceSampler sampler = samplers.computeIfAbsent(level, TellusSurfaceSamplers::forLevel).orElse(null);
            TileWork requested = active;
            BooleanSupplier cancelled = () -> closed || requested.predictionCancelled || tick - requested.touched > WORK_TTL_TICKS;
            if (sampler != null) predictions.request(key.toString(), () -> {
                if (cancelled.getAsBoolean()) return;
                int[] pixels = SharedMapTerrainRenderer.render(sampler, (int) (key.x * SharedMapTileGrid.span(key.bps)),
                        (int) (key.z * SharedMapTileGrid.span(key.bps)), 256, 256, key.bps, cancelled);
                if (!cancelled.getAsBoolean() && java.util.Arrays.stream(pixels).anyMatch(color -> (color >>> 24) != 0)) {
                    completed.offer(new Result(key, requested, 0, pixels, true));
                }
            });
        }
    }

    public void tick(ServerLevel level) {
        if (closed || server == null || level == null) return;
        tick = server.getTickCount();
        drainCompleted();
        for (int i = 0; i < 8; i++) {
            LoadedChunk changed = changedChunks.poll();
            if (changed == null) break;
            String dimension = changed.level.dimension().location().toString();
            ChunkKey changedKey = new ChunkKey(dimension, changed.x, changed.z);
            savedColors.remove(changedKey);
            absentUntil.remove(changedKey);
            TileKey key = new TileKey(dimension, Math.floorDiv(changed.x, 16), Math.floorDiv(changed.z, 16), 1);
            TileWork active = work.get(key);
            int[] pixels = null;
            if (active != null) {
                pixels = captureLoaded(changed.level, changed.x, changed.z);
                if (pixels != null) deliver(active, Math.floorMod(changed.z, 16) * 16 + Math.floorMod(changed.x, 16), pixels);
            }
            for (TileWork coarse : work.values()) {
                if (coarse.key.bps == 1 || coarse.level != changed.level) continue;
                int[] indices = SharedMapTileGrid.pixelsInChunk(coarse.key.x, coarse.key.z, coarse.key.bps, changed.x, changed.z);
                if (indices.length == 0) continue;
                if (pixels == null) pixels = captureLoaded(changed.level, changed.x, changed.z);
                if (pixels != null) deliverChunkSamples(coarse, indices, pixels);
            }
        }
        for (TileWork active : List.copyOf(work.values())) {
            if (active.level == level && tick - active.touched > WORK_TTL_TICKS) discard(active.key);
        }
        for (TileWork active : fairPending(work.values(),
                task -> task.level == level && task.cursor < task.limit(), task -> task.lastAdvanced, CHUNKS_PER_LEVEL_TICK)) {
            active.lastAdvanced = tick;
            advance(active);
        }
    }

    public void changedChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (closed || level == null) return;
        for (long chunk : dependentChunks(chunkX, chunkZ)) {
            changedChunks.offer(new LoadedChunk(level, ChunkPos.getX(chunk), ChunkPos.getZ(chunk)));
        }
    }

    static long[] dependentChunks(int chunkX, int chunkZ) {
        return new long[]{ChunkPos.asLong(chunkX, chunkZ), ChunkPos.asLong(chunkX, chunkZ + 1)};
    }

    static <T> List<T> recentPending(Collection<T> tasks, Predicate<T> pending, int budget) {
        List<T> ordered = new ArrayList<>(tasks);
        Collections.reverse(ordered);
        return ordered.stream().filter(pending).limit(Math.max(0, budget)).toList();
    }

    static <T> List<T> fairPending(Collection<T> tasks, Predicate<T> pending, ToLongFunction<T> lastAdvanced, int budget) {
        List<T> ordered = new ArrayList<>(tasks);
        Collections.reverse(ordered); // Stable sorting keeps the newest first when none has received a slice yet.
        ordered.removeIf(task -> !pending.test(task));
        ordered.sort(Comparator.comparingLong(lastAdvanced));
        return ordered.stream().limit(Math.max(0, budget)).toList();
    }

    private void advance(TileWork active) {
        if (active.cursor >= active.limit()) return;
        int local = active.local();
        ChunkPos chunk = location(active.key, local);
        int chunkX = chunk.x, chunkZ = chunk.z;
        int[] loaded = active.key.bps == 1 ? captureLoaded(active.level, chunkX, chunkZ) : captureLoadedPixel(active, local);
        if (loaded != null) {
            clearPending(active);
            deliver(active, local, loaded);
            active.cursor++;
            return;
        }
        ChunkKey chunkKey = new ChunkKey(active.key.dimension, chunkX, chunkZ);
        if (active.key.bps > 1 && active.pending == null) {
            int[] saved = savedColors.get(chunkKey);
            if (saved != null) { deliver(active, local, sampledPixel(active.key, local, saved)); active.cursor++; return; }
            if (absentUntil.getOrDefault(chunkKey, Long.MIN_VALUE) > tick) { active.cursor++; return; }
        }
        if (active.pending == null) {
            List<CompletableFuture<Optional<CompoundTag>>> pair = readBudget.request(2, index ->
                    OfflineChunkNbtReader.beginProbe(active.level, new ChunkPos(chunkX, index == 0 ? chunkZ : chunkZ - 1)));
            if (pair == null) return;
            active.pending = new ChunkReads(pair.get(0), pair.get(1));
            active.started = tick;
            return;
        }
        if (!active.pending.complete() && tick - active.started < READ_TIMEOUT_TICKS) return;
        Optional<CompoundTag> tag = completedTag(active.pending.current);
        Optional<CompoundTag> north = completedTag(active.pending.north);
        clearPending(active);
        active.cursor++;
        if (OfflineChunkNbtReader.classify(tag) != OfflineChunkNbtReader.ChunkStatusClass.FULL) {
            absentUntil.put(chunkKey, tick + 100);
            while (absentUntil.size() > 2048) absentUntil.remove(absentUntil.keySet().iterator().next());
            return;
        }
        // Freeze both snapshots on the server thread before their palette is read by the worker.
        CompoundTag owned = tag.orElseThrow().copy();
        CompoundTag ownedNorth = OfflineChunkNbtReader.classify(north) == OfflineChunkNbtReader.ChunkStatusClass.FULL
                ? north.orElseThrow().copy() : null;
        int minY = active.level.getMinBuildHeight();
        int maxY = active.level.getMaxBuildHeight();
        active.decoding.incrementAndGet();
        try {
            decodeWorker.execute(() -> {
                int[] pixels;
                try { pixels = SharedMapSavedChunk.read(owned, ownedNorth, chunkX, chunkZ, minY, maxY); }
                catch (RuntimeException unavailable) { pixels = new int[0]; }
                if (closed || !completed.offer(new Result(active.key, active, local, pixels, false))) active.decoding.decrementAndGet();
            });
        } catch (RejectedExecutionException busy) {
            active.decoding.decrementAndGet(); // bounded overload retries when this viewport is requested again
        }
    }

    private static Optional<CompoundTag> completedTag(CompletableFuture<Optional<CompoundTag>> future) {
        if (!future.isDone()) return Optional.empty();
        try { return future.getNow(Optional.empty()); }
        catch (RuntimeException unavailable) { return Optional.empty(); }
    }

    private void clearPending(TileWork active) {
        if (active.pending == null) return;
        active.pending = null;
        // Detaching a timed-out, discarded or now-loaded view does not complete its shared IO.
    }

    private void drainCompleted() {
        for (int i = 0; i < 16; i++) {
            Result result = completed.poll();
            if (result == null) return;
            if (result.prediction) {
                if (work.get(result.key) != result.work || result.work.predictionCancelled
                        || tick - result.work.touched > WORK_TTL_TICKS) continue;
                int[] merged = SharedMapTerrainRenderer.mergePrediction(predictionCache.get(result.key), result.pixels);
                predictionCache.put(result.key, merged);
                while (predictionCache.size() > 128) predictionCache.remove(predictionCache.keySet().iterator().next());
                TileWork active = work.get(result.key);
                if (active != null) deliverPrediction(active, merged);
            } else {
                TileWork active = result.work;
                active.decoding.decrementAndGet();
                if (work.get(result.key) != active) continue;
                ChunkPos at = location(result.key, result.localChunk);
                if (result.key.bps > 1) {
                    int[] fresh = captureLoadedPixel(active, result.localChunk);
                    if (fresh == null && result.pixels.length == 256) {
                        savedColors.put(new ChunkKey(result.key.dimension, at.x, at.z), result.pixels);
                        while (savedColors.size() > 1024) savedColors.remove(savedColors.keySet().iterator().next());
                    }
                    deliver(active, result.localChunk, fresh == null ? sampledPixel(result.key, result.localChunk, result.pixels) : fresh);
                } else {
                    int[] fresh = captureLoaded(active.level, at.x, at.z);
                    deliver(active, result.localChunk, fresh == null ? result.pixels : fresh);
                }
            }
        }
    }

    private void deliverPrediction(TileWork active, int[] pixels) {
        active.subscribers.forEach((id, world) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && player.serverLevel() == active.level) send(player, world, active.key, 0, pixels, true);
        });
    }

    private void deliver(TileWork active, int local, int[] pixels) {
        if (pixels.length != (active.key.bps == 1 ? 256 : 1) || java.util.Arrays.stream(pixels).noneMatch(color -> (color >>> 24) != 0)) return;
        active.subscribers.forEach((id, world) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && player.serverLevel() == active.level) send(player, world, active.key, local, pixels, false);
        });
    }

    private static void send(ServerPlayer player, String world, TileKey key, int local, int[] pixels, boolean prediction) {
        boolean fine = key.bps == 1;
        ModNetwork.CHANNEL.send(player, new SharedMapTerrainPatchPacket(world, key.dimension, key.x, key.z,
                prediction ? 0 : fine ? local % 16 * 16 : local % 256,
                prediction ? 0 : fine ? local / 16 * 16 : local / 256,
                prediction ? 256 : fine ? 16 : 1, prediction ? 256 : fine ? 16 : 1, pixels, prediction, key.bps));
    }

    private static ChunkPos location(TileKey key, int local) {
        if (key.bps == 1) return new ChunkPos(key.x * 16 + local % 16, key.z * 16 + local / 16);
        return new ChunkPos(Math.floorDiv(SharedMapTileGrid.sample(key.x, local % 256, key.bps), 16),
                Math.floorDiv(SharedMapTileGrid.sample(key.z, local / 256, key.bps), 16));
    }

    private static int[] sampledPixel(TileKey key, int local, int[] chunkPixels) {
        if (chunkPixels.length != 256) return new int[0];
        int x = SharedMapTileGrid.sample(key.x, local % 256, key.bps);
        int z = SharedMapTileGrid.sample(key.z, local / 256, key.bps);
        return new int[]{chunkPixels[Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16)]};
    }

    private static int[] captureLoadedPixel(TileWork active, int local) {
        int x = SharedMapTileGrid.sample(active.key.x, local % 256, active.key.bps);
        int z = SharedMapTileGrid.sample(active.key.z, local / 256, active.key.bps);
        if (active.level.getChunkSource().getChunkNow(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) == null) return null;
        return renderLoaded(active.level, x, z, 1, 1);
    }

    private void deliverChunkSamples(TileWork active, int[] indices, int[] chunkPixels) {
        int minX = indices[0] % 256, minZ = indices[0] / 256;
        int width = indices[indices.length - 1] % 256 - minX + 1;
        int height = indices[indices.length - 1] / 256 - minZ + 1;
        int[] pixels = new int[indices.length];
        for (int i = 0; i < indices.length; i++) pixels[i] = sampledPixel(active.key, indices[i], chunkPixels)[0];
        if (java.util.Arrays.stream(pixels).noneMatch(color -> (color >>> 24) != 0)) return;
        active.subscribers.forEach((id, world) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player != null && player.serverLevel() == active.level) ModNetwork.CHANNEL.send(player,
                    new SharedMapTerrainPatchPacket(world, active.key.dimension, active.key.x, active.key.z,
                            minX, minZ, width, height, pixels, false, active.key.bps));
        });
    }

    /** Called only on the server thread; lookup of the current/northern chunk is explicitly non-loading. */
    private static int[] captureLoaded(ServerLevel level, int chunkX, int chunkZ) {
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) return null;
        return renderLoaded(level, chunkX * 16, chunkZ * 16, 16, 16);
    }

    private static int[] renderLoaded(ServerLevel level, int startX, int startZ, int width, int height) {
        return SharedMapTerrainRenderer.render((x, z, bps) -> {
            if (level.getChunkSource().getChunkNow(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) == null) return Optional.empty();
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            if (y < level.getMinBuildHeight()) return Optional.empty();
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            boolean water = MapBlockColors.isWaterSurface(state);
            int depth = 0;
            if (water) {
                for (int below = y; below >= level.getMinBuildHeight(); below--) {
                    if (!MapBlockColors.isWaterSurface(level.getBlockState(new BlockPos(x, below, z)))) break;
                    depth++;
                }
            }
            return Optional.of(new SurfaceSample(y, state, water, depth));
        }, startX, startZ, width, height);
    }

    private void discard(TileKey key) {
        TileWork removed = work.remove(key);
        if (removed != null) {
            removed.predictionCancelled = true;
            clearPending(removed);
        }
        predictions.cancel(key.toString());
    }

    private void close() {
        closed = true;
        readBudget.close();
        predictions.shutdown();
        predictionWorker.shutdownNow();
        decodeWorker.shutdownNow();
        work.clear();
        completed.clear();
        changedChunks.clear();
        predictionCache.clear();
        savedColors.clear();
        absentUntil.clear();
        samplers.clear();
    }
}
