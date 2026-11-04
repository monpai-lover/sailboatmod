package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.Prediction;
import com.monpai.sailboatmod.route.terrain.PredictionBudget;
import com.monpai.sailboatmod.route.terrain.PrefetchGate;
import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.*;

class NarrowWaterwayRegressionTest {
    @Test @Timeout(45)
    void anExactlyHullWidthCanalRemainsNavigableInBothDirections() {
        check((x, z) -> x < 120 || x > 240 || (z >= 0 && z <= 1), "two-block canal");
    }

    @Test @Timeout(45)
    void narrowCanalsWithTwoRightAngleBendsRemainNavigableInBothDirections() {
        check((x, z) -> x < 100 || x > 300 || (x <= 183 && z >= 0 && z <= 3)
                || (x >= 180 && x <= 183 && z >= 0 && z <= 43)
                || (x >= 180 && z >= 40 && z <= 43), "bent four-block canal");
    }

    private static void check(BiPredicate<Integer, Integer> channel, String name) {
        BiPredicate<Integer, Integer> water = (x, z) -> x >= -80 && x <= 460 && z >= -80 && z <= 100 && channel.test(x, z);
        TerrainPredictor predictor = new TerrainPredictor() {
            @Override public Prediction predictChunk(int cx, int cz, long wait) {
                boolean[] wet = new boolean[256];
                int[] heights = new int[256];
                for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    wet[z * 16 + x] = water.test(cx * 16 + x, cz * 16 + z);
                    heights[z * 16 + x] = 62;
                }
                return Prediction.ready(new NbtChunkWaterReader.ChunkColumns(wet, heights));
            }
            @Override public PrefetchGate gate() { return PrefetchGate.ALWAYS_READY; }
        };
        for (boolean reverse : new boolean[]{false, true}) {
            BlockPos start = new BlockPos(reverse ? 384 : 0, 63, 20);
            BlockPos goal = new BlockPos(reverse ? 0 : 384, 63, 20);
            var result = WaterAutoRouteServiceBench.plan(start, goal, 63, predictor,
                    PredictionBudget.sixtySeconds(), (x, z, spacing) -> Optional.of(
                            new SurfaceSample(water.test(x, z) ? 63 : 65, null, water.test(x, z), water.test(x, z) ? 1 : 0)), 20_000, 0);
            assertTrue(result.successful(), name + " reverse=" + reverse + " reason=" + result.reason());
            List<BlockPos> path = result.value();
            assertEquals(start, path.getFirst());
            assertEquals(goal, path.getLast());
            for (int i = 1; i < path.size(); i++) {
                BlockPos a = path.get(i - 1), b = path.get(i);
                assertTrue(WaterGridTraversal.all(a.getX(), a.getZ(), b.getX(), b.getZ(), (x, z) ->
                        water.test(x, z) && water.test(x + 1, z) && water.test(x, z + 1) && water.test(x + 1, z + 1)),
                        name + " must preserve a water-only 2x2 footprint");
            }
        }
    }
}
