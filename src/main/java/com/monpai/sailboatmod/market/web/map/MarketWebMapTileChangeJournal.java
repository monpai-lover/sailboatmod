package com.monpai.sailboatmod.market.web.map;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** A bounded, runtime-only invalidation log. PNGs remain the authoritative durable cache. */
public final class MarketWebMapTileChangeJournal {
    public record Tile(String kind, String dimension, int zoom, int x, int z, long revision) {}
    public record Changes(String session, String dimension, long revision, boolean reset, List<Tile> tiles) {}

    private final int capacity;
    private final String session = UUID.randomUUID().toString();
    private final ArrayDeque<Tile> changes = new ArrayDeque<>();
    private long revision;

    public MarketWebMapTileChangeJournal(int capacity) {
        this.capacity = Math.max(1, Math.min(4096, capacity));
    }

    public String session() { return session; }
    public synchronized long revision() { return revision; }
    synchronized int retainedChanges() { return changes.size(); }

    public synchronized void record(String kind, String dimension, int zoom, int x, int z) {
        changes.addLast(new Tile(kind, dimension, zoom, x, z, ++revision));
        while (changes.size() > capacity) changes.removeFirst();
    }

    public synchronized Changes changes(String dimension, long since, String knownSession) {
        long oldest = changes.isEmpty() ? revision + 1 : changes.getFirst().revision();
        boolean reset = !session.equals(knownSession) || since < oldest - 1 || since > revision || since < 0;
        if (reset) return new Changes(session, dimension, revision, true, List.of());
        var latest = new LinkedHashMap<String, Tile>();
        for (Tile tile : changes) {
            if (tile.revision() > since && tile.dimension().equals(dimension)) {
                latest.put(tile.kind() + ":" + tile.zoom() + ":" + tile.x() + ":" + tile.z(), tile);
            }
        }
        return new Changes(session, dimension, revision, false, List.copyOf(latest.values()));
    }
}
