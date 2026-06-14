package com.monpai.sailboatmod.market.web.map;

import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Squaremap-style snapshot cache and request gate.
 *
 * <p>Tile misses and background renders can ask for the same chunk many times. This manager keeps a
 * small FIFO cache of futures, deduplicates identical requests, and caps the number of active
 * snapshot reads so disk IO cannot fan out without bounds.</p>
 */
public final class MarketWebMapSnapshotManager {
    public static final int DEFAULT_CACHE_SIZE = 2048;

    private final SnapshotProvider provider;
    private final int maxCacheSize;
    private final int maxActiveRequests;
    private final boolean prefetchVerticalNeighbors;
    private final LinkedHashMap<Key, CompletableFuture<Optional<MarketWebMapChunkSnapshot>>> cache = new LinkedHashMap<>();
    private final Queue<PendingRequest> pending = new ArrayDeque<>();
    private final Set<Key> pendingKeys = ConcurrentHashMap.newKeySet();
    private final Set<Key> activeKeys = ConcurrentHashMap.newKeySet();

    public MarketWebMapSnapshotManager(SnapshotProvider provider,
                                       int maxCacheSize,
                                       int maxActiveRequests,
                                       boolean prefetchVerticalNeighbors) {
        this.provider = provider == null ? (dimensionId, chunkX, chunkZ, quality) -> CompletableFuture.completedFuture(Optional.empty()) : provider;
        this.maxCacheSize = Math.max(1, maxCacheSize);
        this.maxActiveRequests = Math.max(1, maxActiveRequests);
        this.prefetchVerticalNeighbors = prefetchVerticalNeighbors;
    }

    public CompletableFuture<Optional<MarketWebMapChunkSnapshot>> snapshotWithVerticalNeighbors(String dimensionId,
                                                                                                int chunkX,
                                                                                                int chunkZ,
                                                                                                MarketWebMapTileQuality quality) {
        if (!prefetchVerticalNeighbors) {
            return snapshotDirect(dimensionId, chunkX, chunkZ, quality);
        }
        snapshotDirect(dimensionId, chunkX, chunkZ - 1, quality);
        CompletableFuture<Optional<MarketWebMapChunkSnapshot>> current = snapshotDirect(dimensionId, chunkX, chunkZ, quality);
        snapshotDirect(dimensionId, chunkX, chunkZ + 1, quality);
        return current;
    }

    public synchronized CompletableFuture<Optional<MarketWebMapChunkSnapshot>> snapshotDirect(String dimensionId,
                                                                                               int chunkX,
                                                                                               int chunkZ,
                                                                                               MarketWebMapTileQuality quality) {
        if (!MarketWebMapConstants.OVERWORLD.equals(dimensionId)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        Key key = new Key(dimensionId, chunkX, chunkZ);
        CompletableFuture<Optional<MarketWebMapChunkSnapshot>> existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Optional<MarketWebMapChunkSnapshot>> promise = new CompletableFuture<>();
        cache.put(key, promise);
        PendingRequest request = new PendingRequest(key, quality == null ? MarketWebMapTileQuality.SERVER_REGION_SCAN : quality, promise);
        if (activeKeys.size() < maxActiveRequests) {
            start(request);
        } else {
            pending.add(request);
            pendingKeys.add(key);
        }
        trimCache();
        return promise;
    }

    public synchronized int activeRequests() {
        return activeKeys.size();
    }

    public synchronized int pendingRequests() {
        return pending.size();
    }

    public synchronized int cachedSnapshots() {
        return cache.size();
    }

    private void start(PendingRequest request) {
        activeKeys.add(request.key());
        CompletableFuture<Optional<MarketWebMapChunkSnapshot>> source;
        try {
            source = provider.readSnapshot(
                    request.key().dimensionId(),
                    request.key().chunkX(),
                    request.key().chunkZ(),
                    request.quality());
        } catch (RuntimeException exception) {
            source = CompletableFuture.completedFuture(Optional.empty());
        }
        source.whenComplete((snapshot, failure) -> {
            if (failure != null) {
                request.promise().complete(Optional.empty());
            } else {
                request.promise().complete(snapshot == null ? Optional.empty() : snapshot);
            }
            complete(request.key());
        });
    }

    private synchronized void complete(Key key) {
        activeKeys.remove(key);
        launchQueued();
        trimCache();
    }

    private void launchQueued() {
        while (activeKeys.size() < maxActiveRequests && !pending.isEmpty()) {
            PendingRequest request = pending.poll();
            pendingKeys.remove(request.key());
            if (request.promise().isDone()) {
                continue;
            }
            start(request);
        }
    }

    private void trimCache() {
        while (cache.size() > maxCacheSize) {
            Iterator<Key> iterator = cache.keySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            Key eldest = iterator.next();
            if (activeKeys.contains(eldest) || pendingKeys.contains(eldest)) {
                return;
            }
            iterator.remove();
        }
    }

    public interface SnapshotProvider {
        CompletableFuture<Optional<MarketWebMapChunkSnapshot>> readSnapshot(String dimensionId,
                                                                            int chunkX,
                                                                            int chunkZ,
                                                                            MarketWebMapTileQuality quality);
    }

    private record Key(String dimensionId, int chunkX, int chunkZ) {
        private long packedChunk() {
            return ChunkPos.asLong(chunkX, chunkZ);
        }
    }

    private record PendingRequest(Key key,
                                  MarketWebMapTileQuality quality,
                                  CompletableFuture<Optional<MarketWebMapChunkSnapshot>> promise) {
    }
}
