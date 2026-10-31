package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.Prediction;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers;
import com.monpai.sailboatmod.route.water.lookahead.LoadedChunkWaterColumns;
import com.monpai.sailboatmod.util.OfflineChunkPalette;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static com.monpai.sailboatmod.route.water.PortBerthValidator.*;

/** Request-local, real-first port sampler. Capture on the server thread; resolve on a worker. */
public final class PortTerrainProbe {
    private static final int MAX_LIVE_CHUNKS = 128;
    private static final int MAX_DISK_READS = 128;
    private final ServerLevel level;
    private final int minY, maxY, seaY;
    // A present null value means loaded but not captured within budget: never replace it with prediction.
    private final Map<Long, NbtChunkWaterReader.ChunkColumns> live = new HashMap<>();
    private final Map<Long, Disk> disk = new HashMap<>();
    private final java.util.Set<Long> liveChecked = new java.util.HashSet<>();
    private final Map<Long, NbtChunkWaterReader.ChunkColumns> predicted = new HashMap<>();
    private final Map<Long, Boolean> ready = new HashMap<>();
    private final TerrainPredictor predictor;
    private final TerrainSurfaceSampler surface;
    private long deadline;
    private BooleanSupplier cancelled = () -> false;
    private boolean detailed;
    private int diskReads;

    private record Disk(NbtChunkWaterReader.ChunkColumns columns, boolean absent, Origin origin) {
        static Disk unavailable() { return new Disk(null, false, Origin.NONE); }
    }
    private record Owned(CompoundTag tag, NbtChunkWaterReader.ChunkColumns loaded) {}

    private PortTerrainProbe(ServerLevel level, TerrainPredictor predictor) {
        this.level = level;
        this.minY = level.getMinBuildHeight();
        this.maxY = level.getMaxBuildHeight();
        this.seaY = level.getSeaLevel();
        this.predictor = predictor;
        this.surface = TellusSurfaceSamplers.forLevel(level).orElse(null);
    }

    public static PortTerrainProbe capture(ServerLevel level, List<Zone> zones, TerrainPredictor predictor) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Port capture requires server thread");
        PortTerrainProbe probe = new PortTerrainProbe(level, predictor);
        // Metadata-only construction. Live columns are copied lazily in budgeted tick slices.
        return probe;
    }

    /** Start the wall-clock budget when a worker actually begins, not while it is queued. */
    public PortTerrainProbe start(boolean detailed, long budgetMillis, BooleanSupplier cancelled) {
        if (level.getServer().isSameThread()) throw new IllegalStateException("Port sampling must run off-thread");
        this.detailed = detailed;
        this.deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, budgetMillis));
        this.cancelled = cancelled == null ? () -> false : cancelled;
        return this;
    }

    public boolean expired() {
        return Thread.currentThread().isInterrupted() || cancelled.getAsBoolean() || System.nanoTime() >= deadline;
    }

    public Result resolve(Zone zone) {
        WaterRouteCancellation.check();
        return PortBerthValidator.resolve(zone, this::sample, this::expired);
    }

    /** Retain real/ready data, but allow a later worker slice to retry unavailable terrain. */
    public void retryPending() {
        ready.entrySet().removeIf(e -> !e.getValue());
        live.entrySet().removeIf(e -> { if (e.getValue() == null) { liveChecked.remove(e.getKey()); return true; } return false; });
        disk.entrySet().removeIf(e -> e.getValue().columns == null && !e.getValue().absent);
    }

    private Cell sample(int x, int z) {
        if (expired()) return Cell.unknown();
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        if (liveChecked.add(key)) {
            var capture = PortChunkSnapshot.capture(level, x >> 4, z >> 4);
            try {
                var snapshot = capture.get(Math.max(1, Math.min(1000, waitMillis() * 5)), TimeUnit.MILLISECONDS);
                if (snapshot.loaded()) live.put(key, snapshot.columns());
            } catch (InterruptedException interrupted) {
                capture.cancel(false); Thread.currentThread().interrupt(); live.put(key, null);
            } catch (Exception unavailable) { capture.cancel(false); live.put(key, null); }
        }
        Cell loaded = live.containsKey(key) ? column(live.get(key), x, z, Origin.LIVE) : null;
        if (loaded != null) return loaded;
        Disk stored = disk.computeIfAbsent(key, ignored -> readDisk(x >> 4, z >> 4));
        Cell saved = stored.columns == null ? null : column(stored.columns, x, z, stored.origin);
        return PortBerthValidator.choose(null, saved, stored.absent, () -> predict(x, z));
    }

    private Disk readDisk(int cx, int cz) {
        if (expired() || diskReads++ >= MAX_DISK_READS) return Disk.unavailable();
        try {
            // Do not use beginProbe: that convenience API turns IO exceptions into Optional.empty().
            // Only a successful, genuinely absent read may fall back to Tellus.
            Optional<CompoundTag> raw = level.getChunkSource().chunkMap.read(new ChunkPos(cx, cz))
                    .get(waitMillis(), TimeUnit.MILLISECONDS);
            if (raw.isEmpty()) return new Disk(null, true, Origin.NONE);
            // Pending-write tags can also be seen by main-thread data fixing. Own a copy there,
            // then decode off-thread. No global save/flush is scheduled for a menu query.
            var ownership = PortAsyncRuntime.onMain(level.getServer(), () -> new Owned(raw.get().copy(), null));
            Owned owned;
            try {
                owned = ownership.get(waitMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception unavailable) {
                ownership.cancel(false);
                throw unavailable;
            }
            if (owned.loaded != null) return new Disk(owned.loaded, false, Origin.LIVE);
            if (!OfflineChunkPalette.isFullStatus(owned.tag)) return Disk.unavailable();
            return NbtChunkWaterReader.decode(owned.tag, minY, maxY)
                    .map(columns -> new Disk(columns, false, Origin.NBT)).orElseGet(Disk::unavailable);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return Disk.unavailable();
        } catch (Exception unavailable) {
            return Disk.unavailable();
        }
    }

    private Cell predict(int x, int z) {
        if (predictor == null || expired()) return Cell.unknown();
        int cx = x >> 4, cz = z >> 4;
        long key = ChunkPos.asLong(cx, cz);
        try {
            boolean available = ready.computeIfAbsent(key, ignored -> {
                var future = predictor.gate().request(cx, cz);
                return future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled();
            });
            if (!available) return Cell.unknown();
            if (detailed) {
                NbtChunkWaterReader.ChunkColumns columns = predicted.get(key);
                if (columns == null) {
                    Prediction prediction = predictor.predictChunk(cx, cz, waitMillis());
                    if (prediction.status() != Prediction.Status.READY) return Cell.unknown();
                    columns = prediction.columns();
                    predicted.put(key, columns);
                }
                return column(columns, x, z, Origin.TELLUS);
            }
            if (surface == null) return Cell.unknown();
            return surface.sample(x, z, 1).map(s -> new Cell(s.water() ? State.WATER : State.LAND,
                    s.water() && s.surfaceY() == seaY ? seaY - 1 : s.surfaceY(), Origin.TELLUS)).orElseGet(Cell::unknown);
        } catch (RuntimeException | LinkageError unavailable) {
            return Cell.unknown();
        }
    }

    private long waitMillis() {
        return Math.max(1, Math.min(200, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
    }

    private static Cell column(NbtChunkWaterReader.ChunkColumns c, int x, int z, Origin origin) {
        if (c == null) return Cell.unknown();
        int index = (z & 15) * 16 + (x & 15);
        return new Cell(c.water()[index] ? State.WATER : State.LAND, c.surfaceY()[index], origin);
    }

    /** Carry authoritative request snapshots into planning; predicted data never overwrites them. */
    public Map<Long, NbtChunkWaterReader.ChunkColumns> realSnapshots() {
        Map<Long, NbtChunkWaterReader.ChunkColumns> snapshots = new HashMap<>();
        disk.forEach((key, value) -> { if (value.columns != null) snapshots.put(key, value.columns); });
        live.forEach((key, value) -> { if (value != null) snapshots.put(key, value); });
        return Map.copyOf(snapshots);
    }

    public static Zone zone(DockBerthResolver.DockZone zone) {
        return new Zone(zone.dockPos().getX(), zone.dockPos().getY(), zone.dockPos().getZ(),
                zone.minX(), zone.maxX(), zone.minZ(), zone.maxZ());
    }
}
