package com.monpai.sailboatmod.route.terrain;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TellusSurfaceSamplersTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void forLevelIsEmptyForNullLevel() {
        assertTrue(TellusSurfaceSamplers.forLevel(null).isEmpty());
    }

    @Test
    void worldScaleIsEmptyForNullLevel() {
        assertTrue(TellusSurfaceSamplers.worldScale(null).isEmpty());
    }

    @Test
    void maxTileZoomFollowsTheSpike() {
        assertEquals(4, TellusSurfaceSamplers.MAX_TILE_ZOOM);
    }

    @Test
    void surfaceSampleRejectsNegativeDepth() {
        assertThrows(IllegalArgumentException.class,
                () -> new SurfaceSample(64, Blocks.WATER.defaultBlockState(), true, -1));
    }

    @Test
    void dryLandHasNoDepth() {
        assertThrows(IllegalArgumentException.class,
                () -> new SurfaceSample(70, Blocks.GRASS_BLOCK.defaultBlockState(), false, 3));
    }
    @Test
    void incompatibleTellusApiDisablesTheSampler() {
        assertTrue(TellusSurfaceSamplers.checked(() -> "sampler", () -> {
            throw new NoSuchMethodError("samplePreloadTerrain");
        }).isEmpty());
        assertTrue(TellusSurfaceSamplers.checked(() -> "sampler", () -> {
            throw new NoSuchMethodException("landMaskKnown");
        }).isEmpty());
        assertEquals("sampler", TellusSurfaceSamplers.checked(() -> "sampler", () -> { }).orElseThrow());
    }

}
