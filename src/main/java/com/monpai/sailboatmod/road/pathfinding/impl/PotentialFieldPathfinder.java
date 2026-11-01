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
        RoadSearchBudget.check();
        int step = Math.max(1, config.getAStarStep());
        int maxSteps = Math.max(5000, config.getMaxSteps() * 4);
        int goalX = end.getX();
        int goalZ = end.getZ();
        int startX = start.getX();
        int startZ = start.getZ();
        // Search buffer: don't limit search area so island paths can route through water
        int manhattan = Math.abs(goalX - startX) + Math.abs(goalZ - startZ);
        int searchBuffer = Integer.MAX_VALUE;

        // Goal direction for contour computation
        double goalDirX = goalX - startX;
        double goalDirZ = goalZ - startZ;
        double goalLen = Math.sqrt(goalDirX * goalDirX + goalDirZ * goalDirZ);
        if (goalLen > 1e-6) {
            goalDirX /= goalLen;
            goalDirZ /= goalLen;
        }

        if (cache.isBlocked(startX, startZ) || cache.isBlocked(goalX, goalZ))
            return PathResult.failure("Road endpoint is excluded");
        double h0 = costModel.heuristic(startX, startZ, goalX, goalZ);
        Node startNode = new Node(startX, startZ, 0.0, h0, null);

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        HashMap<Long, Double> bestG = new HashMap<>();
        // Weighted heuristic (x15) is inconsistent: without a closed set cheaper g-values keep re-opening
        // already expanded cells and the search churns on a few thousand cells until maxSteps (far towns failed).
        HashSet<Long> closed = new HashSet<>();

        open.add(startNode);
        bestG.put(key(startNode.x, startNode.z), 0.0);

        int steps = 0;
        while (!open.isEmpty() && steps < maxSteps) {
            RoadSearchBudget.check();
            Node current = open.poll();
            // Stale queue entries (a cheaper route to this cell was found later) are skipped without being
            // charged to the expansion budget; counting them used to exhaust maxSteps on re-queued cells.
            double recorded = bestG.getOrDefault(key(current.x, current.z), Double.MAX_VALUE);
            if (current.g > recorded || !closed.add(key(current.x, current.z))) {
                continue;
            }
            steps++;

            // Success check: Manhattan distance <= step * 3
            if (Math.abs(current.x - goalX) + Math.abs(current.z - goalZ) <= step * 3
                    && terminalConnectionAllowed(current.x, current.z, goalX, goalZ, cache)) {
                return PathResult.success(reconstructPath(current, end, cache));
            }
            // Terrain gradient at current position
            double[] grad = TerrainGradientHelper.terrainGradient(current.x, current.z, cache);
            double gradX = grad[0];
            double gradZ = grad[1];
            // Contour direction at current position
            double[] contour = TerrainGradientHelper.contourDirection(gradX, gradZ, goalDirX, goalDirZ);
            double contourX = contour[0];
            double contourZ = contour[1];

            int fromH = cache.getHeight(current.x, current.z);
            for (int[] dir : DIRECTIONS) {
                RoadSearchBudget.check();
                int nx = current.x + dir[0] * step;
                int nz = current.z + dir[1] * step;
                long nKey = key(nx, nz);
                if (closed.contains(nKey) || cache.isBlocked(nx, nz)) continue;

                // Search buffer check: perpendicular distance from start-end line
                double perpDist = perpendicularDistance(nx, nz, startX, startZ, goalX, goalZ, goalLen);
                if (perpDist > searchBuffer) {
                    continue;
                }

                // Squared elevation cost (halved weight for potential field)
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
                double waterCost = edgeWaterCost(current.x, current.z, nx, nz, cache);
                if (!Double.isFinite(waterCost)) continue;
                // Near water penalty
                double nearWaterCost = cache.isNearWater(nx, nz) ? config.getNearWaterCost() * 4.0 : 0;

                // Deviation cost
                double devCost = costModel.deviationCost(nx, nz, start, end);

                double totalCost = baseCost + elevCost + gradePenalty + gradAlignPenalty
                        - contourDiscount + biomeCost + stabilityCost + waterCost + nearWaterCost + devCost;
                double ng = current.g + totalCost;

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

    /** Sample the inside of coarse hops too: two dry endpoints are not a dry edge.
     * Water remains a bridge cost, not an unconditional prohibition. This is a draft planner,
     * not a substitute for the building stage's per-block collision/support validation. */
    private double edgeWaterCost(int ax, int az, int bx, int bz, TerrainSamplingCache cache) {
        int count = Math.max(1, (int)Math.ceil(Math.hypot(bx-(double)ax, bz-(double)az) / 8));
        double penalty = 0;
        for (int i = 1; i <= count; i++) {
            RoadSearchBudget.check();
            int x = (int)Math.round(ax + (bx-(double)ax)*i/count);
            int z = (int)Math.round(az + (bz-(double)az)*i/count);
            if (cache.isBlocked(x, z)) return Double.POSITIVE_INFINITY;
            penalty = Math.max(penalty, costModel.waterCostAggressive(cache, x, z));
        }
        return penalty;
    }
    private boolean terminalConnectionAllowed(int ax, int az, int bx, int bz, TerrainSamplingCache cache) {
        double endpoints = Math.max(costModel.waterCostAggressive(cache, ax, az),
                costModel.waterCostAggressive(cache, bx, bz));
        return edgeWaterCost(ax, az, bx, bz, cache) <= endpoints;
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
            RoadSearchBudget.check();
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
