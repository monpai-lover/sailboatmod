package com.monpai.sailboatmod.roadplanner.map;

import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import org.junit.jupiter.api.Test;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapTerrainLodRendererTest {
    @Test void coarseSamplesUsePixelCentresAndImmediateNorthReliefAtTheNativeWorldResolution() throws Exception {
        Set<String> sampled = new HashSet<>();
        TerrainSurfaceSampler sampler = (x, z, bps) -> {
            assertEquals(16, bps);
            sampled.add(x + "," + z);
            return Optional.of(new SurfaceSample(63, null, true, 12));
        };
        int[] pixels = (int[]) SharedMapTerrainRenderer.class.getMethod("render", TerrainSurfaceSampler.class,
                int.class, int.class, int.class, int.class, int.class).invoke(null, sampler, -4096, -4096, 2, 2, 16);
        assertEquals(4, pixels.length);
        assertTrue(sampled.contains("-4088,-4088"));
        assertTrue(sampled.contains("-4072,-4072"));
        assertTrue(sampled.contains("-4088,-4089"), "relief uses the same adjacent block as actual terrain, not a 16-block height difference");
        assertEquals(RoadMapRenderStyle.styleWater(12), pixels[0]);
    }
    @Test void partialPredictionRemainsRetryableAndKeepsPreviouslyKnownPixels() throws Exception {
        int[] first = new int[]{0xFF112233, 0, 0};
        int[] second = new int[]{0, 0xFF445566, 0};
        int[] merged = (int[]) SharedMapTerrainRenderer.class.getMethod("mergePrediction", int[].class, int[].class)
                .invoke(null, first, second);
        assertArrayEquals(new int[]{0xFF112233, 0xFF445566, 0}, merged);
        assertArrayEquals(new int[]{0xFF112233, 0, 0}, first);
        assertEquals(true, SharedMapTerrainRenderer.class.getMethod("hasUnknownPixels", int[].class).invoke(null, merged));
        assertEquals(false, SharedMapTerrainRenderer.class.getMethod("hasUnknownPixels", int[].class)
                .invoke(null, (Object) new int[]{0xFF112233, 0xFF445566}));
    }
}
