package com.monpai.sailboatmod.roadplanner.map;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapTileGridTest {
    @Test void pixelCentresAndFloorDivisionStayCorrectAcrossNegativeTiles() {
        assertEquals(4096, SharedMapTileGrid.span(16));
        assertEquals(-4088, SharedMapTileGrid.sample(-1, 0, 16));
        assertEquals(-8, SharedMapTileGrid.sample(-1, 255, 16));
        assertEquals(-1, SharedMapTileGrid.tileAt(-1, 16));
        assertEquals(255, SharedMapTileGrid.pixelAt(-1, 16));
        assertEquals(0, SharedMapTileGrid.tileAt(4095, 16));
    }
    @Test void nativeTellusCoordinatesBeyondThirtyMillionRemainValidButIntegerOverflowDoesNot() {
        assertTrue(SharedMapTileGrid.validTile(130_593, 0, 1));
        assertTrue(SharedMapTileGrid.validTile(7, -8, 1 << 20));
        assertFalse(SharedMapTileGrid.validTile(Integer.MAX_VALUE, 0, 1 << 20));
        assertFalse(SharedMapTileGrid.validTile(0, 0, 3));
    }
    @Test void farScaleSelectsAPowerOfTwoPixelGridInsteadOfMillionsOfTiles() {
        int bps = SharedMapTileGrid.forScale(1.0 / 65_536, 1280, 720);
        assertTrue(bps >= 16_384);
        assertTrue(SharedMapTileGrid.validBps(bps));
        assertEquals(1, SharedMapTileGrid.forScale(0.25, 320, 240));
    }
    @Test void realChunkProjectsOnlyItsPixelCentresAndNeverPaintsAnEntireCoarseCell() {
        assertEquals(0, SharedMapTileGrid.pixelsInChunk(0, 0, 64, 0, 0).length);
        assertArrayEquals(new int[]{0}, SharedMapTileGrid.pixelsInChunk(0, 0, 64, 2, 2));
        assertEquals(0, SharedMapTileGrid.pixelsInChunk(0, 0, 64, 3, 2).length);
        assertArrayEquals(new int[]{254*256+254, 254*256+255, 255*256+254, 255*256+255},
                SharedMapTileGrid.pixelsInChunk(-1, -1, 8, -1, -1));
        assertEquals(256, SharedMapTileGrid.pixelsInChunk(0, 0, 1, 0, 0).length);
    }
}
