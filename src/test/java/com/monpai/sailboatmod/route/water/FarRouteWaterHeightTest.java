package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Saved Tellus water is inclusive at Y=64, while the validated boat coordinate is Y=65. */
class FarRouteWaterHeightTest {
    private static final int GENERATOR_SEA_LEVEL = 64;
    private static final BlockPos SOURCE = new BlockPos(1776 * 16 + 8, 65, -2752 * 16 + 8);
    private static final BlockPos TARGET = SOURCE.offset(100_000, 0, 0);

    private record Fixture(TerrainPredictor predictor, AtomicInteger nearCalls) { }

    private static Fixture fixture() throws Exception {
        var stored = NbtChunkWaterReader.decodeForRouting(
                ProtoChunkRoutingTest.fixture("target-initialize-light"), -64, 320);
        assertTrue(stored.hasTerrain());
        for (int i = 0; i < 256; i++) {
            assertTrue(stored.columns().water()[i]);
            assertEquals(GENERATOR_SEA_LEVEL, stored.columns().surfaceY()[i]);
        }
        AtomicInteger nearCalls = new AtomicInteger();
        TerrainPredictor near = new TerrainPredictor() {
            @Override public Prediction predictChunk(int cx, int cz, long waitMillis) {
                nearCalls.incrementAndGet();
                return Prediction.ready(stored.columns());
            }
            @Override public PrefetchGate gate() { return PrefetchGate.ALWAYS_READY; }
        };
        var far = new WaterAutoRouteService.FarRouteTerrain((x, z, spacing) -> Optional.of(
                new SurfaceSample(GENERATOR_SEA_LEVEL, null, true, 8)), null);
        return new Fixture(WaterAutoRouteService.farRoutePredictor(
                far, near, SOURCE, TARGET, GENERATOR_SEA_LEVEL), nearCalls);
    }

    @Test void validatedBoatHeightKeepsMaskAndSavedPortWaterOnTheSamePlane() throws Exception {
        Fixture fixture = fixture();
        Prediction near = fixture.predictor().predictChunk(1776, -2752, 1);
        assertEquals(Prediction.Status.READY, near.status());
        assertEquals(64, near.columns().surfaceY()[0]);
        Prediction mid = fixture.predictor().predictChunk(1776 + 500, -2752, 1);
        assertEquals(Prediction.Status.READY, mid.status());
        assertEquals(64, mid.columns().surfaceY()[0],
                "mask-only water must use validated source boat Y minus one, not generator sea level minus one");
        assertEquals(1, fixture.nearCalls().get(), "mid-route prediction must keep its mask-only fast path");
    }

    @Test void strictHullCanCrossThePortPredictorBoundaryWithoutAnArtificialHeightStep() throws Exception {
        Fixture fixture = fixture();
        RealBlockWaterMap map = new RealBlockWaterMap(null, GENERATOR_SEA_LEVEL).enableOnDemand()
                .retryUnknownPrediction().withPredictor(fixture.predictor(), PredictionBudget.sixtySeconds());
        // Chunk centre +1024 remains near; the adjacent centre +1040 uses the mask.
        int x = (1776 + 65) * 16 - 1;
        WaterColumn hull = map.sampleHullStrict(x, SOURCE.getZ(), 1);
        assertNotNull(hull);
        assertTrue(hull.passable(), "four real-water hull cells cannot become blocked at the predictor seam");
        assertEquals(65, hull.surfacePos().getY());
        assertEquals(1, fixture.nearCalls().get());
    }
}
