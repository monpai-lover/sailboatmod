package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadMapTileRenderCacheTest {
    @Test
    void meaningfulCheckRejectsMostlyBlankSnapshots() {
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_4);
        int[] blankPixels = new int[region.pixelWidth() * region.pixelHeight()];
        int[] meaningfulPixels = blankPixels.clone();
        for (int index = 0; index < 128; index++) {
            meaningfulPixels[index] = 0xFF00AA00;
        }

        assertFalse(RoadMapTileRenderCache.isMeaningful(new RoadMapSnapshot(1L, region, List.of(), blankPixels), 25));
        assertTrue(RoadMapTileRenderCache.isMeaningful(new RoadMapSnapshot(1L, region, List.of(), meaningfulPixels), 25));
    }

    @Test
    void cacheUploadsTextureOnlyWhenSnapshotChanges() {
        RoadMapTileRenderCache cache = new RoadMapTileRenderCache(25);
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_4);
        RoadMapSnapshot snapshot = new RoadMapSnapshot(1L, region, List.of(), filledPixels(region, 0xFF00AA00));

        assertTrue(cache.acceptSnapshot(snapshot));
        assertEquals(1, cache.pendingUploadCount());
        assertFalse(cache.acceptSnapshot(snapshot));
        assertEquals(1, cache.pendingUploadCount());
        assertEquals(1, cache.drainPendingUploads().size());
        assertEquals(0, cache.pendingUploadCount());
    }

    @Test
    void schedulerThrottlesCurrentAndNeighborTileUpdates() {
        RoadMapTileUpdateScheduler scheduler = new RoadMapTileUpdateScheduler(1000L, 500L);

        assertTrue(scheduler.shouldUpdateCurrent("0_0", 0L));
        assertFalse(scheduler.shouldUpdateCurrent("0_0", 999L));
        assertTrue(scheduler.shouldUpdateCurrent("0_0", 1000L));
        assertTrue(scheduler.shouldUpdateNeighbor(1200L));
        assertFalse(scheduler.shouldUpdateNeighbor(1499L));
        assertTrue(scheduler.shouldUpdateNeighbor(1700L));
    }

    private int[] filledPixels(RoadMapRegion region, int argb) {
        int[] pixels = new int[region.pixelWidth() * region.pixelHeight()];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = argb;
        }
        return pixels;
    }
}
