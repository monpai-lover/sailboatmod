package com.monpai.sailboatmod.market.web.map;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Optional;

/** Bounded immutable decoded tiles, validated against the bytes actually read from disk. */
final class MarketWebMapDecodedTileCache {
    private final long maximumBytes;
    private final LinkedHashMap<Path, Entry> entries = new LinkedHashMap<>(16, 0.75F, true);
    private long retainedBytes;

    MarketWebMapDecodedTileCache(long maximumBytes) {
        this.maximumBytes = Math.max(0, maximumBytes);
    }

    Optional<int[]> find(Path path, byte[] actualPng) {
        Entry entry;
        synchronized (this) {
            entry = matchingEntry(path, actualPng);
        }
        return entry == null ? Optional.empty() : Optional.of(entry.pixels().clone());
    }

    boolean matches(Path path, byte[] actualPng, int[] pixels) {
        Entry entry;
        synchronized (this) {
            entry = matchingEntry(path, actualPng);
        }
        return entry != null && Arrays.equals(entry.pixels(), pixels);
    }

    void remember(Path path, byte[] encoded, int[] pixels) {
        if (path == null || encoded == null || pixels == null) return;
        Path key = normalize(path);
        long bytes = (long) pixels.length * Integer.BYTES + encoded.length;
        if (bytes > maximumBytes) {
            remove(key);
            return;
        }
        Entry entry = new Entry(encoded.clone(), pixels.clone(), bytes);
        synchronized (this) {
            remove(key);
            entries.put(key, entry);
            retainedBytes += bytes;
            while (retainedBytes > maximumBytes) remove(entries.keySet().iterator().next());
        }
    }

    synchronized void remove(Path path) {
        if (path == null) return;
        Entry removed = entries.remove(normalize(path));
        if (removed != null) retainedBytes -= removed.bytes();
    }

    synchronized long retainedBytes() { return retainedBytes; }

    /** Caller holds this monitor; no file IO or user callback is performed here. */
    private Entry matchingEntry(Path path, byte[] actualPng) {
        if (path == null) return null;
        Path key = normalize(path);
        Entry entry = entries.get(key);
        if (entry != null && !Arrays.equals(entry.encoded(), actualPng)) {
            remove(key);
            return null;
        }
        return entry;
    }

    private static Path normalize(Path path) { return path.toAbsolutePath().normalize(); }
    private record Entry(byte[] encoded, int[] pixels, long bytes) { }
}
