package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimPreviewTerrainServiceTest {
    @Test
    void visibleChunkWorkIsScheduledAheadOfPrefetchWork() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();

        service.enqueueViewportForTest("minecraft:overworld", 0, 0, 1, 3, 5L, "town|a");

        assertEquals(9, service.visibleQueueSizeForTest());
        assertTrue(service.prefetchQueueSizeForTest() > 9);
    }

    @Test
    void invalidatingChunkClearsTileAndDependentSnapshots() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        service.putTileForTest("minecraft:overworld", 2, 3, new int[] {1, 2, 3, 4});
        service.putViewportDependencyForTest("minecraft:overworld", 2, 3, "town|a|11");

        service.invalidateChunkForTest("minecraft:overworld", 2, 3);

        assertNull(service.getTileForTest("minecraft:overworld", 2, 3));
        assertTrue(service.invalidatedViewportKeysForTest().contains("town|a|11"));
    }
}
