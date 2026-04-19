package com.monpai.sailboatmod.road.pathfinding.cost;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

public class TerrainCostModel {
    private static final double ORTHO_STEP = 1.0;
    private static final double DIAG_STEP = 1.414;
    private static final double WATER_BIOME_COST = 12.0;
    private static final double WATER_COLUMN_BASE_PENALTY = 800.0;
    private static final double WATER_DEPTH_SQUARED_WEIGHT = 2.0;
    public static final double SLOPE_SOFT_THRESHOLD = 0.5;
    public static final double SLOPE_HARD_THRESHOLD = 0.8;
    public static final double SLOPE_SOFT_PENALTY = 800.0;
    public static final double SLOPE_HARD_PENALTY = 8000.0;
    public static final double SOFT_GRADE_LIMIT = 0.08;
    public static final double HARD_GRADE_LIMIT = 0.15;
    public static final double SOFT_GRADE_PENALTY = 600.0;
    public static final double HARD_GRADE_PENALTY = 6000.0;
    public static final double CONTOUR_DISCOUNT = 0.45;
    public static final double GRADIENT_ALIGN_PENALTY = 80.0;

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

        double biomeCost = cache.isWaterBiome(toX, toZ) ? config.getBiomeWeight() * WATER_BIOME_COST : 0;
        double stabilityCost = config.getStabilityWeight() * cache.terrainStability(toX, toZ);
        double waterCost = waterCostAggressive(cache, toX, toZ);
        double nearWaterCost = cache.isNearWater(toX, toZ) ? config.getNearWaterCost() : 0;

        return stepCost + elevationCost + biomeCost + stabilityCost + waterCost + nearWaterCost;
    }

    public double waterCostAggressive(TerrainSamplingCache cache, int x, int z) {
        if (!cache.isWater(x, z)) return 0;
        int depth = cache.getWaterDepth(x, z);
        return WATER_COLUMN_BASE_PENALTY + (double)(depth * depth) * config.getWaterDepthWeight() * WATER_DEPTH_SQUARED_WEIGHT;
    }

    public double slopeCost(int fromX, int fromZ, int toX, int toZ, TerrainSamplingCache cache) {
        int fromH = cache.getHeight(fromX, fromZ);
        int toH = cache.getHeight(toX, toZ);
        double hDist = Math.sqrt((toX - fromX) * (toX - fromX) + (toZ - fromZ) * (toZ - fromZ));
        if (hDist < 0.001) return 0;
        double slope = Math.abs(toH - fromH) / hDist;
        if (slope > SLOPE_HARD_THRESHOLD) return SLOPE_HARD_PENALTY;
        if (slope > SLOPE_SOFT_THRESHOLD) return SLOPE_SOFT_PENALTY;
        return 0;
    }

    public double gradeCost(double elevation, double horizontalDist) {
        if (horizontalDist < 0.001) return 0;
        double grade = Math.abs(elevation) / horizontalDist;
        if (grade > HARD_GRADE_LIMIT) return HARD_GRADE_PENALTY;
        if (grade > SOFT_GRADE_LIMIT) return SOFT_GRADE_PENALTY;
        return 0;
    }

    public double heuristic(int x, int z, int goalX, int goalZ) {
        int dx = Math.abs(x - goalX);
        int dz = Math.abs(z - goalZ);
        return config.getHeuristicWeight() * (dx + dz - 0.6 * Math.min(dx, dz));
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

    public PathfindingConfig getConfig() { return config; }
}
