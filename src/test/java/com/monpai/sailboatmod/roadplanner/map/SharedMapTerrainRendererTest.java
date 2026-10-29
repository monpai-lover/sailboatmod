package com.monpai.sailboatmod.roadplanner.map;

import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SharedMapTerrainRendererTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test
    void nativeIngameTilesUseTheSameCentersNorthSlopeAndPaletteAsTellus() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        int[] pixels = render((x,z,bps) -> {
            assertEquals(1, bps);
            assertTrue(x >= -256 && x < 0);
            assertTrue(z >= -257 && z < 0);
            calls.incrementAndGet();
            return Optional.of(new SurfaceSample(1000 + z, Blocks.GRASS_BLOCK.defaultBlockState(), false, 0));
        }, -256, -256, 256, 256);
        int expected = RoadMapRenderStyle.styleTerrainByDelta(MapBlockColors.lookup("minecraft:grass_block"), 1);
        assertEquals(256 * 257, calls.get(), "one north halo row is sampled without generating chunks");
        assertEquals(expected, pixels[0]);
        assertEquals(expected, pixels[pixels.length - 1]);
    }

    @Test
    void missingDataStaysTransparentAndDoesNotInventCliffsAcrossGaps() throws Exception {
        int[] pixels = render((x,z,bps) -> z < 0 || z == 1 ? Optional.empty()
                : Optional.of(new SurfaceSample(z == 0 ? 60 : 65, Blocks.GRASS_BLOCK.defaultBlockState(), false, 0)),
                0, 0, 16, 16);
        int flat = RoadMapRenderStyle.styleTerrainByDelta(MapBlockColors.lookup("minecraft:grass_block"), 0);
        assertEquals(flat, pixels[0]);
        assertEquals(0, pixels[16]);
        assertEquals(flat, pixels[32]);
    }

    @Test
    void waterDepthAndUnavailableTellusSamplesKeepExistingMapRules() throws Exception {
        int[] pixels = render((x,z,bps) -> {
            if (x == 0) throw new IllegalStateException("data not ready");
            return Optional.of(new SurfaceSample(64, Blocks.WATER.defaultBlockState(), true, 5));
        }, 0, 0, 16, 16);
        assertEquals(0, pixels[0]);
        assertEquals(RoadMapRenderStyle.styleWater(5), pixels[1]);
        assertEquals(RoadMapRenderStyle.styleWater(5), pixels[255]);
    }

    private static int[] render(TerrainSurfaceSampler sampler, int x, int z, int width, int height) throws Exception {
        try {
            Class<?> type = Class.forName("com.monpai.sailboatmod.roadplanner.map.SharedMapTerrainRenderer");
            Method render = type.getMethod("render", TerrainSurfaceSampler.class, int.class, int.class, int.class, int.class);
            return (int[]) render.invoke(null, sampler, x, z, width, height);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            return fail("native maps need the shared Tellus/actual palette and adjacent north renderer");
        }
    }
}
