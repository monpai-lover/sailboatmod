package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.roadplanner.SharedMapTerrainPatchPacket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Server-thread outbox. Immutable published rasters are shared by subscribers, not copied per subscriber. */
final class SharedMapSendQueue {
    static final int MAX_PENDING = 512;
    static final int MAX_PER_PLAYER = 128;
    static final long MAX_RETAINED_BYTES = 16L * 1024 * 1024;
    static final long MAX_PLAYER_BYTES = 4L * 1024 * 1024;
    static final int BYTES_PER_TICK = 256 * 1024;
    static final int PLAYER_BYTES_PER_TICK = 64 * 1024;
    static final int PACKETS_PER_TICK = 64;
    static final long TTL_TICKS = 200;
    // Upper bound for two UTF-8 identifiers and fixed fields, included in the transport budget.
    private static final int HEADER_BYTES = 1024;
    private final LinkedHashMap<Key, Transfer> pending = new LinkedHashMap<>();
    private final Map<UUID, Long> playerBytes = new HashMap<>();
    private final Map<UUID, Integer> playerCounts = new HashMap<>();
    private long retainedBytes;
    private long lastDrain;
    private boolean drained;

    record Target(UUID player, String world, String dimension, int tileX, int tileZ, int bps) { }
    private record Key(Target target, int x, int z, int width, int height, boolean prediction) { }
    private static final class Transfer {
        final Key key;
        final int[] pixels;
        final long created;
        int row;
        Transfer(Key key, int[] pixels, long created) { this.key = key; this.pixels = pixels; this.created = created; }
        long bytes() { return pixels.length * (long) Integer.BYTES; }
    }

    /** Caller transfers an immutable raster reference. It must never mutate pixels after publication. */
    void offer(Target target, int x, int z, int width, int height, int[] pixels, boolean prediction, long tick) {
        if (target == null || target.player == null || pixels == null || width < 1 || height < 1
                || width > 256 || height > 256 || pixels.length != (long) width * height
                || x < 0 || z < 0 || x + (long) width > 256 || z + (long) height > 256
                || prediction && (x != 0 || z != 0 || width != 256 || height != 256)
                || !prediction && pixels.length > 256) throw new IllegalArgumentException("Invalid outgoing terrain");
        Key key = new Key(target, x, z, width, height, prediction);
        Transfer old = pending.get(key);
        if (old != null && old.pixels == pixels) return; // A retry must not restart an in-progress tile.
        if (old != null) remove(key);
        long bytes = pixels.length * (long) Integer.BYTES;
        while (playerCounts.getOrDefault(target.player, 0) >= MAX_PER_PLAYER
                || playerBytes.getOrDefault(target.player, 0L) + bytes > MAX_PLAYER_BYTES) {
            Key oldest = pending.keySet().stream().filter(k -> k.target.player.equals(target.player)).findFirst().orElse(null);
            if (oldest == null) return;
            remove(oldest);
        }
        while (pending.size() >= MAX_PENDING || retainedBytes + bytes > MAX_RETAINED_BYTES) {
            Iterator<Key> keys = pending.keySet().iterator();
            if (!keys.hasNext()) return;
            remove(keys.next());
        }
        Transfer transfer = new Transfer(key, pixels, tick);
        pending.put(key, transfer);
        retainedBytes += bytes;
        playerBytes.merge(target.player, bytes, Long::sum);
        playerCounts.merge(target.player, 1, Integer::sum);
    }

    /** One global budget per server tick, not once per dimension. Skipped transfers rotate to the back. */
    void drain(long tick, Predicate<Target> available, BiConsumer<Target, SharedMapTerrainPatchPacket> sink) {
        drainWithStatus(tick, target -> available.test(target) ? 1 : -1, sink);
    }

    /** -1 drops an obsolete/disconnected view, 0 pauses backpressure, 1 admits one fragment. */
    void drainWithStatus(long tick, ToIntFunction<Target> status, BiConsumer<Target, SharedMapTerrainPatchPacket> sink) {
        if (drained && lastDrain == tick) return;
        lastDrain = tick;
        drained = true;
        Map<UUID, Integer> used = new HashMap<>();
        Map<UUID, Integer> packets = new HashMap<>();
        int total = 0, sent = 0, attempts = pending.size();
        while (attempts-- > 0 && sent < PACKETS_PER_TICK && !pending.isEmpty()) {
            Key key = pending.keySet().iterator().next();
            Transfer transfer = pending.get(key);
            if (tick - transfer.created >= TTL_TICKS) { remove(key); continue; }
            int availability = status.applyAsInt(key.target);
            if (availability < 0) { remove(key); continue; }
            if (availability == 0) { rotate(key, transfer); continue; }
            int rows = Math.min(key.height - transfer.row, SharedMapTerrainPatchPacket.MAX_PIXELS / key.width);
            int cost = rows * key.width * Integer.BYTES + HEADER_BYTES;
            UUID player = key.target.player;
            if (total + cost > BYTES_PER_TICK || used.getOrDefault(player, 0) + cost > PLAYER_BYTES_PER_TICK
                    || packets.getOrDefault(player, 0) >= 16) { rotate(key, transfer); continue; }
            // Only one small fragment is materialized; never allocate a list of all fragments.
            int[] fragment = key.prediction
                    ? Arrays.copyOfRange(transfer.pixels, transfer.row * key.width, (transfer.row + rows) * key.width)
                    : transfer.pixels;
            Target target = key.target;
            SharedMapTerrainPatchPacket packet = new SharedMapTerrainPatchPacket(target.world, target.dimension,
                    target.tileX, target.tileZ, key.x, key.z + transfer.row, key.width, rows, fragment, key.prediction, target.bps);
            try { sink.accept(target, packet); }
            catch (RuntimeException disconnected) { remove(key); continue; }
            total += cost;
            sent++;
            used.merge(player, cost, Integer::sum);
            packets.merge(player, 1, Integer::sum);
            transfer.row += rows;
            if (transfer.row == key.height) remove(key); else rotate(key, transfer);
        }
    }

    private void rotate(Key key, Transfer transfer) { pending.remove(key); pending.put(key, transfer); }
    private void remove(Key key) {
        Transfer old = pending.remove(key);
        if (old == null) return;
        retainedBytes -= old.bytes();
        UUID id = key.target.player;
        playerBytes.computeIfPresent(id, (ignored, count) -> count == old.bytes() ? null : count - old.bytes());
        playerCounts.computeIfPresent(id, (ignored, count) -> count == 1 ? null : count - 1);
    }
    void clear() { pending.clear(); playerBytes.clear(); playerCounts.clear(); retainedBytes = 0; drained = false; }
    int size() { return pending.size(); }
    long retainedBytes() { return retainedBytes; }
}
