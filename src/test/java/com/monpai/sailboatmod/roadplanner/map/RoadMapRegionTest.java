package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadMapRegionTest {
    @Test
    void centeredRegionUsesFixedInclusiveExclusiveBounds() {
        RoadMapRegion region = RoadMapRegion.centeredOn(new BlockPos(100, 70, 200), 128, MapLod.LOD_1);

        assertEquals(128, region.regionSize());
        assertEquals(36, region.minX());
        assertEquals(164, region.maxXExclusive());
        assertEquals(136, region.minZ());
        assertEquals(264, region.maxZExclusive());
        assertEquals(128, region.pixelWidth());
        assertEquals(128, region.pixelHeight());
        assertTrue(region.containsWorldXZ(36, 136));
        assertTrue(region.containsWorldXZ(163, 263));
        assertFalse(region.containsWorldXZ(164, 264));
    }

    @Test
    void lodControlsPixelDimensions() {
        assertEquals(1, MapLod.LOD_1.blocksPerPixel());
        assertEquals(2, MapLod.LOD_2.blocksPerPixel());
        assertEquals(4, MapLod.LOD_4.blocksPerPixel());

        assertEquals(64, RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_2).pixelWidth());
        assertEquals(32, RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_4).pixelHeight());
        assertThrows(IllegalArgumentException.class, () -> RoadMapRegion.centeredOn(BlockPos.ZERO, 130, MapLod.LOD_4));
    }

    @Test
    void convertsBetweenWorldAndGuiCoordinates() {
        RoadMapRegion region = RoadMapRegion.centeredOn(new BlockPos(100, 70, 200), 128, MapLod.LOD_1);
        RoadMapViewport viewport = new RoadMapViewport(10, 20, 256, 128);

        RoadMapPoint guiPoint = region.worldToGui(new BlockPos(100, 72, 200), viewport);

        assertEquals(138.0D, guiPoint.x(), 0.0001D);
        assertEquals(84.0D, guiPoint.y(), 0.0001D);
        assertEquals(100, region.guiToWorldXZ(guiPoint.x(), guiPoint.y(), viewport).getX());
        assertEquals(200, region.guiToWorldXZ(guiPoint.x(), guiPoint.y(), viewport).getZ());
    }

    @Test
    void snapshotStoresImmutableColumns() {
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_4);
        RoadMapColumnSample sample = new RoadMapColumnSample(0, 64, 0, 0xFF00AA00, false, 0, 63);
        RoadMapSnapshot snapshot = new RoadMapSnapshot(42L, region, List.of(sample), new int[]{0xFF00AA00});

        assertEquals(sample, snapshot.columns().get(0));
        assertEquals(0xFF00AA00, snapshot.argbPixels()[0]);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.columns().add(sample));

        int[] pixels = snapshot.argbPixels();
        pixels[0] = 0;
        assertEquals(0xFF00AA00, snapshot.argbPixels()[0]);
    }
}
