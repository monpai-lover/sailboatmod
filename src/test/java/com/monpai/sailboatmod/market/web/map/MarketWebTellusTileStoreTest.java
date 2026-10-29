package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebTellusTileStoreTest {
    @TempDir
    Path root;

    @Test
    void writesAndReadsTiles() {
        MarketWebTellusTileStore store = new MarketWebTellusTileStore(root);
        store.write(3, -2, 7, new byte[]{1, 2, 3});

        assertArrayEquals(new byte[]{1, 2, 3}, store.read(3, -2, 7).orElseThrow());
        assertTrue(Files.isRegularFile(root.resolve("tellus").resolve("v2").resolve("3").resolve("-2_7.png")));
    }

    @Test
    void missingTileIsEmpty() {
        assertTrue(new MarketWebTellusTileStore(root).read(0, 0, 0).isEmpty());
    }

    @Test
    void oldCoarsePredictionCacheIsNotReused() throws Exception {
        Path oldTile = root.resolve("tellus").resolve("3").resolve("-2_7.png");
        Files.createDirectories(oldTile.getParent());
        Files.write(oldTile, new byte[]{1, 2, 3});

        assertTrue(new MarketWebTellusTileStore(root).read(3, -2, 7).isEmpty(),
                "native-resolution prediction must not show old coarse DEM tiles");
        assertTrue(Files.isRegularFile(oldTile), "the old cache can remain on disk without being served");
    }

    @Test
    void writeLeavesNoTemporaryFiles() throws Exception {
        MarketWebTellusTileStore store = new MarketWebTellusTileStore(root);
        store.write(0, 1, 1, new byte[]{9});
        store.write(0, 1, 1, new byte[]{8});

        try (var files = Files.list(root.resolve("tellus").resolve("v2").resolve("0"))) {
            assertEquals(1, files.count(), "the tile is written through a temp file and moved into place");
        }
        assertArrayEquals(new byte[]{8}, store.read(0, 1, 1).orElseThrow());
    }
}
