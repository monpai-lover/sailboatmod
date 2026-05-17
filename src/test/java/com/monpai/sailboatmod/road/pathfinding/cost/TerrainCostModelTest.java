package com.monpai.sailboatmod.road.pathfinding.cost;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TerrainCostModelTest {
    @Test
    void slopeCostAppliesSoftAndHardThresholdPenalties() {
        TerrainCostModel model = new TerrainCostModel(new PathfindingConfig());

        assertEquals(0.0, model.slopeCost(0, 0, 10, 0, heightCache(Map.of(0, 64, 10, 69))));
        assertEquals(TerrainCostModel.SLOPE_SOFT_PENALTY,
                model.slopeCost(0, 0, 10, 0, heightCache(Map.of(0, 64, 10, 70))));
        assertEquals(TerrainCostModel.SLOPE_HARD_PENALTY,
                model.slopeCost(0, 0, 10, 0, heightCache(Map.of(0, 64, 10, 73))));
    }

    @Test
    void moveCostIncludesSlopePenalty() {
        PathfindingConfig config = new PathfindingConfig();
        config.setElevationWeight(0);
        config.setBiomeWeight(0);
        config.setStabilityWeight(0);
        config.setWaterDepthWeight(0);
        config.setNearWaterCost(0);
        TerrainCostModel model = new TerrainCostModel(config);

        assertEquals(1.0 + TerrainCostModel.SLOPE_SOFT_PENALTY * 0.1,
                model.moveCost(0, 0, 10, 0, heightCache(Map.of(0, 64, 10, 70))));
    }

    private static TerrainSamplingCache heightCache(Map<Integer, Integer> heightsByX) {
        return new TerrainSamplingCache(null, PathfindingConfig.SamplingPrecision.NORMAL) {
            @Override
            public int getHeight(int x, int z) {
                return heightsByX.getOrDefault(x, 64);
            }

            @Override
            public boolean isWater(int x, int z) {
                return false;
            }

            @Override
            public boolean isWaterBiome(int x, int z) {
                return false;
            }

            @Override
            public boolean isNearWater(int x, int z) {
                return false;
            }

            @Override
            public boolean isBlocked(int x, int z) {
                return false;
            }

            @Override
            public double terrainStability(int x, int z) {
                return 0;
            }
        };
    }
}
