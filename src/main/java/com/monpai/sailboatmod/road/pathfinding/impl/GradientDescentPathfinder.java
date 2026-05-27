package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.cost.TerrainCostModel;
import net.minecraft.core.BlockPos;

import java.util.*;

public class GradientDescentPathfinder implements Pathfinder {

    private static final int[][] DIRECTIONS = {
        { 0, -1}, { 0,  1}, {-1,  0}, { 1,  0},
        {-1, -1}, {-1,  1}, { 1, -1}, { 1,  1}
    };

    private final PathfindingConfig config;
    private final TerrainCostModel costModel;

    public GradientDescentPathfinder(PathfindingConfig config) {
        this.config = config;
        this.costModel = new TerrainCostModel(config);
    }

    private record Node(int x, int z, double g, double f, Node parent) {}

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @Override
    public PathResult findPath(BlockPos start, BlockPos end, TerrainSamplingCache cache) {
        int step = config.getAStarStep();
        int maxSteps = Math.max(5000, config.getMaxSteps() * 3);
        int goalX = end.getX();
        int goalZ = end.getZ();

        double h0 = costModel.heuristic(start.getX(), start.getZ(), goalX, goalZ);
        Node startNode = new Node(start.getX(), start.getZ(), 0.0, h0, null);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        HashMap<Long, Double> bestG = new HashMap<>();

        open.add(startNode);
        bestG.put(key(startNode.x, startNode.z), 0.0);

        int steps = 0;
        while (!open.isEmpty() && steps < maxSteps) {
            steps++;
            Node current = open.poll();

            // Success check: Manhattan distance <= step * 3
            if (Math.abs(current.x - goalX) + Math.abs(current.z - goalZ) <= step * 3) {
                return PathResult.success(reconstructPath(current, end, cache));
            }

            double recorded = bestG.getOrDefault(key(current.x, current.z), Double.MAX_VALUE);
            if (current.g > recorded) {
                continue;
            }

            for (int[] dir : DIRECTIONS) {
                int nx = current.x + dir[0] * step;
                int nz = current.z + dir[1] * step;

                // Squared elevation cost
                int fromH = cache.getHeight(current.x, current.z);
                int toH = cache.getHeight(nx, nz);
                double heightDiff = toH - fromH;
                double elevCost = (heightDiff * heightDiff) * config.getElevationWeight();

                // Base move cost
                boolean diagonal = (dir[0] != 0) && (dir[1] != 0);
                double stepCost = diagonal ? 1.414 : 1.0;
                double baseCost = stepCost * step;

                // Slope penalties
                double hDist = Math.sqrt((double)(dir[0] * step * dir[0] * step)
                        + (double)(dir[1] * step * dir[1] * step));
                double slope = (hDist < 0.001) ? 0 : Math.abs(heightDiff) / hDist;
                double slopePenalty = 0;
                if (slope > TerrainCostModel.SLOPE_HARD_THRESHOLD) {
                    slopePenalty = TerrainCostModel.SLOPE_HARD_PENALTY;
                } else if (slope > TerrainCostModel.SLOPE_SOFT_THRESHOLD) {
                    slopePenalty = TerrainCostModel.SLOPE_SOFT_PENALTY;
                }

                // Water cost (squared depth via costModel)
                double waterCost = costModel.waterCostAggressive(cache, nx, nz);
                // Near water penalty
                double nearWaterCost = cache.isNearWater(nx, nz) ? config.getNearWaterCost() * 4.0 : 0;

                // Biome and stability costs from costModel
                double biomeCost = cache.isWaterBiome(nx, nz) ? config.getBiomeWeight() * 12.0 : 0;
                double stabilityCost = config.getStabilityWeight() * cache.terrainStability(nx, nz);

                // Deviation cost
                double devCost = costModel.deviationCost(nx, nz, start, end);

                double ng = current.g + baseCost + elevCost + slopePenalty
                        + waterCost + nearWaterCost + biomeCost + stabilityCost + devCost;

                long nKey = key(nx, nz);
                double prevBest = bestG.getOrDefault(nKey, Double.MAX_VALUE);
                if (ng < prevBest) {
                    bestG.put(nKey, ng);
                    double nh = costModel.heuristic(nx, nz, goalX, goalZ);
                    open.add(new Node(nx, nz, ng, ng + nh, current));
                }
            }
        }

        return PathResult.failure("GradientDescent A* exhausted after " + steps + " steps without reaching goal");
    }

    private List<BlockPos> reconstructPath(Node goalNode, BlockPos end, TerrainSamplingCache cache) {
        List<BlockPos> path = new ArrayList<>();
        Node n = goalNode;
        while (n != null) {
            int y = cache.getHeight(n.x, n.z);
            path.add(new BlockPos(n.x, y, n.z));
            n = n.parent;
        }
        Collections.reverse(path);
        BlockPos last = path.get(path.size() - 1);
        if (last.getX() != end.getX() || last.getZ() != end.getZ()) {
            int ey = cache.getHeight(end.getX(), end.getZ());
            path.add(new BlockPos(end.getX(), ey, end.getZ()));
        }
        return path;
    }
}
