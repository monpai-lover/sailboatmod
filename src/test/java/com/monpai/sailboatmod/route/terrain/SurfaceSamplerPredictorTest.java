package com.monpai.sailboatmod.route.terrain;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SurfaceSamplerPredictorTest {
    private final TerrainSurfaceSampler eastIsWater = (x, z, bps) -> Optional.of(x >= 100
            ? new SurfaceSample(63, Blocks.WATER.defaultBlockState(), true, 4)
            : new SurfaceSample(70, Blocks.STONE.defaultBlockState(), false, 0));

    @Test
    void midRouteChunksComeFromTheSurfaceSamplerAndPortsFromTheGenerator() {
        AtomicInteger generator = new AtomicInteger();
        TerrainPredictor near = new TerrainPredictor() {
            @Override public Prediction predictChunk(int cx, int cz, long maxWaitMillis) { generator.incrementAndGet(); return Prediction.notReady(); }
            @Override public PrefetchGate gate() { return PrefetchGate.ALWAYS_READY; }
        };
        SurfaceSamplerPredictor p = new SurfaceSamplerPredictor(eastIsWater, near, new BlockPos(0, 63, 0),
                new BlockPos(100_000, 63, 0), 1024, 63);

        assertEquals(Prediction.Status.NOT_READY, p.predictChunk(1, 1, 0).status());
        assertEquals(1, generator.get(), "port chunks keep the generator prediction");

        Prediction mid = p.predictChunk(6, 500, 0); // x 96..111: 4 land columns, 12 water columns per row
        assertEquals(Prediction.Status.READY, mid.status());
        assertFalse(mid.columns().water()[0]);
        assertTrue(mid.columns().water()[4]);
        assertEquals(62, mid.columns().surfaceY()[4], "top water block, like saved NBT and generator columns");
        assertEquals(1, generator.get());
    }

    @Test
    void landMaskTilingMatchesTellusZoomChoice() {
        assertEquals(11, LandMaskTiling.zoomFor(100, 1)); // Tellus clamps to the file's max zoom (10)
        assertEquals(7, LandMaskTiling.zoomFor(100, 16));
        assertEquals(1, LandMaskTiling.coarsening(2));
        assertEquals(16, LandMaskTiling.coarsening(32));
        assertEquals(LandMaskTiling.EQUATOR_METERS / 100 / 128, LandMaskTiling.tileBlocks(100, 7), 1e-6);
    }
}
