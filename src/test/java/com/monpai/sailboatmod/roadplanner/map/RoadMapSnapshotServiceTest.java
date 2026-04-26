package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadMapSnapshotServiceTest {
    @Test
    void colorizerDarkensDeepWaterPixels() {
        RoadMapColorizer colorizer = new RoadMapColorizer();
        RoadMapColumnSample shallow = new RoadMapColumnSample(0, 62, 0, 0xFF3366CC, true, 1, 62);
        RoadMapColumnSample deep = new RoadMapColumnSample(0, 62, 0, 0xFF3366CC, true, 8, 62);

        assertTrue(brightness(colorizer.color(shallow)) > brightness(colorizer.color(deep)));
    }

    @Test
    void colorizerUsesReliefForLandBrightness() {
        RoadMapColorizer colorizer = new RoadMapColorizer();
        RoadMapColumnSample ridge = new RoadMapColumnSample(0, 70, 0, 0xFF669933, false, 0, 64);
        RoadMapColumnSample hollow = new RoadMapColumnSample(0, 58, 0, 0xFF669933, false, 0, 64);

        assertTrue(brightness(colorizer.color(ridge)) > brightness(colorizer.color(hollow)));
    }

    @Test
    void sampleColumnsForTestSamplesEveryLodPixel() {
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_4);

        List<RoadMapColumnSample> samples = RoadMapSnapshotService.sampleColumnsForTest(region, (worldX, worldZ) ->
                new RoadMapColumnSample(worldX, 64, worldZ, 0xFF00AA00, false, 0, 64));

        assertEquals(region.pixelWidth() * region.pixelHeight(), samples.size());
        assertEquals(region.minX(), samples.get(0).worldX());
        assertEquals(region.minZ(), samples.get(0).worldZ());
        assertEquals(region.minX() + 4, samples.get(1).worldX());
    }

    @Test
    void directExecutorBuildsSnapshotSynchronouslyForTests() {
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_4);
        RoadMapSnapshotService service = RoadMapSnapshotService.directExecutorForTest(new RoadMapColorizer());

        CompletableFuture<RoadMapSnapshot> future = service.buildSnapshotAsync(99L, region, (worldX, worldZ) ->
                new RoadMapColumnSample(worldX, 64, worldZ, 0xFF00AA00, false, 0, 64));

        assertTrue(future.isDone());
        assertEquals(99L, future.join().createdAtGameTime());
        assertEquals(region.pixelWidth() * region.pixelHeight(), future.join().argbPixels().length);
    }

    private static int brightness(int argb) {
        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;
        return red + green + blue;
    }
}
