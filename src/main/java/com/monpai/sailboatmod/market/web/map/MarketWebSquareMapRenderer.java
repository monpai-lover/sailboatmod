package com.monpai.sailboatmod.market.web.map;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColorizer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Squaremap-style render worker: render from immutable chunk snapshots off-thread, then write images
 * through a bounded single ImageIO queue.
 */
public final class MarketWebSquareMapRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_IN_FLIGHT_CHUNKS = 512;

    private final ExecutorService renderExecutor;
    private final MarketWebSquareMapImageIOExecutor imageIO;
    private final RoadMapColorizer colorizer;
    private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

    public MarketWebSquareMapRenderer() {
        this(new RoadMapColorizer(), new MarketWebSquareMapImageIOExecutor());
    }

    MarketWebSquareMapRenderer(RoadMapColorizer colorizer, MarketWebSquareMapImageIOExecutor imageIO) {
        this.colorizer = colorizer == null ? new RoadMapColorizer() : colorizer;
        this.imageIO = imageIO == null ? new MarketWebSquareMapImageIOExecutor() : imageIO;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors() / 3);
        this.renderExecutor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "SailboatMarketWebMap-RenderWorker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean submit(MarketWebMapTileCache cache,
                          MarketWebMapChunkSnapshot snapshot,
                          MarketWebMapTileQuality quality,
                          long nowMillis) {
        if (cache == null || snapshot == null || pendingTasks() >= MAX_IN_FLIGHT_CHUNKS) {
            return false;
        }
        long key = ChunkPos.asLong(snapshot.chunkX(), snapshot.chunkZ());
        if (!inFlight.add(key)) {
            return false;
        }
        renderExecutor.execute(() -> {
            try {
                int[] pixels = snapshot.colorize(colorizer);
                MarketWebMapTileQuality source = quality == null ? MarketWebMapTileQuality.SERVER_LOADED_CHUNK : quality;
                imageIO.submit(() -> {
                    cache.mergeChunkArgb(snapshot.dimensionId(), snapshot.chunkX(), snapshot.chunkZ(), pixels, source, nowMillis);
                    cache.mergeChunkPyramid(snapshot.dimensionId(), snapshot.chunkX(), snapshot.chunkZ(), pixels, source, nowMillis);
                });
            } catch (RuntimeException exception) {
                LOGGER.warn("Market web square map render failed for chunk {},{}", snapshot.chunkX(), snapshot.chunkZ(), exception);
            } finally {
                inFlight.remove(key);
            }
        });
        return true;
    }

    public int pendingTasks() {
        return inFlight.size() + imageIO.pendingTasks();
    }

    public void shutdown() {
        renderExecutor.shutdownNow();
        imageIO.shutdown();
    }
}
