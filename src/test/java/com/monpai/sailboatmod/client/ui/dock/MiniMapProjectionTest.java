package com.monpai.sailboatmod.client.ui.dock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MiniMapProjectionTest {
    private static final int RADIUS = 50;

    @Test
    void edgesAndCentreMapToTheMapBorderAndMiddle() {
        assertEquals(0, MiniMapProjection.toPixel(-RADIUS, 101, RADIUS));
        assertEquals(50, MiniMapProjection.toPixel(0, 101, RADIUS));
        assertEquals(100, MiniMapProjection.toPixel(RADIUS, 101, RADIUS));
        assertEquals(100, MiniMapProjection.toPixel(RADIUS * 3, 101, RADIUS), "outside the radius is clamped");
    }

    @Test
    void pixelsRoundTripToOffsets() {
        for (int px = 0; px < 101; px++) {
            assertEquals(px, MiniMapProjection.toPixel(MiniMapProjection.toOffset(px, 101, RADIUS), 101, RADIUS));
        }
    }

    @Test
    void dragInAnyDirectionGivesTheSameOrderedZone() {
        int[] forward = MiniMapProjection.zoneFromDrag(10, 5, 40, 30, 46, 34, RADIUS);
        int[] backward = MiniMapProjection.zoneFromDrag(40, 30, 10, 5, 46, 34, RADIUS);
        assertArrayEquals(forward, backward);
        assertEquals(MiniMapProjection.toOffset(10, 46, RADIUS), forward[0]);
        assertEquals(MiniMapProjection.toOffset(40, 46, RADIUS), forward[1]);
        assertEquals(MiniMapProjection.toOffset(5, 34, RADIUS), forward[2]);
        assertEquals(MiniMapProjection.toOffset(30, 34, RADIUS), forward[3]);
    }
}
