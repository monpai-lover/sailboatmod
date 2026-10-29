package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;
import com.monpai.sailboatmod.roadplanner.map.MapBlockColors;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRenderStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapRegionShadingTest {
    private static final String DIMENSION = MarketWebMapConstants.OVERWORLD;
    private static final int GRASS = MapBlockColors.lookup("minecraft:grass_block");
    private static final int FLAT_GRASS = RoadMapRenderStyle.styleTerrainByDelta(GRASS, 0);

    @TempDir
    Path tempDir;

    @Test
    void capturedImmediateNorthPreventsBrightBandAtInternalNegativeChunkBoundary() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        try {
            // The screenshot's stripe is at region-local row 64, not a 512-block region edge.
            captureNorth(manager, flat(-1, -33, 64));
            captureNorth(manager, flat(-1, -29, 65));
            render(manager, cache, flat(-1, -28, 65));

            int[] pixels = cache.readSquareTilePixels(DIMENSION, 0, -1, -1);
            assertEquals(FLAT_GRASS, pixels[64 * 512 + 496], "row 64 must compare with row 63 at height 65");
            assertEquals(FLAT_GRASS, pixels[65 * 512 + 496]);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void coldPartialRenderCannotUseDistantRegionSeedAcrossAbsentChunks() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        try {
            captureNorth(manager, flat(0, -1, 64));
            render(manager, cache, flat(0, 4, 65));

            int[] pixels = cache.readSquareTilePixels(DIMENSION, 0, 0, 0);
            assertEquals(FLAT_GRASS, pixels[64 * 512], "unknown immediate north must not invent a one-block cliff");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void absentChunkWithinOneBatchBreaksHeightComparison() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        try {
            render(manager, cache, flat(0, 0, 60), flat(0, 2, 65));

            int[] pixels = cache.readSquareTilePixels(DIMENSION, 0, 0, 0);
            assertEquals(FLAT_GRASS, pixels[32 * 512], "chunk 2 has no data for the bottom row of chunk 1");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void missingSampleBreaksHeightComparisonWithinChunk() {
        MarketWebMapRegionRenderer renderer = new MarketWebMapRegionRenderer();
        MarketWebMapChunkSnapshot snapshot = flat(0, 0, 65);
        for (int x = 0; x < 16; x++) {
            snapshot.samples()[x] = sample(x, 60, 0);
            snapshot.samples()[16 + x] = null;
        }
        int[] pixels = renderer.renderChunk(snapshot, renderer.unknownLastY());

        assertEquals(Integer.MIN_VALUE, pixels[16]);
        assertEquals(FLAT_GRASS, pixels[32], "row 2 must not compare against nonadjacent row 0");
    }

    @Test
    void partialRenderDoesNotPersistInteriorHeightAsSouthEdge() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        try {
            render(manager, cache, flat(0, 4, 65));

            int[][] rows = cache.readBottomRows(0, 0);
            assertTrue(rows == null || Arrays.stream(rows[0]).allMatch(y -> y == Integer.MIN_VALUE),
                    "region row 511 was never sampled; row 79 cannot become its seam");
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void partialSouthEdgeRefreshPreservesOtherActualSeamColumnsAfterRestart() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        int[][] oldRows = new int[32][16];
        for (int[] row : oldRows) {
            Arrays.fill(row, 90);
        }
        cache.writeBottomRows(0, 0, oldRows);
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        try {
            render(manager, cache, flat(1, 31, 65));

            int[][] rows = cache.readBottomRows(0, 0);
            assertEquals(90, rows[0][0], "untouched real south-edge samples must survive a partial update");
            assertEquals(65, rows[1][0]);
            assertEquals(90, rows[31][15]);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void genuineAdjacentSlopeRemainsVisible() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        try {
            render(manager, cache, flat(0, 3, 65), flat(0, 4, 66));

            int[] pixels = cache.readSquareTilePixels(DIMENSION, 0, 0, 0);
            assertEquals(RoadMapRenderStyle.styleTerrainByDelta(GRASS, 1), pixels[64 * 512]);
            assertNotEquals(FLAT_GRASS, pixels[64 * 512]);
            assertEquals(FLAT_GRASS, pixels[65 * 512]);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void explicitMissingNorthInCurrentBatchOverridesOlderCachedHeight() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        try {
            captureNorth(manager, flat(0, 3, 64));
            Object state = newRegionState(manager, 0, 0);
            Method put = state.getClass().getDeclaredMethod("put", MarketWebMapChunkSnapshot.class, MarketWebMapTileQuality.class);
            put.setAccessible(true);
            put.invoke(state, flat(0, 3, 64), MarketWebMapTileQuality.SERVER_REGION_SCAN);
            Method missing = state.getClass().getDeclaredMethod("missing", int.class, int.class, MarketWebMapTileQuality.class);
            missing.setAccessible(true);
            missing.invoke(state, 0, 3, MarketWebMapTileQuality.SERVER_REGION_SCAN);
            put.invoke(state, flat(0, 4, 65), MarketWebMapTileQuality.SERVER_REGION_SCAN);
            Method schedule = MarketWebMapRenderManager.class.getDeclaredMethod("scheduleRegionRender",
                    MarketWebMapTileCache.class, state.getClass(), long.class);
            schedule.setAccessible(true);
            schedule.invoke(manager, cache, state, 1L);
            awaitWrites(manager);

            assertEquals(FLAT_GRASS, cache.readSquareTilePixels(DIMENSION, 0, 0, 0)[64 * 512]);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void missingNorthEventInvalidatesHeightBeforeLaterSouthBatch() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        try {
            captureNorth(manager, flat(0, 3, 64));
            acceptMissing(manager, cache, 0, 3);
            render(manager, cache, flat(0, 4, 65));

            assertEquals(FLAT_GRASS, cache.readSquareTilePixels(DIMENSION, 0, 0, 0)[64 * 512]);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void missingSouthEdgeInvalidatesPersistedSeamForNextRegion() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        try {
            render(manager, cache, flat(0, 31, 64));
            acceptMissing(manager, cache, 0, 31);
            assertEquals(Integer.MIN_VALUE, cache.readBottomRows(0, 0)[0][0]);
            render(manager, cache, flat(0, 32, 65));

            assertEquals(FLAT_GRASS, cache.readSquareTilePixels(DIMENSION, 0, 0, 1)[0]);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void delayedSeamWriteUsesLatestMergedEdgeColumns() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            writer(manager).submit(() -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Object state = newRegionState(manager, 0, 0);
            int[][] firstEdge = new int[32][];
            firstEdge[0] = new int[16];
            Arrays.fill(firstEdge[0], 65);
            Method persist = MarketWebMapRenderManager.class.getDeclaredMethod("cacheBottomRows",
                    MarketWebMapTileCache.class, state.getClass(), int[][].class);
            persist.setAccessible(true);
            persist.invoke(manager, cache, state, firstEdge);

            // A newer completed snapshot arrives before the queued writer can run.
            captureNorth(manager, flat(1, 31, 70));
            release.countDown();
            awaitWrites(manager);

            assertEquals(65, cache.readBottomRows(0, 0)[0][0]);
            assertEquals(70, cache.readBottomRows(0, 0)[1][0], "a delayed write must not restore its older copy");
        } finally {
            release.countDown();
            manager.shutdown();
        }
    }

    @Test
    void olderRenderCannotReplaceNewerAcceptedHeightInSameSeamColumn() throws Exception {
        MarketWebMapRenderManager manager = new MarketWebMapRenderManager();
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        try {
            captureNorth(manager, flat(0, 31, 70));
            Object state = newRegionState(manager, 0, 0);
            int[][] olderEdge = new int[32][];
            olderEdge[0] = new int[16];
            Arrays.fill(olderEdge[0], 65);
            Method persist = MarketWebMapRenderManager.class.getDeclaredMethod("cacheBottomRows",
                    MarketWebMapTileCache.class, state.getClass(), int[][].class);
            persist.setAccessible(true);
            persist.invoke(manager, cache, state, olderEdge);
            awaitWrites(manager);

            assertEquals(70, cache.readBottomRows(0, 0)[0][0],
                    "a delayed older render must retain the latest accepted south-edge snapshot");
        } finally {
            manager.shutdown();
        }
    }

    private static void captureNorth(MarketWebMapRenderManager manager, MarketWebMapChunkSnapshot snapshot) throws Exception {
        Method capture = MarketWebMapRenderManager.class.getDeclaredMethod("cacheBottomRow", MarketWebMapChunkSnapshot.class);
        capture.setAccessible(true);
        capture.invoke(manager, snapshot);
    }

    private static void render(MarketWebMapRenderManager manager,
                               MarketWebMapTileCache cache,
                               MarketWebMapChunkSnapshot... snapshots) throws Exception {
        Method expected = MarketWebMapRenderManager.class.getDeclaredMethod("prepareExpectedRegionChunks", List.class);
        expected.setAccessible(true);
        expected.invoke(manager, Arrays.stream(snapshots)
                .map(snapshot -> new MarketWebMapDirtyChunkQueue.ChunkCoordinate(DIMENSION, snapshot.chunkX(), snapshot.chunkZ()))
                .toList());
        Method accept = MarketWebMapRenderManager.class.getDeclaredMethod("acceptSnapshot", MarketWebMapTileCache.class,
                MarketWebMapChunkSnapshot.class, MarketWebMapTileQuality.class, long.class);
        accept.setAccessible(true);
        for (MarketWebMapChunkSnapshot snapshot : snapshots) {
            accept.invoke(manager, cache, snapshot, MarketWebMapTileQuality.SERVER_REGION_SCAN, 1L);
        }
        awaitWrites(manager);
    }

    private static void acceptMissing(MarketWebMapRenderManager manager,
                                      MarketWebMapTileCache cache,
                                      int chunkX,
                                      int chunkZ) throws Exception {
        Method expected = MarketWebMapRenderManager.class.getDeclaredMethod("prepareExpectedRegionChunks", List.class);
        expected.setAccessible(true);
        expected.invoke(manager, List.of(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(DIMENSION, chunkX, chunkZ)));
        Method missing = MarketWebMapRenderManager.class.getDeclaredMethod("acceptMissingSnapshot", MarketWebMapTileCache.class,
                String.class, int.class, int.class, MarketWebMapTileQuality.class, long.class);
        missing.setAccessible(true);
        missing.invoke(manager, cache, DIMENSION, chunkX, chunkZ, MarketWebMapTileQuality.SERVER_REGION_SCAN, 1L);
        awaitWrites(manager);
    }

    private static Object newRegionState(MarketWebMapRenderManager manager, int regionX, int regionZ) throws Exception {
        Method create = MarketWebMapRenderManager.class.getDeclaredMethod("newRegionState", String.class, int.class, int.class);
        create.setAccessible(true);
        return create.invoke(manager, DIMENSION, regionX, regionZ);
    }

    private static MarketWebSquareMapImageIOExecutor writer(MarketWebMapRenderManager manager) throws Exception {
        Field writerField = MarketWebMapRenderManager.class.getDeclaredField("imageIO");
        writerField.setAccessible(true);
        return (MarketWebSquareMapImageIOExecutor) writerField.get(manager);
    }

    private static void awaitWrites(MarketWebMapRenderManager manager) throws Exception {
        Field renderingField = MarketWebMapRenderManager.class.getDeclaredField("renderingRegions");
        renderingField.setAccessible(true);
        AtomicInteger rendering = (AtomicInteger) renderingField.get(manager);
        MarketWebSquareMapImageIOExecutor writer = writer(manager);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while ((rendering.get() != 0 || writer.pendingTasks() != 0) && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(0, rendering.get(), "region render worker must finish");
        assertEquals(0, writer.pendingTasks(), "seam and PNG writes must finish");
    }

    private static MarketWebMapChunkSnapshot flat(int chunkX, int chunkZ, int height) {
        RoadMapColumnSample[] samples = new RoadMapColumnSample[256];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                samples[z * 16 + x] = sample(chunkX * 16 + x, height, chunkZ * 16 + z);
            }
        }
        return new MarketWebMapChunkSnapshot(DIMENSION, chunkX, chunkZ, samples);
    }

    private static RoadMapColumnSample sample(int x, int height, int z) {
        return new RoadMapColumnSample(x, height, z, GRASS, false, 0, height);
    }
}
