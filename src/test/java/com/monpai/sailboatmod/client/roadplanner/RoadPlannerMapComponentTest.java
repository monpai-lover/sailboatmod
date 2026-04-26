package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshot;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerMapComponentTest {
    @Test
    void componentConvertsBetweenGuiAndWorldCoordinates() {
        RoadMapRegion region = RoadMapRegion.centeredOn(new BlockPos(100, 70, 200), 128, MapLod.LOD_1);
        RoadPlannerMapComponent component = new RoadPlannerMapComponent(region, new RoadMapViewport(10, 20, 256, 128));

        BlockPos world = component.guiToWorldXZ(138, 84);

        assertEquals(100, world.getX());
        assertEquals(200, world.getZ());
        assertEquals(138.0D, component.worldToGui(new BlockPos(100, 64, 200)).x(), 0.0001D);
    }

    @Test
    void textureQueuesOnlyMeaningfulDirtySnapshots() {
        RoadPlannerMapTexture texture = new RoadPlannerMapTexture(25);
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_4);
        RoadMapSnapshot snapshot = new RoadMapSnapshot(1L, region, List.of(), filledPixels(region, 0xFF00AA00));

        assertTrue(texture.acceptSnapshot(snapshot));
        assertFalse(texture.acceptSnapshot(snapshot));
        assertEquals(1, texture.drainUploadsForRenderThread().size());
        assertEquals(0, texture.pendingUploadCount());
    }

    private int[] filledPixels(RoadMapRegion region, int argb) {
        int[] pixels = new int[region.pixelWidth() * region.pixelHeight()];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = argb;
        }
        return pixels;
    }
}
