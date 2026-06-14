package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketWebMapTileCoordinateTest {
    @Test
    void baseTileContainsThirtyTwoByThirtyTwoChunks() {
        MarketWebMapTileCoordinate.Tile tile = MarketWebMapTileCoordinate.baseTileForChunk(33, -1);

        assertEquals(1, tile.x());
        assertEquals(-1, tile.z());
        assertEquals(1, MarketWebMapTileCoordinate.localChunkX(33));
        assertEquals(31, MarketWebMapTileCoordinate.localChunkZ(-1));
    }

    @Test
    void blockLocalPositionMapsIntoBaseTilePixel() {
        assertEquals(16, MarketWebMapTileCoordinate.basePixelX(33, 0));
        assertEquals(511, MarketWebMapTileCoordinate.basePixelZ(-1, 15));
    }

    @Test
    void zoomTileScalesBaseTileLikeSquaremap() {
        MarketWebMapTileCoordinate.Tile scaled = MarketWebMapTileCoordinate.scaleTile(3, -2, 1);

        assertEquals(1, scaled.x());
        assertEquals(-1, scaled.z());
    }
}
