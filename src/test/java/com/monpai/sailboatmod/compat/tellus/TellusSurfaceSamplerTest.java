package com.monpai.sailboatmod.compat.tellus;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TellusSurfaceSamplerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void zoomPreservesTheGeneratedOceanDepthAtTheSameBlock() {
        List<Double> requestedMeters = new ArrayList<>();
        TellusSurfaceSampler sampler = new TellusSurfaceSampler(100.0D, 64,
                (x, z, meters) -> {
                    requestedMeters.add(meters);
                    // Observed at world-scale 100: a coarser DEM changes depth 4 into depth 3,
                    // crossing the web map's water-brightness band although the block is unchanged.
                    return new TellusSurfaceSampler.NativeSample(meters <= 100.0D ? 60 : 61, 80, false, true);
                }, (x, z, height) -> Blocks.STONE.defaultBlockState());

        var nativePixel = sampler.sample(28110, -48800, 1).orElseThrow();
        var coarsePixel = sampler.sample(28110, -48800, 16).orElseThrow();

        assertEquals(4, nativePixel.waterDepth());
        assertEquals(nativePixel.waterDepth(), coarsePixel.waterDepth(), "zoom must preserve the same ocean-depth band");
        assertEquals(List.of(100.0D, 100.0D), requestedMeters);
    }

    @Test
    void zoomPreservesNarrowCoastGeometryInsteadOfSelectingAnotherDataSource() {
        TellusSurfaceSampler sampler = new TellusSurfaceSampler(100.0D, 64,
                (x, z, meters) -> meters <= 100.0D
                        ? new TellusSurfaceSampler.NativeSample(63, 80, false, true)
                        : new TellusSurfaceSampler.NativeSample(65, 30, true, true),
                (x, z, height) -> Blocks.GRASS_BLOCK.defaultBlockState());

        assertTrue(sampler.sample(27994, -48634, 1).orElseThrow().water());
        assertTrue(sampler.sample(27994, -48634, 8).orElseThrow().water(),
                "pixel spacing must not change land and water classification at a fixed block");
    }
}
