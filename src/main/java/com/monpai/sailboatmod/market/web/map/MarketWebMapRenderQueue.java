package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class MarketWebMapRenderQueue {

    private final int maxTasks;
    private final int reservedLoaded;
    private final LinkedHashMap<Key, Task> tasks = new LinkedHashMap<>();
    private final LinkedHashMap<Key, Task> claimed = new LinkedHashMap<>();
    private final LinkedHashSet<Key> loadedKeys = new LinkedHashSet<>();
    private final LinkedHashSet<Key> reservedKeys = new LinkedHashSet<>();

    public MarketWebMapRenderQueue(int maxTasks) {
        this(maxTasks, 0);
    }

    public MarketWebMapRenderQueue(int maxTasks, int reservedLoaded) {
        this.maxTasks = Math.max(1, maxTasks);
        this.reservedLoaded = Math.max(0, reservedLoaded);
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
        Key key = new Key(dimensionId, chunkX, chunkZ);
        MarketWebMapTileQuality safeQuality = quality == null ? MarketWebMapTileQuality.SERVER_LOADED_CHUNK : quality;
        Task existing = tasks.get(key);
        if (existing != null) {
            if (safeQuality.priority() > existing.quality().priority()) {
                tasks.put(key, new Task(dimensionId, chunkX, chunkZ, nowMillis, safeQuality));
                indexQueued(key, safeQuality);
                return true;
            }
            return false;
        }
        // Accepted tasks survive pauses. Apply backpressure to new discovery instead of
        // silently evicting old work or expiring it while updates are suspended.
        if (!claimed.containsKey(key) && retainedTaskCount() - reservedKeys.size() >= maxTasks) {
            if (safeQuality != MarketWebMapTileQuality.SERVER_LOADED_CHUNK || reservedKeys.size() >= reservedLoaded) { return false; }
            reservedKeys.add(key);
        }
        Task active = claimed.get(key);
        if (active != null && active.quality().priority() > safeQuality.priority()) { safeQuality = active.quality(); }
        tasks.put(key, new Task(dimensionId, chunkX, chunkZ, nowMillis, safeQuality));
        indexQueued(key, safeQuality);
        return true;
    }

    public synchronized List<Task> poll(int budget, long nowMillis) {
        int limit = Math.max(0, budget);
        List<Task> out = new ArrayList<>(Math.min(limit, tasks.size()));
        Iterator<Map.Entry<Key, Task>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext() && out.size() < limit) {
            Map.Entry<Key, Task> entry = iterator.next();
            out.add(entry.getValue());
            loadedKeys.remove(entry.getKey());
            iterator.remove();
            releaseUnretainedReservation(entry.getKey());
        }
        return out;
    }

    public synchronized List<Task> pollTracked(int budget, long nowMillis) {
        int limit = Math.max(0, budget);
        List<Task> out = new ArrayList<>(Math.min(limit, tasks.size()));
        Iterator<Map.Entry<Key, Task>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext() && out.size() < limit) {
            Map.Entry<Key, Task> entry = iterator.next();
            if (claimed.containsKey(entry.getKey())) continue;
            claimed.put(entry.getKey(), entry.getValue());
            out.add(entry.getValue());
            loadedKeys.remove(entry.getKey());
            iterator.remove();
            releaseUnretainedReservation(entry.getKey());
        }
        return out;
    }

    /** Select a small foreground batch without scanning the bulk region queue. */
    public synchronized List<Task> pollTrackedLoaded(int budget, long nowMillis) {
        int limit = Math.max(0, budget);
        List<Task> out = new ArrayList<>(Math.min(limit, loadedKeys.size()));
        Iterator<Key> iterator = loadedKeys.iterator();
        int inspected = 0;
        while (iterator.hasNext() && out.size() < limit && inspected++ < Math.max(32, limit)) {
            Key key = iterator.next();
            iterator.remove();
            Task task = tasks.get(key);
            if (task == null || (task.quality() != MarketWebMapTileQuality.SERVER_LOADED_CHUNK && !reservedKeys.contains(key))
                    || claimed.containsKey(key)) { continue; }
            tasks.remove(key);
            claimed.put(key, task);
            out.add(task);
        }
        return out;
    }

    private void indexQueued(Key key, MarketWebMapTileQuality quality) {
        if ((quality == MarketWebMapTileQuality.SERVER_LOADED_CHUNK || reservedKeys.contains(key)) && !claimed.containsKey(key)) { loadedKeys.add(key); }
        else { loadedKeys.remove(key); }
    }

    /** Admission ownership survives quality upgrades until accepted work is consumed or transferred. */
    public synchronized boolean isReservedForeground(Task task) {
        return task != null && reservedKeys.contains(new Key(task.dimensionId(), task.chunkX(), task.chunkZ()));
    }

    private void releaseUnretainedReservation(Key key) {
        if (!tasks.containsKey(key) && !claimed.containsKey(key)) { reservedKeys.remove(key); }
    }

    /** Release only this submission, after it has registered work or been requeued on deferral. */
    public synchronized void acknowledge(Task task) {
        if (task == null) return;
        Key key = new Key(task.dimensionId(), task.chunkX(), task.chunkZ());
        if (claimed.get(key) == task) {
            claimed.remove(key);
            Task queued = tasks.get(key);
            if (queued != null) { indexQueued(key, queued.quality()); }
            else { releaseUnretainedReservation(key); }
        }
    }

    public synchronized List<Task> pollCoalesced(int minimumBudget, int sameTileBurstLimit, long nowMillis) {
        int minimum = Math.max(0, minimumBudget);
        int burstLimit = Math.max(minimum, sameTileBurstLimit);
        List<Task> out = new ArrayList<>(Math.min(burstLimit, tasks.size()));
        Iterator<Map.Entry<Key, Task>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext() && out.size() < minimum) {
            Map.Entry<Key, Task> entry = iterator.next();
            out.add(entry.getValue());
            loadedKeys.remove(entry.getKey());
            iterator.remove();
            releaseUnretainedReservation(entry.getKey());
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
                loadedKeys.remove(entry.getKey());
                iterator.remove();
                releaseUnretainedReservation(entry.getKey());
            }
        }
        return out;
    }

    public synchronized List<Task> snapshot() {
        LinkedHashMap<Key, Task> accepted = new LinkedHashMap<>(claimed);
        accepted.putAll(tasks);
        return List.copyOf(accepted.values());
    }

    public synchronized int size() {
        return tasks.size();
    }

    public synchronized void clear() {
        tasks.clear();
        claimed.clear();
        loadedKeys.clear();
        reservedKeys.clear();
    }

    public synchronized boolean contains(String dimensionId, int chunkX, int chunkZ) {
        return tasks.containsKey(new Key(dimensionId, chunkX, chunkZ));
    }

    public synchronized int remainingCapacity() {
        return Math.max(0, maxTasks - (retainedTaskCount() - reservedKeys.size()));
    }

    private int retainedTaskCount() {
        int count = tasks.size() + claimed.size();
        for (Key key : claimed.keySet()) if (tasks.containsKey(key)) count--;
        return count;
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
