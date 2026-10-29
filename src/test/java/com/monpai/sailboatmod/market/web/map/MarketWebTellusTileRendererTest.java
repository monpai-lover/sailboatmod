package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.roadplanner.map.MapBlockColors;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRenderStyle;
import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebTellusTileRendererTest {
    private static final int PIXELS = MarketWebTellusTileRenderer.TILE_PIXELS * MarketWebTellusTileRenderer.TILE_PIXELS;

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void tileBoundsFollowTheSquareGrid() {
        assertEquals(512L, MarketWebTellusTileRenderer.blockMinX(0, 1));
        assertEquals(-4096L, MarketWebTellusTileRenderer.blockMinX(3, -1));
        assertEquals(-1024L, MarketWebTellusTileRenderer.blockMinZ(1, -1));
        assertEquals(2L * 512 * 2048, MarketWebTellusTileRenderer.blockMinX(11, 2));
    }

    @Test
    void onlyTilesOverlappingTheEarthAreRendered() {
        // at world scale 30 the Earth spans about +-667,918 blocks on both axes
        assertTrue(MarketWebTellusTileRenderer.overlapsEarth(4, 39, -14, 30.0D), "Everest");
        assertTrue(MarketWebTellusTileRenderer.overlapsEarth(4, -82, 81, 30.0D), "the tile holding the south-west corner");
        assertFalse(MarketWebTellusTileRenderer.overlapsEarth(4, 82, 0, 30.0D), "east of the antimeridian");
        assertFalse(MarketWebTellusTileRenderer.overlapsEarth(0, 0, -1306, 30.0D), "north of the Mercator limit");
        assertFalse(MarketWebTellusTileRenderer.overlapsEarth(4, 262_144, 0, 30.0D), "would overflow int block coordinates");
        assertFalse(MarketWebTellusTileRenderer.overlapsEarth(0, Integer.MIN_VALUE, 0, 30.0D));
        assertFalse(MarketWebTellusTileRenderer.overlapsEarth(0, 0, 0, 0.0D), "no scale, no Earth");
    }

    @Test
    void samplesPixelCenters() {
        List<int[]> calls = new ArrayList<>();
        TerrainSurfaceSampler recording = (x, z, bps) -> {
            calls.add(new int[]{x, z, bps});
            return Optional.of(new SurfaceSample(64, Blocks.STONE.defaultBlockState(), false, 0));
        };

        MarketWebTellusTileRenderer.render(recording, 2, 1, -1);

        assertEquals(PIXELS + 512, calls.size(), "one halo row plus the output pixels");
        List<int[]> firstRow = calls.stream().filter(call -> call[1] == -2048 + 2).toList();
        assertEquals(512, firstRow.size());
        assertEquals(2048 + 2, firstRow.get(0)[0]);
        assertEquals(4, firstRow.get(0)[2]);
        assertEquals(2048 + 4 + 2, firstRow.get(1)[0], "pixels advance along x first");
        List<int[]> haloRow = calls.stream().filter(call -> call[1] == -2048 + 2 - 4).toList();
        assertEquals(512, haloRow.size(), "the halo uses the same pixel-center grid immediately to the north");
        assertEquals(firstRow.get(0)[0], haloRow.get(0)[0]);
        assertEquals(firstRow.get(511)[0], haloRow.get(511)[0]);
        assertEquals(4, haloRow.get(0)[2]);
    }

    @Test
    void waterUsesDepthStyle() {
        MarketWebTellusTileRenderer.Rendered tile = MarketWebTellusTileRenderer.render(
                (x, z, bps) -> Optional.of(new SurfaceSample(64, Blocks.WATER.defaultBlockState(), true, 5)), 0, 0, 0);

        assertEquals(RoadMapRenderStyle.styleWater(5), tile.argb()[0]);
        assertEquals(RoadMapRenderStyle.styleWater(5), tile.argb()[PIXELS - 1]);
    }

    @Test
    void flatLandHasNoShade() {
        MarketWebTellusTileRenderer.Rendered tile = MarketWebTellusTileRenderer.render(
                (x, z, bps) -> Optional.of(new SurfaceSample(70, Blocks.GRASS_BLOCK.defaultBlockState(), false, 0)), 0, 0, 0);

        int expected = RoadMapRenderStyle.styleTerrainByDelta(
                MapBlockColors.colorFor(Blocks.GRASS_BLOCK.defaultBlockState(), 0xFF7F7F7F), 0);
        assertEquals(expected, tile.argb()[300 * 512 + 300]);
    }

    @Test
    void slopeShadesAgainstTheNorthNeighbour() {
        // height rises by 1 block per block southward
        MarketWebTellusTileRenderer.Rendered tile = MarketWebTellusTileRenderer.render(
                (x, z, bps) -> Optional.of(new SurfaceSample(64 + z, Blocks.GRASS_BLOCK.defaultBlockState(), false, 0)), 0, 0, 0);

        int grass = MapBlockColors.colorFor(Blocks.GRASS_BLOCK.defaultBlockState(), 0xFF7F7F7F);
        assertEquals(RoadMapRenderStyle.styleTerrainByDelta(grass, 1), tile.argb()[5], "first row uses the northern halo");
        assertEquals(RoadMapRenderStyle.styleTerrainByDelta(grass, 1), tile.argb()[512 + 5]);
    }

    @Test
    void adjacentTilesKeepContinuousRisingAndFallingSlopesAtDifferentZooms() {
        int grass = MapBlockColors.colorFor(Blocks.GRASS_BLOCK.defaultBlockState(), 0xFF7F7F7F);
        for (int zoom : new int[]{0, 2}) {
            int bps = 1 << zoom;
            for (int slope : new int[]{1, -2}) {
                TerrainSurfaceSampler sampler = (x, z, blocksPerSample) -> Optional.of(
                        new SurfaceSample(10000 + slope * z, Blocks.GRASS_BLOCK.defaultBlockState(), false, 0));
                for (int northTileY : new int[]{-2, 0}) {
                    MarketWebTellusTileRenderer.Rendered north = MarketWebTellusTileRenderer.render(sampler, zoom, -1, northTileY);
                    MarketWebTellusTileRenderer.Rendered south = MarketWebTellusTileRenderer.render(sampler, zoom, -1, northTileY + 1);
                    int delta = Math.round(slope * bps / (float) Math.max(1, bps / 4));
                    int[] expected = new int[512];
                    Arrays.fill(expected, RoadMapRenderStyle.styleTerrainByDelta(grass, delta));
                    String boundary = "zoom " + zoom + " slope " + slope + " tileY " + northTileY;
                    assertArrayEquals(expected, Arrays.copyOfRange(north.argb(), PIXELS - 512, PIXELS), boundary);
                    assertArrayEquals(expected, Arrays.copyOfRange(south.argb(), 0, 512), boundary);
                    assertArrayEquals(expected, Arrays.copyOfRange(south.argb(), 512, 1024), boundary);
                }
            }
        }
    }

    @Test
    void waterInNorthHaloProvidesTheRealHeightForTheFirstLandRow() {
        MarketWebTellusTileRenderer.Rendered tile = MarketWebTellusTileRenderer.render(
                (x, z, bps) -> Optional.of(z < 0
                        ? new SurfaceSample(64, Blocks.WATER.defaultBlockState(), true, 5)
                        : new SurfaceSample(65, Blocks.GRASS_BLOCK.defaultBlockState(), false, 0)), 0, 0, 0);

        int grass = MapBlockColors.colorFor(Blocks.GRASS_BLOCK.defaultBlockState(), 0xFF7F7F7F);
        assertEquals(RoadMapRenderStyle.styleTerrainByDelta(grass, 1), tile.argb()[0]);
        assertEquals(RoadMapRenderStyle.styleTerrainByDelta(grass, 0), tile.argb()[512]);
        assertEquals(0, tile.failures());
    }

    @Test
    void unavailableNorthHaloFallsBackWithoutCountingAsOutputFailure() {
        int grass = MapBlockColors.colorFor(Blocks.GRASS_BLOCK.defaultBlockState(), 0xFF7F7F7F);
        for (boolean throwsException : new boolean[]{false, true}) {
            AtomicInteger haloAttempts = new AtomicInteger();
            MarketWebTellusTileRenderer.Rendered tile = MarketWebTellusTileRenderer.render((x, z, bps) -> {
                if (z < 0) {
                    haloAttempts.incrementAndGet();
                    if (throwsException) {
                        throw new IllegalStateException("north sample unavailable");
                    }
                    return Optional.empty();
                }
                return Optional.of(new SurfaceSample(64 + z, Blocks.GRASS_BLOCK.defaultBlockState(), false, 0));
            }, 0, 0, 0);

            assertEquals(512, haloAttempts.get());
            assertEquals(RoadMapRenderStyle.styleTerrainByDelta(grass, 0), tile.argb()[0]);
            assertEquals(RoadMapRenderStyle.styleTerrainByDelta(grass, 1), tile.argb()[512]);
            assertEquals(0, tile.failures(), "failed halo samples are outside the output image");
            assertTrue(tile.worthSaving());
        }
    }

    @Test
    void failedSamplesAreTransparent() {
        MarketWebTellusTileRenderer.Rendered tile = MarketWebTellusTileRenderer.render(
                (x, z, bps) -> x < 100 ? Optional.empty()
                        : Optional.of(new SurfaceSample(70, Blocks.SAND.defaultBlockState(), false, 0)), 0, 0, 0);

        assertEquals(0, tile.argb()[50] >>> 24);
        assertEquals(0xFF, tile.argb()[200] >>> 24);
        assertEquals(100 * 512, tile.failures());
    }

    @Test
    void mostlyFailedTileIsNotWorthSaving() {
        assertFalse(render(0.6).worthSaving());
        assertTrue(render(0.4).worthSaving());
    }

    @Test
    void pngRoundTripsPixels() throws Exception {
        MarketWebTellusTileRenderer.Rendered tile = MarketWebTellusTileRenderer.render(
                (x, z, bps) -> Optional.of(new SurfaceSample(64, Blocks.WATER.defaultBlockState(), true, 3)), 0, 0, 0);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(MarketWebTellusTileRenderer.png(tile)));

        assertEquals(512, image.getWidth());
        assertEquals(RoadMapRenderStyle.styleWater(3), image.getRGB(10, 10));
    }

    private static MarketWebTellusTileRenderer.Rendered render(double failedShare) {
        int failedColumns = (int) Math.round(512 * failedShare);
        return MarketWebTellusTileRenderer.render((x, z, bps) -> x < failedColumns ? Optional.empty()
                : Optional.of(new SurfaceSample(70, Blocks.SAND.defaultBlockState(), false, 0)), 0, 0, 0);
    }
}
