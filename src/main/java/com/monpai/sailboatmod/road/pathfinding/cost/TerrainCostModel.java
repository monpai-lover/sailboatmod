package com.monpai.sailboatmod.road.pathfinding.cost;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

public class TerrainCostModel {
    private static final double ORTHO_STEP = 1.0;
    private static final double DIAG_STEP = 1.414;
    private static final double WATER_BIOME_COST = 12.0;
    private static final double WATER_COLUMN_BASE_PENALTY = 20.0;
    private static final double WATER_DEPTH_SQUARED_WEIGHT = 0.5;

    private final PathfindingConfig config;

    public TerrainCostModel(PathfindingConfig config) {
        this.config = config;
    }

    public double moveCost(int fromX, int fromZ, int toX, int toZ, TerrainSamplingCache cache) {
        boolean diagonal = (fromX != toX) && (fromZ != toZ);
        double stepCost = diagonal ? DIAG_STEP : ORTHO_STEP;

        int fromH = cache.getHeight(fromX, fromZ);
        int toH = cache.getHeight(toX, toZ);
        double elevationCost = config.getElevationWeight() * Math.abs(toH - fromH);

        double biomeCost = 0;
        if (cache.isWaterBiome(toX, toZ)) {
            biomeCost = config.getBiomeWeight() * WATER_BIOME_COST;
        }

        double stabilityCost = config.getStabilityWeight() * cache.terrainStability(toX, toZ);

        double waterCost = 0;
        if (cache.isWater(toX, toZ)) {
            int depth = cache.getWaterDepth(toX, toZ);
            waterCost = WATER_COLUMN_BASE_PENALTY + (depth * depth) * config.getWaterDepthWeight() * WATER_DEPTH_SQUARED_WEIGHT;
        }

        double nearWaterCost = cache.isNearWater(toX, toZ) ? config.getNearWaterCost() : 0;

        return stepCost + elevationCost + biomeCost + stabilityCost + waterCost + nearWaterCost;
    }

    public double heuristic(int x, int z, int goalX, int goalZ) {
        int dx = Math.abs(x - goalX);
        int dz = Math.abs(z - goalZ);
        return config.getHeuristicWeight() * (dx + dz);
    }

    public double deviationCost(int x, int z, BlockPos start, BlockPos end) {
        double lineX = end.getX() - start.getX();
        double lineZ = end.getZ() - start.getZ();
        double len = Math.sqrt(lineX * lineX + lineZ * lineZ);
        if (len < 1e-6) return 0;
        double dx = x - start.getX();
        double dz = z - start.getZ();
        double perpDist = Math.abs(dx * lineZ - dz * lineX) / len;
        return config.getDeviationWeight() * perpDist;
    }
}
