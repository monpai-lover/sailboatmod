package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapPngFailureRecoveryTest {
    @TempDir Path tempDir;

    @Test
    void cacheWriteFailureIsObservableAndRetrySucceedsWithoutFalseChangeNotifications() throws Exception {
        Path blockedRoot = tempDir.resolve("blocked_cache");
        Files.writeString(blockedRoot, "a file prevents creating the PNG parent directory");
        MarketWebMapTileCache cache = new MarketWebMapTileCache(blockedRoot);
        int[] pixels = pixels(512 * 512, 0xFF225588);
        long before = cache.changeJournal().revision();

        assertThrows(UncheckedIOException.class, () ->
                cache.writeSquareTilePixels(MarketWebMapConstants.OVERWORLD, 0, 0, 0, pixels));
        assertEquals(before, cache.changeJournal().revision());
        assertTrue(cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 0, 0, 0).isEmpty());

        Files.delete(blockedRoot);
        assertTrue(cache.writeSquareTilePixels(MarketWebMapConstants.OVERWORLD, 0, 0, 0, pixels));
        long written = cache.changeJournal().revision();
        assertTrue(written > before);
        assertTrue(cache.writeSquareTilePixels(MarketWebMapConstants.OVERWORLD, 0, 0, 0, pixels));
        assertEquals(written, cache.changeJournal().revision(), "identical bytes are successful without another change event");
    }

    @Test
    void regionImageDoesNotTreatFailedBasePngAsUnchangedAndCanRetryTheSameImage() throws Exception {
        Path blockedRoot = tempDir.resolve("blocked_region");
        Files.writeString(blockedRoot, "blocked PNG parent");
        MarketWebMapTileCache cache = new MarketWebMapTileCache(blockedRoot);
        MarketWebMapRegionImage image = new MarketWebMapRegionImage(MarketWebMapConstants.OVERWORLD, 0, 0);
        assertTrue(image.putChunkPixels(0, 0, pixels(16 * 16, 0xFF336699)));

        assertThrows(UncheckedIOException.class, () ->
                image.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 1));
        Files.delete(blockedRoot);
        assertTrue(image.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 2));
        assertEquals(0xFF336699, cache.readSquareTilePixels(MarketWebMapConstants.OVERWORLD, 0, 0, 0)[0]);
        assertEquals(0xFF336699, cache.readSquareTilePixels(MarketWebMapConstants.OVERWORLD, 1, 0, 0)[0]);
    }

    @Test
    void invalidInputStillReturnsFalseBeforeTryingFilesystemWrites() throws Exception {
        Path blockedRoot = tempDir.resolve("invalid_input");
        Files.writeString(blockedRoot, "invalid requests must not touch this blocker");
        MarketWebMapTileCache cache = new MarketWebMapTileCache(blockedRoot);
        assertFalse(cache.writeSquareTilePixels(MarketWebMapConstants.OVERWORLD, 0, 0, 0, null));
        assertFalse(cache.writeSquareTilePixels("minecraft:the_nether", 0, 0, 0, pixels(512 * 512, 1)));
        assertFalse(cache.writeSquareTilePixels(MarketWebMapConstants.OVERWORLD, -1, 0, 0, pixels(512 * 512, 1)));
    }

    @Test
    void regionImageRepairsFailedZoomAfterBaseWasAlreadySavedWithoutNotifyingUnchangedTiles() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir.resolve("zoom_recovery"));
        Path blockedZoom = cache.squareTileDir(1);
        Files.createDirectories(blockedZoom.getParent());
        Files.writeString(blockedZoom, "block the first zoom directory after the base can be saved");
        MarketWebMapRegionImage image = new MarketWebMapRegionImage(MarketWebMapConstants.OVERWORLD, 0, 0);
        assertTrue(image.putChunkPixels(0, 0, pixels(16 * 16, 0xFF3377AA)));

        assertThrows(UncheckedIOException.class, () ->
                image.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 1));
        assertEquals(0xFF3377AA, cache.readSquareTilePixels(MarketWebMapConstants.OVERWORLD, 0, 0, 0)[0]);
        assertTrue(cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 1, 0, 0).isEmpty());
        Files.delete(blockedZoom);
        assertTrue(image.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 2),
                "an unchanged base must not prevent repairing a previously failed zoom write");
        assertEquals(0xFF3377AA, cache.readSquareTilePixels(MarketWebMapConstants.OVERWORLD, 1, 0, 0)[0]);
        long repaired = cache.changeJournal().revision();

        MarketWebMapRegionImage unchanged = new MarketWebMapRegionImage(MarketWebMapConstants.OVERWORLD, 0, 0);
        unchanged.putChunkPixels(0, 0, pixels(16 * 16, 0xFF3377AA));
        assertFalse(unchanged.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 3));
        assertEquals(repaired, cache.changeJournal().revision(), "unchanged base and zooms must not emit invalidations");
    }

    private static int[] pixels(int size, int color) {
        int[] pixels = new int[size];
        Arrays.fill(pixels, color);
        return pixels;
    }
}
