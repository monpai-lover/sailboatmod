package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.util.OfflineChunkNbtReader;
import com.monpai.sailboatmod.util.OfflineChunkPalette;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import com.monpai.sailboatmod.util.ModChunkLoading;

import java.util.Optional;

/** Read-only water snapshots. FULL and surfaced ProtoChunks retain their saved
 * terrain. Proven pre-noise/all-air ProtoChunks may use Tellus prediction; IO failure,
 * malformed data and unknown generation states never authorize that fallback.
 * Live snapshots/copies are budgeted on the tick thread, disk/decode is asynchronous.
 */
public final class NbtChunkWaterReader {
    private static final int CHUNK = 16;
    private static final String OVERWORLD = "minecraft:overworld";

    private NbtChunkWaterReader() {
    }

    /** 一个区块的判水列:water[256](localZ*16+localX) + 表面 Y[256]。写一次不可变。 */
    public record ChunkColumns(boolean[] water, int[] surfaceY) {
    }

    public enum ReadStatus { READY, ABSENT, UNAVAILABLE, PROTO_TERRAIN, PRE_TERRAIN }
    public enum ReadIssue {
        NONE, IO_TIMEOUT, IO_ERROR, SNAPSHOT_TIMEOUT, SNAPSHOT_ERROR, COPY_TIMEOUT, COPY_ERROR,
        DECODE_ERROR, UNKNOWN_STATUS, INCOMPLETE_TERRAIN, UNEXPECTED_EARLY_BLOCKS, UNSUPPORTED_DIMENSION, NOT_LOADED
    }
    public record ReadResult(ReadStatus status, ChunkColumns columns, String generationStatus, ReadIssue issue) {
        public ReadResult(ReadStatus status, ChunkColumns columns) { this(status, columns, "", ReadIssue.NONE); }
        public static ReadResult ready(ChunkColumns c) { return new ReadResult(ReadStatus.READY, c, "full", ReadIssue.NONE); }
        public static ReadResult absent() { return new ReadResult(ReadStatus.ABSENT, null, "absent", ReadIssue.NONE); }
        public static ReadResult unavailable() { return unavailable(ReadIssue.NONE, ""); }
        public static ReadResult unavailable(ReadIssue reason, String status) { return new ReadResult(ReadStatus.UNAVAILABLE, null, status, reason); }
        public boolean hasTerrain() { return status == ReadStatus.READY || status == ReadStatus.PROTO_TERRAIN; }
        public boolean allowsPrediction() { return status == ReadStatus.ABSENT || status == ReadStatus.PRE_TERRAIN; }
    }

    public static Optional<ChunkColumns> readOffThread(ServerLevel level, int cx, int cz, long timeoutMs) {
        ReadResult result = readForRouting(level, cx, cz, timeoutMs);
        return result.hasTerrain() ? Optional.of(result.columns()) : Optional.empty();
    }

    /** Compatibility entry for older worker-only callers. The planner uses the
     * async entry below directly; it never spends a terrain worker waiting on IO. */
    public static ReadResult readForRouting(ServerLevel level, int cx, int cz, long timeoutMs) {
        if (level == null) return ReadResult.absent();
        if (!OVERWORLD.equals(level.dimension().location().toString()))
            return ReadResult.unavailable(ReadIssue.UNSUPPORTED_DIMENSION, "");
        if (level.getServer().isSameThread()) {
            var loaded = level.getChunkSource().getChunkNow(cx, cz);
            return loaded == null ? ReadResult.unavailable(ReadIssue.NOT_LOADED, "") : ReadResult.ready(
                    com.monpai.sailboatmod.route.water.lookahead.LoadedChunkWaterColumns.capture(loaded));
        }
        WaterRouteCancellation.check();
        // Legacy worker callers may already own a PortIO slot. Decode on that worker,
        // never block it waiting for another task on the same bounded pool.
        java.util.concurrent.CompletableFuture<?> pending = null;
        ReadIssue timeoutIssue = ReadIssue.SNAPSHOT_TIMEOUT, errorIssue = ReadIssue.SNAPSHOT_ERROR;
        try {
            var capture = PortChunkSnapshot.capture(level, cx, cz); pending = capture;
            var live = capture.get(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (live.loaded()) return live.columns() == null ? ReadResult.unavailable(errorIssue, "changed") : ReadResult.ready(live.columns());
            timeoutIssue = ReadIssue.IO_TIMEOUT; errorIssue = ReadIssue.IO_ERROR;
            var read = level.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)).thenApply(raw -> raw); pending = read;
            var raw = read.get(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (raw.isEmpty()) return ReadResult.absent();
            timeoutIssue = ReadIssue.COPY_TIMEOUT; errorIssue = ReadIssue.COPY_ERROR;
            var copy = PortAsyncRuntime.onMain(level.getServer(), () -> raw.get().copy()); pending = copy;
            var owned = copy.get(Math.max(1L, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            WaterRouteCancellation.check();
            return decodeForRouting(owned, level.getMinBuildHeight(), level.getMaxBuildHeight());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); WaterRouteCancellation.check();
            return ReadResult.unavailable(errorIssue, "interrupted");
        } catch (java.util.concurrent.TimeoutException timeout) {
            return ReadResult.unavailable(timeoutIssue, "");
        } catch (java.util.concurrent.ExecutionException failed) {
            return ReadResult.unavailable(errorIssue, "");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    /** One retained request: loaded snapshot -> disk -> safe owned copy -> worker
     * decode. No get/join in this pipeline, no save/force-generation. Cancellation
     * cancels only request-owned dependants, never the shared vanilla disk read. */
    public static java.util.concurrent.CompletableFuture<ReadResult> readForRoutingAsync(
            ServerLevel level, int cx, int cz, long timeoutMs) {
        if (level == null) return java.util.concurrent.CompletableFuture.completedFuture(ReadResult.absent());
        if (!OVERWORLD.equals(level.dimension().location().toString()))
            return java.util.concurrent.CompletableFuture.completedFuture(ReadResult.unavailable(ReadIssue.UNSUPPORTED_DIMENSION, ""));
        var result = new java.util.concurrent.CompletableFuture<ReadResult>();
        var active = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<?>>();
        long timeout = Math.max(1L, timeoutMs);
        java.util.function.Consumer<java.util.concurrent.CompletableFuture<?>> track = future -> {
            active.set(future); if (result.isDone()) future.cancel(true);
        };
        result.whenComplete((v, e) -> {
            var future = active.get(); if (future != null && !future.isDone()) future.cancel(true);
        });
        result.completeOnTimeout(ReadResult.unavailable(ReadIssue.IO_TIMEOUT, "pipeline"),
                timeout * 4L, java.util.concurrent.TimeUnit.MILLISECONDS);
        var live = PortChunkSnapshot.capture(level, cx, cz);
        track.accept(live);
        live.orTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS).whenComplete((capture, failure) -> {
            if (result.isDone()) return;
            if (failure != null) { result.complete(readFailure(failure, ReadIssue.SNAPSHOT_TIMEOUT, ReadIssue.SNAPSHOT_ERROR)); return; }
            if (capture.loaded()) {
                result.complete(capture.columns() == null ? ReadResult.unavailable(ReadIssue.SNAPSHOT_ERROR, "changed")
                        : ReadResult.ready(capture.columns())); return;
            }
            // Identity dependant is private to this request. orTimeout must not mutate
            // or cancel the original future returned by ChunkMap/IOWorker.
            var disk = level.getChunkSource().chunkMap.read(new ChunkPos(cx, cz)).thenApply(raw -> raw);
            track.accept(disk);
            disk.orTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS).whenComplete((raw, diskFailure) -> {
                if (result.isDone()) return;
                if (diskFailure != null) { result.complete(readFailure(diskFailure, ReadIssue.IO_TIMEOUT, ReadIssue.IO_ERROR)); return; }
                if (raw.isEmpty()) { result.complete(ReadResult.absent()); return; }
                var copy = PortAsyncRuntime.onMain(level.getServer(), () -> raw.get().copy());
                track.accept(copy);
                copy.orTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS).whenComplete((owned, copyFailure) -> {
                    if (result.isDone()) return;
                    if (copyFailure != null) { result.complete(readFailure(copyFailure, ReadIssue.COPY_TIMEOUT, ReadIssue.COPY_ERROR)); return; }
                    var decoded = PortAsyncRuntime.worker(level.getServer(), () ->
                            decodeForRouting(owned, level.getMinBuildHeight(), level.getMaxBuildHeight()));
                    track.accept(decoded);
                    decoded.whenComplete((value, decodeFailure) -> {
                        if (result.isDone()) return;
                        result.complete(decodeFailure == null ? value : ReadResult.unavailable(ReadIssue.DECODE_ERROR, ""));
                    });
                }).exceptionally(callbackFailure -> {
                    result.complete(ReadResult.unavailable(ReadIssue.DECODE_ERROR, "callback")); return null;
                });
            }).exceptionally(callbackFailure -> {
                result.complete(ReadResult.unavailable(ReadIssue.COPY_ERROR, "callback")); return null;
            });
        }).exceptionally(callbackFailure -> {
            result.complete(ReadResult.unavailable(ReadIssue.IO_ERROR, "callback")); return null;
        });
        return result;
    }
    private static ReadResult readFailure(Throwable failure, ReadIssue timeout, ReadIssue other) {
        while (failure.getCause() != null && (failure instanceof java.util.concurrent.CompletionException
                || failure instanceof java.util.concurrent.ExecutionException)) failure = failure.getCause();
        return ReadResult.unavailable(failure instanceof java.util.concurrent.TimeoutException ? timeout : other, "");
    }

    /** Pure decode of an owned tag. ProtoChunk is not synonymous with missing/corrupt.
     * At SURFACE and later, use the blocks already present (provisional until FULL).
     * Before NOISE, prediction is allowed ONLY if all saved block palettes are air.
     * Inconsistent/unknown states, broken packed arrays and partially formed NOISE
     * remain unknown; they can neither authorize prediction nor invent dry land. */
    static ReadResult decodeForRouting(CompoundTag tag, int minY, int maxY) {
        if (tag == null || maxY <= minY) return ReadResult.unavailable(ReadIssue.DECODE_ERROR, "");
        CompoundTag data = tag.contains("Level", Tag.TAG_COMPOUND) ? tag.getCompound("Level") : tag;
        String status = data.getString("Status");
        if (status.startsWith("minecraft:")) status = status.substring(10);
        if (!java.util.Set.of("empty", "structure_starts", "structure_references", "biomes", "noise",
                "surface", "carvers", "features", "initialize_light", "light", "spawn", "full").contains(status))
            return ReadResult.unavailable(ReadIssue.UNKNOWN_STATUS, "unknown");
        if (!validSections(data)) return ReadResult.unavailable(ReadIssue.DECODE_ERROR, status);
        if (java.util.Set.of("empty", "structure_starts", "structure_references", "biomes").contains(status)) {
            if (!allAirPalettes(data)) return ReadResult.unavailable(ReadIssue.UNEXPECTED_EARLY_BLOCKS, status);
            return new ReadResult(ReadStatus.PRE_TERRAIN, null, status, ReadIssue.NONE);
        }
        if (status.equals("noise")) return ReadResult.unavailable(ReadIssue.INCOMPLETE_TERRAIN, status);
        try {
            var blocks = OfflineChunkPalette.decodeTerrainSnapshot(tag, minY, maxY);
            Optional<ChunkColumns> columns = decodeColumns(blocks, minY);
            if (columns.isEmpty()) return ReadResult.unavailable(ReadIssue.DECODE_ERROR, status);
            if (!status.equals("full")) for (int height : columns.get().surfaceY())
                if (height < minY) return ReadResult.unavailable(ReadIssue.INCOMPLETE_TERRAIN, status);
            return new ReadResult(status.equals("full") ? ReadStatus.READY : ReadStatus.PROTO_TERRAIN,
                    columns.get(), status, ReadIssue.NONE);
        } catch (RuntimeException invalid) { return ReadResult.unavailable(ReadIssue.DECODE_ERROR, status); }
    }
    private static boolean allAirPalettes(CompoundTag data) {
        var sections = data.getList("sections", Tag.TAG_COMPOUND);
        for (int i=0; i<sections.size(); i++) {
            var palette = sections.getCompound(i).getCompound("block_states").getList("palette", Tag.TAG_COMPOUND);
            for (int j=0; j<palette.size(); j++) {
                String name = palette.getCompound(j).getString("Name");
                if (!name.equals("minecraft:air") && !name.equals("minecraft:cave_air") && !name.equals("minecraft:void_air")) return false;
            }
        }
        return true;
    }
    private static boolean validSections(CompoundTag data) {
        if (data.contains("sections") && !data.contains("sections", Tag.TAG_LIST)) return false;
        var sections = data.getList("sections", Tag.TAG_COMPOUND);
        java.util.Set<Integer> ys = new java.util.HashSet<>();
        for (int i=0; i<sections.size(); i++) {
            var section = sections.getCompound(i);
            if (!section.contains("Y", Tag.TAG_ANY_NUMERIC) || !ys.add((int)section.getByte("Y"))) return false;
            if (!section.contains("block_states")) continue; // pre-noise biome-only section
            if (!section.contains("block_states", Tag.TAG_COMPOUND)) return false;
            var states = section.getCompound("block_states");
            var palette = states.getList("palette", Tag.TAG_COMPOUND);
            if (palette.isEmpty() || palette.size() > 4096) return false;
            for (int j=0; j<palette.size(); j++) if (palette.getCompound(j).getString("Name").isBlank()) return false;
            if (palette.size() > 1) {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size()-1));
                int perLong = 64 / bits;
                long[] packed = states.getLongArray("data");
                if (packed.length != (4096+perLong-1)/perLong) return false;
                long mask = (1L << bits)-1;
                for (int n=0; n<4096; n++) if (((packed[n/perLong] >>> ((n%perLong)*bits)) & mask) >= palette.size()) return false;
            }
        }
        return true;
    }

    /**
     * <b>主线程</b>:force 加载一个区块后读其 NBT 解出判水列(仅 NBT 后台读不到的极少区块)。force+FULL 后
     * 用统一读取器主线程入口取已落盘 tag;读不到 → empty。完事释放票据。
     */
    public static Optional<ChunkColumns> readForcedOnMainThread(ServerLevel level, int cx, int cz) {
        if (level == null || level.getServer() == null
                || !OVERWORLD.equals(level.dimension().location().toString())) {
            return Optional.empty();
        }
        BlockPos owner = new BlockPos(cx << 4, level.getSeaLevel(), cz << 4);
        try {
            ModChunkLoading.forceChunk(level, SailboatMod.MODID, owner, cx, cz, true, false);
            level.getChunk(cx, cz, ChunkStatus.FULL, true);
            int minY = level.getMinBuildHeight();
            int maxY = level.getMaxBuildHeight();
            // force 后已落盘:主线程入口非阻塞拿 tag(不再 flush,force 已确保落盘)。
            Optional<CompoundTag> tag = OfflineChunkNbtReader.readSafeOnMainThread(
                    level, new ChunkPos(cx, cz), 0L, false);
            return tag.flatMap(value -> decode(value, minY, maxY));
        } catch (Throwable t) {
            return Optional.empty();
        } finally {
            try {
                ModChunkLoading.forceChunk(level, SailboatMod.MODID, owner, cx, cz, false, false);
            } catch (Throwable ignored) {
            }
        }
    }

    // ============================ 以下为纯 NBT 解码 + 判水(无世界访问;口径 byte-for-byte 保留) ============================

    /** 从独占 tag 解出判水列。口径完全同旧版:表面那格(跳噪声)判水。tag 非 full / 无 section → empty。 */
    static Optional<ChunkColumns> decode(CompoundTag chunkTag, int minBuildHeight, int maxBuildHeight) {
        return decodeColumns(OfflineChunkPalette.decode(chunkTag, minBuildHeight, maxBuildHeight), minBuildHeight);
    }

    private static Optional<ChunkColumns> decodeColumns(OfflineChunkPalette.OfflineChunkBlocks chunk, int minBuildHeight) {
        if (chunk.isEmpty()) {
            return Optional.empty();
        }
        boolean[] water = new boolean[CHUNK * CHUNK];
        int[] surfaceY = new int[CHUNK * CHUNK];
        for (int localZ = 0; localZ < CHUNK; localZ++) {
            for (int localX = 0; localX < CHUNK; localX++) {
                int idx = localZ * CHUNK + localX;
                int firstAvailable = chunk.firstAvailableHeight(localX, localZ, OfflineChunkPalette::isDefaultSurfaceNoise);
                if (firstAvailable <= minBuildHeight) {
                    water[idx] = false;
                    surfaceY[idx] = -999;
                    continue;
                }
                int surfY = firstAvailable - 1;
                water[idx] = isWaterColumn(chunk, localX, surfY, localZ);
                surfaceY[idx] = surfY;
            }
        }
        return Optional.of(new ChunkColumns(water, surfaceY));
    }

    /** 表面那格是否可航水体(口径同旧 BlockEntry.water):waterlogged || water/bubble_column || 海草海带。 */
    private static boolean isWaterColumn(OfflineChunkPalette.OfflineChunkBlocks chunk, int localX, int worldY, int localZ) {
        String blockId = chunk.blockId(localX, worldY, localZ);
        CompoundTag entry = chunk.paletteTag(localX, worldY, localZ);
        boolean waterlogged = false;
        if (entry != null && entry.contains("Properties", Tag.TAG_COMPOUND)) {
            waterlogged = "true".equalsIgnoreCase(entry.getCompound("Properties").getString("waterlogged"));
        }
        return WaterSurfaceRules.isNavigableSurface(blockId, waterlogged);
    }
}
