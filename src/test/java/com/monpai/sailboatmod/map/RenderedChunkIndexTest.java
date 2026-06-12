package com.monpai.sailboatmod.map;

import com.monpai.sailboatmod.roadplanner.map.RoadMapTileSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderedChunkIndexTest {
    @Test
    void marksFullTileAsSixteenBySixteenChunks() {
        RenderedChunkIndex index = new RenderedChunkIndex();

        int marked = index.markTile("minecraft:overworld", 2, -1, fullMask());

        assertEquals(256, marked);
        assertTrue(index.isRendered("minecraft:overworld", 32, -16));
        assertTrue(index.isRendered("minecraft:overworld", 47, -1));
        assertFalse(index.isRendered("minecraft:overworld", 48, -1));
        assertFalse(index.isRendered("minecraft:the_nether", 32, -16));
    }

    @Test
    void marksOnlyChunksCoveredByPartialMask() {
        RenderedChunkIndex index = new RenderedChunkIndex();
        boolean[] mask = new boolean[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                mask[y * RoadMapTileSpec.TILE_PIXELS + x] = true;
            }
        }

        int marked = index.markTile("minecraft:overworld", 0, 0, mask);

        assertEquals(1, marked);
        assertTrue(index.isRendered("minecraft:overworld", 0, 0));
        assertFalse(index.isRendered("minecraft:overworld", 1, 0));
        assertFalse(index.isRendered("minecraft:overworld", 0, 1));
    }

    @Test
    void clearDimensionRemovesOnlyThatDimension() {
        RenderedChunkIndex index = new RenderedChunkIndex();
        index.markChunk("minecraft:overworld", 4, 5);
        index.markChunk("minecraft:the_nether", 4, 5);

        index.clearDimension("minecraft:overworld");

        assertFalse(index.isRendered("minecraft:overworld", 4, 5));
        assertTrue(index.isRendered("minecraft:the_nether", 4, 5));
    }

    @Test
    void detectsAnyRenderedChunkInsideTile() {
        RenderedChunkIndex index = new RenderedChunkIndex();
        index.markChunk("minecraft:overworld", 18, 19);

        assertTrue(index.hasAnyRenderedChunkInTile("minecraft:overworld", 1, 1));
        assertFalse(index.hasAnyRenderedChunkInTile("minecraft:overworld", 2, 1));
    }

    private static boolean[] fullMask() {
        boolean[] mask = new boolean[RoadMapTileSpec.TILE_PIXELS * RoadMapTileSpec.TILE_PIXELS];
        java.util.Arrays.fill(mask, true);
        return mask;
    }
}
