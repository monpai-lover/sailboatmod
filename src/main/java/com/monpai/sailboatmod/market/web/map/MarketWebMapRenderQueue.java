package com.monpai.sailboatmod.market.web.map;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MarketWebMapRenderQueue {
    private static final long TASK_TTL_MILLIS = 120_000L;

    private final int maxTasks;
    private final LinkedHashMap<Key, Task> tasks = new LinkedHashMap<>();

    public MarketWebMapRenderQueue(int maxTasks) {
        this.maxTasks = Math.max(1, maxTasks);
    }

    public synchronized boolean enqueue(String dimensionId, int chunkX, int chunkZ, long nowMillis) {
        return enqueue(dimensionId, chunkX, chunkZ, nowMillis, MarketWebMapTileQuality.SERVER_LOADED_CHUNK);
    }

    public synchronized boolean enqueue(String dimensionId,
                                        int chunkX,
                                        int chunkZ,
                                        long nowMillis,
                                        MarketWebMapTileQuality quality) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return false;
        }
        prune(nowMillis);
        Key key = new Key(dimensionId, chunkX, chunkZ);
        MarketWebMapTileQuality safeQuality = quality == null ? MarketWebMapTileQuality.SERVER_LOADED_CHUNK : quality;
        Task existing = tasks.get(key);
        if (existing != null) {
            if (safeQuality.priority() > existing.quality().priority()) {
                tasks.put(key, new Task(dimensionId, chunkX, chunkZ, nowMillis, safeQuality));
                return true;
            }
            return false;
        }
        tasks.put(key, new Task(dimensionId, chunkX, chunkZ, nowMillis, safeQuality));
        while (tasks.size() > maxTasks) {
            Iterator<Key> iterator = tasks.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    public synchronized List<Task> poll(int budget, long nowMillis) {
        prune(nowMillis);
        int limit = Math.max(0, budget);
        List<Task> out = new ArrayList<>(Math.min(limit, tasks.size()));
        Iterator<Map.Entry<Key, Task>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext() && out.size() < limit) {
            Map.Entry<Key, Task> entry = iterator.next();
            out.add(entry.getValue());
            iterator.remove();
        }
        return out;
    }

    public synchronized List<Task> pollCoalesced(int minimumBudget, int sameTileBurstLimit, long nowMillis) {
        prune(nowMillis);
        int minimum = Math.max(0, minimumBudget);
        int burstLimit = Math.max(minimum, sameTileBurstLimit);
        List<Task> out = new ArrayList<>(Math.min(burstLimit, tasks.size()));
        Iterator<Map.Entry<Key, Task>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext() && out.size() < minimum) {
            Map.Entry<Key, Task> entry = iterator.next();
            out.add(entry.getValue());
            iterator.remove();
        }
        if (out.isEmpty() || out.size() >= burstLimit) {
            return out;
        }
        Task first = out.get(0);
        MarketWebMapTileCoordinate.Tile firstTile = MarketWebMapTileCoordinate.baseTileForChunk(first.chunkX(), first.chunkZ());
        iterator = tasks.entrySet().iterator();
        while (iterator.hasNext() && out.size() < burstLimit) {
            Map.Entry<Key, Task> entry = iterator.next();
            Task task = entry.getValue();
            if (!Objects.equals(task.dimensionId(), first.dimensionId())) {
                continue;
            }
            MarketWebMapTileCoordinate.Tile tile = MarketWebMapTileCoordinate.baseTileForChunk(task.chunkX(), task.chunkZ());
            if (tile.equals(firstTile)) {
                out.add(task);
                iterator.remove();
            }
        }
        return out;
    }

    public synchronized int size() {
        return tasks.size();
    }

    private void prune(long nowMillis) {
        tasks.entrySet().removeIf(entry -> nowMillis - entry.getValue().createdAtMillis() > TASK_TTL_MILLIS);
    }

    private record Key(String dimensionId, int chunkX, int chunkZ) {
        private Key {
            dimensionId = dimensionId == null ? "" : dimensionId;
        }
    }

    public record Task(String dimensionId,
                       int chunkX,
                       int chunkZ,
                       long createdAtMillis,
                       MarketWebMapTileQuality quality) {
        public Task {
            dimensionId = Objects.requireNonNullElse(dimensionId, "");
            quality = quality == null ? MarketWebMapTileQuality.SERVER_LOADED_CHUNK : quality;
        }
    }
}
