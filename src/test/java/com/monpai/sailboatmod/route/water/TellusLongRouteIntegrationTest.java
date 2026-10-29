package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.Prediction;
import com.monpai.sailboatmod.route.terrain.PredictionBudget;
import com.monpai.sailboatmod.route.terrain.PrefetchGate;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TellusLongRouteIntegrationTest {
    @Test
    void existingTellusRescueEntrypointPlansBeyondTheOldRadiusThroughANarrowSea() {
        AtomicInteger predictedChunks = new AtomicInteger();
        TerrainPredictor predictor = new TerrainPredictor() {
            @Override public Prediction predictChunk(int cx, int cz, long maxWaitMillis) {
                predictedChunks.incrementAndGet();
                boolean[] water = new boolean[256];
                int[] surface = new int[256];
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    int wx = cx * 16 + x, wz = cz * 16 + z;
                    water[z * 16 + x] = wx >= 0 && wx <= 33_024 && wz >= 0 && wz < 64
                            && !(wx >= 4096 && wx <= 8192 && wz <= 40);
                    surface[z * 16 + x] = 63;
                }
                return Prediction.ready(new NbtChunkWaterReader.ChunkColumns(water, surface));
            }
            @Override public PrefetchGate gate() { return PrefetchGate.ALWAYS_READY; }
        };
        DockBerthResolver.DockZone target = new DockBerthResolver.DockZone(new BlockPos(32_768, 63, 20), -4, 4, -4, 4);

        // A null actual level deliberately makes world generation unavailable. All terrain comes from the fake predictor.
        WaterRouteResult<List<BlockPos>> result = WaterAutoRouteService.planTellusRescue(null, 63,
                new BlockPos(50, 63, 20), target, predictor, PredictionBudget.sixtySeconds());

        assertTrue(result.successful(), "reason=" + result.reason());
        assertTrue(result.value().stream().anyMatch(p -> p.getZ() > 40), "the route must clear the land barrier");
        assertTrue(result.value().getLast().getX() >= 32_764);
        assertTrue(predictedChunks.get() < 30_000, "planning is bounded and does not eagerly bake a giant rectangle");
    }
}
