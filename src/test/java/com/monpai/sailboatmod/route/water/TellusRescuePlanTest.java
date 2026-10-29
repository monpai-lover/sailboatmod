package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.Prediction;
import com.monpai.sailboatmod.route.terrain.PredictionBudget;
import com.monpai.sailboatmod.route.terrain.PrefetchGate;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** In Tellus worlds the noise-based rescue cannot sample terrain, so the full rescue plans on NBT + prediction. */
class TellusRescuePlanTest {
    private static final DockBerthResolver.DockZone DESTINATION =
            new DockBerthResolver.DockZone(new BlockPos(230, 63, 20), -8, 8, -8, 8);

    @Test
    void rescuePlansAroundAWallToTheDestinationBerth() {
        WaterRouteResult<List<BlockPos>> plan = WaterAutoRouteService.planTellusRescue(null, 63, new BlockPos(50, 63, 20),
                DESTINATION, predictor(40), PredictionBudget.sixtySeconds());

        assertTrue(plan.successful(), "reason: " + plan.reason());
        List<BlockPos> path = plan.value();
        assertTrue(path.size() >= 2);
        assertTrue(Math.abs(path.get(0).getX() - 50) <= 16);
        BlockPos last = path.get(path.size() - 1);
        assertTrue(Math.abs(last.getX() - 230) <= 8 && Math.abs(last.getZ() - 20) <= 8, "ends at the berth: " + last);
        assertTrue(path.stream().anyMatch(p -> p.getZ() > 40), "goes round the wall end");
    }

    @Test
    void rescueFailsWhenTheWayIsSealed() {
        WaterRouteResult<List<BlockPos>> plan = WaterAutoRouteService.planTellusRescue(null, 63, new BlockPos(50, 63, 20),
                DESTINATION, predictor(63), PredictionBudget.sixtySeconds());

        assertFalse(plan.successful());
    }

    /** Predicted water over x 0..255, z 0..63 with a land wall at x=100, z 0..wallEnd; land elsewhere. */
    private static TerrainPredictor predictor(int wallEnd) {
        return new TerrainPredictor() {
            @Override
            public Prediction predictChunk(int cx, int cz, long maxWaitMillis) {
                boolean[] water = new boolean[256];
                int[] surface = new int[256];
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = (cx << 4) + lx;
                        int z = (cz << 4) + lz;
                        boolean inSea = x >= 0 && x < 256 && z >= 0 && z < 64;
                        water[lz * 16 + lx] = inSea && !(x == 100 && z <= wallEnd);
                        surface[lz * 16 + lx] = 62;
                    }
                }
                return Prediction.ready(new NbtChunkWaterReader.ChunkColumns(water, surface));
            }

            @Override
            public PrefetchGate gate() {
                return PrefetchGate.ALWAYS_READY;
            }
        };
    }
}
