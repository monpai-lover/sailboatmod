package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.cost.TerrainCostModel;
import com.monpai.sailboatmod.road.pathfinding.cost.TerrainGradientHelper;
import net.minecraft.core.BlockPos;

import java.util.*;

public class PotentialFieldPathfinder implements Pathfinder {

    private static final int[][] DIRECTIONS = {
        { 0, -1}, { 0,  1}, {-1,  0}, { 1,  0},
        {-1, -1}, {-1,  1}, { 1, -1}, { 1,  1}
    };

    private final PathfindingConfig config;
    private final TerrainCostModel costModel;

    public PotentialFieldPathfinder(PathfindingConfig config) {
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
        int maxSteps = Math.max(5000, config.getMaxSteps() * 4);
        int goalX = end.getX();
        int goalZ = end.getZ();
        int startX = start.getX();
        int startZ = start.getZ();
        // Search buffer: limit search to within min(1024, manhattan/3) of the start-end line
        int manhattan = Math.abs(goalX - startX) + Math.abs(goalZ - startZ);
        int searchBuffer = Math.min(1024, manhattan / 3);

        // Goal direction for contour computation
        double goalDirX = goalX - startX;
        double goalDirZ = goalZ - startZ;
        double goalLen = Math.sqrt(goalDirX * goalDirX + goalDirZ * goalDirZ);
        if (goalLen > 1e-6) {
            goalDirX /= goalLen;
            goalDirZ /= goalLen;
        }

        double h0 = costModel.heuristic(startX, startZ, goalX, goalZ);
        Node startNode = new Node(startX, startZ, 0.0, h0, null);

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
            // Terrain gradient at current position
            double[] grad = TerrainGradientHelper.terrainGradient(current.x, current.z, cache);
            double gradX = grad[0];
            double gradZ = grad[1];
            // Contour direction at current position
            double[] contour = TerrainGradientHelper.contourDirection(gradX, gradZ, goalDirX, goalDirZ);
            double contourX = contour[0];
            double contourZ = contour[1];

            for (int[] dir : DIRECTIONS) {
                int nx = current.x + dir[0] * step;
                int nz = current.z + dir[1] * step;

                // Search buffer check: perpendicular distance from start-end line
                double perpDist = perpendicularDistance(nx, nz, startX, startZ, goalX, goalZ, goalLen);
                if (perpDist > searchBuffer) {
                    continue;
                }

                // Squared elevation cost (halved weight for potential field)
                int fromH = cache.getHeight(current.x, current.z);
                int toH = cache.getHeight(nx, nz);
                double heightDiff = toH - fromH;
                double elevCost = (heightDiff * heightDiff) * config.getElevationWeight() * 0.5;

                // Base move cost
                boolean diagonal = (dir[0] != 0) && (dir[1] != 0);
                double stepCost = diagonal ? 1.414 : 1.0;
                double baseCost = stepCost * step;

                // Grade penalties
                double hDist = Math.sqrt((double)(dir[0] * step * dir[0] * step)
                        + (double)(dir[1] * step * dir[1] * step));
                double grade = TerrainGradientHelper.computeGrade(heightDiff, hDist);
                double gradePenalty = 0;
                if (grade > TerrainCostModel.HARD_GRADE_LIMIT) {
                    gradePenalty = TerrainCostModel.HARD_GRADE_PENALTY;
                } else if (grade > TerrainCostModel.SOFT_GRADE_LIMIT) {
                    gradePenalty = TerrainCostModel.SOFT_GRADE_PENALTY;
                }
                // Gradient alignment penalty: penalize moving uphill along gradient
                double moveX = dir[0] * step;
                double moveZ = dir[1] * step;
                double gradAlignPenalty = 0;
                double gradMag = TerrainGradientHelper.gradientMagnitude(gradX, gradZ);
                if (gradMag > 1e-6 && heightDiff > 0) {
                    double dotGrad = (moveX * gradX + moveZ * gradZ);
                    if (dotGrad > 0) {
                        gradAlignPenalty = TerrainCostModel.GRADIENT_ALIGN_PENALTY;
                    }
                }

                // Contour discount: reduce cost when moving along contour lines
                double contourDiscount = 0;
                double alignment = TerrainGradientHelper.contourAlignment(moveX, moveZ, contourX, contourZ);
                if (alignment > 0) {
                    contourDiscount = alignment * TerrainCostModel.CONTOUR_DISCOUNT * baseCost;
                }

                // Biome and stability costs
                double biomeCost = cache.isWaterBiome(nx, nz) ? config.getBiomeWeight() * 12.0 : 0;
                double stabilityCost = config.getStabilityWeight() * cache.terrainStability(nx, nz);

                // Water cost
                double waterCost = costModel.waterCostAggressive(cache, nx, nz);
                // Near water penalty
                double nearWaterCost = cache.isNearWater(nx, nz) ? config.getNearWaterCost() * 4.0 : 0;

                // Deviation cost
                double devCost = costModel.deviationCost(nx, nz, start, end);

                double totalCost = baseCost + elevCost + gradePenalty + gradAlignPenalty
                        - contourDiscount + biomeCost + stabilityCost + waterCost + nearWaterCost + devCost;
                double ng = current.g + totalCost;

                long nKey = key(nx, nz);
                double prevBest = bestG.getOrDefault(nKey, Double.MAX_VALUE);
                if (ng < prevBest) {
                    bestG.put(nKey, ng);
                    double nh = costModel.heuristic(nx, nz, goalX, goalZ);
                    open.add(new Node(nx, nz, ng, ng + nh, current));
                }
            }
        }

        return PathResult.failure("PotentialField A* exhausted after " + steps + " steps without reaching goal");
    }

    private double perpendicularDistance(int x, int z, int sx, int sz, int ex, int ez, double lineLen) {
        if (lineLen < 1e-6) return 0;
        double dx = x - sx;
        double dz = z - sz;
        double lineX = ex - sx;
        double lineZ = ez - sz;
        return Math.abs(dx * lineZ - dz * lineX) / lineLen;
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
