package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapBlackTileRepairTest {
    @TempDir Path tempDir;

    @Test
    void automaticPagesCoverTheDirectoryBeforeWrappingAndNeverReadMoreThan128Tiles() throws Exception {
        MarketWebMapTileCache cache = createCandidateTiles(300);
        var scanner = new MarketWebMapBlackTileRepair.AutomaticScanCursor();
        Set<String> firstPage = new HashSet<>();
        Set<String> visited = new HashSet<>();
        for (int page = 0; page < 3; page++) {
            List<String> reads = new ArrayList<>();
            List<int[]> holes = scanner.scan(cache, tempDir.resolve("region"), 500, () -> true, (x, z) -> {
                reads.add(x + "," + z);
                return null;
            });
            assertTrue(reads.size() <= 128, "automatic candidate/header/PNG work must be bounded even for a larger requested budget");
            assertEquals(reads.size(), holes.size());
            if (page == 0) firstPage.addAll(reads);
            for (String tile : reads) assertTrue(visited.add(tile), "a page must advance rather than reread the same prefix");
        }
        assertEquals(300, visited.size(), "all tiles must eventually be examined");
        Set<String> wrapped = new HashSet<>();
        scanner.scan(cache, tempDir.resolve("region"), 128, () -> true, (x, z) -> {
            wrapped.add(x + "," + z);
            return null;
        });
        assertEquals(firstPage, wrapped, "reaching the directory end starts a new conservative repair pass");
    }

    @Test
    void pausingAnAutomaticPageRetriesThatPageWithoutAdvancingItsCursor() throws Exception {
        MarketWebMapTileCache cache = createCandidateTiles(3);
        var scanner = new MarketWebMapBlackTileRepair.AutomaticScanCursor();
        AtomicBoolean enabled = new AtomicBoolean(true);
        List<String> interruptedPage = new ArrayList<>();
        assertThrows(MarketWebMapWorkSuspendedException.class, () ->
                scanner.scan(cache, tempDir.resolve("region"), 1, enabled::get, (x, z) -> {
                    interruptedPage.add(x + "," + z);
                    enabled.set(false);
                    return null;
                }));
        enabled.set(true);
        List<String> retriedPage = new ArrayList<>();
        scanner.scan(cache, tempDir.resolve("region"), 1, enabled::get, (x, z) -> {
            retriedPage.add(x + "," + z);
            return null;
        });
        assertEquals(interruptedPage, retriedPage);
    }

    @Test
    void manualFullScanPreservesTheIndependentAutomaticCursor() throws Exception {
        MarketWebMapTileCache cache = createCandidateTiles(6);
        var scanner = new MarketWebMapBlackTileRepair.AutomaticScanCursor();
        Set<String> first = new HashSet<>();
        scanner.scan(cache, tempDir.resolve("region"), 2, () -> true, (x, z) -> {
            first.add(x + "," + z); return null;
        });
        assertEquals(6, MarketWebMapBlackTileRepair.findBlackRegions(cache, tempDir.resolve("region"), 0,
                () -> true, (x, z) -> null).size(), "explicit full repair still visits the whole directory");
        Set<String> next = new HashSet<>();
        scanner.scan(cache, tempDir.resolve("region"), 2, () -> true, (x, z) -> {
            next.add(x + "," + z); return null;
        });
        assertEquals(2, next.size());
        assertTrue(java.util.Collections.disjoint(first, next));
    }

    private MarketWebMapTileCache createCandidateTiles(int count) throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir.resolve("cache"));
        Files.createDirectories(cache.squareTileDir(0));
        Files.createDirectories(tempDir.resolve("region"));
        for (int x = 0; x < count; x++) {
            // The injectable pixel reader below owns decoding; these real files exercise enumeration and header IO.
            Files.write(cache.squareTileDir(0).resolve(x + "_0.png"), new byte[0]);
            byte[] header = new byte[8192]; header[2] = 2; header[3] = 1;
            Files.write(tempDir.resolve("region/r." + x + ".0.mca"), header);
        }
        return cache;
    }

    @Test
    void pauseStopsAnActiveScanBeforeAnotherPngReadAndTheNextScanCanRetry() throws Exception {
        MarketWebMapTileCache cache = createTwoBlackTilesWithSavedRegions();
        AtomicBoolean enabled = new AtomicBoolean(true);
        AtomicInteger reads = new AtomicInteger();
        assertThrows(MarketWebMapWorkSuspendedException.class, () ->
                MarketWebMapBlackTileRepair.findBlackRegions(cache, tempDir.resolve("region"), 0, enabled::get, (x, z) -> {
                    int[] pixels = cache.readSquareTilePixels("minecraft:overworld", 0, x, z);
                    if (reads.incrementAndGet() == 1) enabled.set(false);
                    return pixels;
                }));
        assertEquals(1, reads.get(), "pause must stop before decoding the next cached PNG");
        enabled.set(true);
        assertEquals(2, MarketWebMapBlackTileRepair.findBlackRegions(cache, tempDir.resolve("region"), 0,
                enabled::get, (x, z) -> cache.readSquareTilePixels("minecraft:overworld", 0, x, z)).size());
    }

    @Test
    void interruptionStopsAnActiveScanEvenWhenUpdatesRemainEnabled() throws Exception {
        MarketWebMapTileCache cache = createTwoBlackTilesWithSavedRegions();
        AtomicInteger reads = new AtomicInteger();
        try {
            assertThrows(MarketWebMapWorkSuspendedException.class, () ->
                    MarketWebMapBlackTileRepair.findBlackRegions(cache, tempDir.resolve("region"), 0, () -> true, (x, z) -> {
                        int[] pixels = cache.readSquareTilePixels("minecraft:overworld", 0, x, z);
                        if (reads.incrementAndGet() == 1) Thread.currentThread().interrupt();
                        return pixels;
                    }));
            assertEquals(1, reads.get());
            assertTrue(Thread.currentThread().isInterrupted(), "the scanner must preserve interruption for its caller");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void pausedScanDoesNotStartReadingTilesAndBudgetStillLimitsNormalScans() throws Exception {
        MarketWebMapTileCache cache = createTwoBlackTilesWithSavedRegions();
        AtomicInteger reads = new AtomicInteger();
        assertThrows(MarketWebMapWorkSuspendedException.class, () ->
                MarketWebMapBlackTileRepair.findBlackRegions(cache, tempDir.resolve("region"), 0, () -> false, (x, z) -> {
                    reads.incrementAndGet();
                    return null;
                }));
        assertEquals(0, reads.get());
        assertEquals(1, MarketWebMapBlackTileRepair.findBlackRegions(cache, tempDir.resolve("region"), 1,
                () -> true, (x, z) -> cache.readSquareTilePixels("minecraft:overworld", 0, x, z)).size());
    }

    @Test
    void emptyRegionHeadersDoNotCauseBlackTileRepairs() throws Exception {
        MarketWebMapTileCache cache = createTwoBlackTilesWithSavedRegions();
        Files.write(tempDir.resolve("region/r.0.0.mca"), new byte[8192]);
        Files.write(tempDir.resolve("region/r.1.0.mca"), new byte[8192]);
        assertTrue(MarketWebMapBlackTileRepair.findBlackRegions(cache, tempDir.resolve("region"), 0,
                () -> true, (x, z) -> fail("empty regions must not cause PNG decoding")).isEmpty());
    }

    private MarketWebMapTileCache createTwoBlackTilesWithSavedRegions() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir.resolve("cache"));
        Files.createDirectories(cache.squareTileDir(0));
        Files.createDirectories(tempDir.resolve("region"));
        for (int x = 0; x < 2; x++) {
            BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
            assertTrue(ImageIO.write(image, "png", cache.squareTileDir(0).resolve(x + "_0.png").toFile()));
            byte[] header = new byte[8192];
            header[2] = 2;
            header[3] = 1;
            Files.write(tempDir.resolve("region/r." + x + ".0.mca"), header);
        }
        return cache;
    }
}
