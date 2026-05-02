package com.monpai.sailboatmod.roadplanner.map;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadMapLodPyramidTest {
    @Test
    void deriveDisplayPixelsRepeatsCoarseColorsForLowerLods() {
        int[] source = new int[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
        Arrays.fill(source, 0xFF223344);
        source[0] = 0xFF556677;

        int[] derived = RoadMapLodPyramid.deriveDisplayPixels(
                source,
                RoadMapTileSpec.TILE_PIXELS,
                RoadMapTileSpec.TILE_PIXELS,
                MapLod.LOD_4);

        assertEquals(RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS, derived.length);
        assertEquals(derived[0], derived[1]);
        assertEquals(derived[0], derived[RoadMapTileSpec.TILE_PIXELS]);
    }
}
