package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.cost.TerrainCostModel;
import net.minecraft.core.BlockPos;

import java.util.*;

public class BidirectionalAStarPathfinder implements Pathfinder {

    private static final int[][] DIRECTIONS = {
        { 0, -1}, { 0,  1}, {-1,  0}, { 1,  0},
        {-1, -1}, {-1,  1}, { 1, -1}, { 1,  1}
    };

    private static final double HEURISTIC_EPSILON = 0.2;

    private final PathfindingConfig config;
    private final TerrainCostModel costModel;

    public BidirectionalAStarPathfinder(PathfindingConfig config) {
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
        int maxSteps = config.getMaxSteps() * 2;
        int sx = start.getX(), sz = start.getZ();
        int ex = end.getX(), ez = end.getZ();

        // Forward search: start -> end
        double fwdH = costModel.heuristic(sx, sz, ex, ez);
        Node fwdStart = new Node(sx, sz, 0.0, fwdH, null);
        PriorityQueue<Node> forwardOpen = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        HashMap<Long, Double> forwardBestG = new HashMap<>();
        HashMap<Long, Node> forwardNodes = new HashMap<>();
        forwardOpen.add(fwdStart);
        long startKey = key(sx, sz);
        forwardBestG.put(startKey, 0.0);
        forwardNodes.put(startKey, fwdStart);

        // Backward search: end -> start
        double bwdH = costModel.heuristic(ex, ez, sx, sz) * (1.0 + HEURISTIC_EPSILON);
        Node bwdStart = new Node(ex, ez, 0.0, bwdH, null);
        PriorityQueue<Node> backwardOpen = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        HashMap<Long, Double> backwardBestG = new HashMap<>();
        HashMap<Long, Node> backwardNodes = new HashMap<>();
        backwardOpen.add(bwdStart);
        long endKey = key(ex, ez);
        backwardBestG.put(endKey, 0.0);
        backwardNodes.put(endKey, bwdStart);

        int steps = 0;
        while (!forwardOpen.isEmpty() && !backwardOpen.isEmpty() && steps < maxSteps) {
            steps++;

            // Expand whichever queue has the lower minimum f
            boolean expandForward;
            if (forwardOpen.isEmpty()) {
                expandForward = false;
            } else if (backwardOpen.isEmpty()) {
                expandForward = true;
            } else {
                expandForward = forwardOpen.peek().f <= backwardOpen.peek().f;
            }

            if (expandForward) {
                Node current = forwardOpen.poll();
                double recorded = forwardBestG.getOrDefault(key(current.x, current.z), Double.MAX_VALUE);
                if (current.g > recorded) continue;

                // Success check: Manhattan distance <= step * 3
                if (Math.abs(current.x - ex) + Math.abs(current.z - ez) <= step * 3) {
                    return PathResult.success(reconstructForwardPath(current, end, cache));
                }

                for (int[] dir : DIRECTIONS) {
                    int nx = current.x + dir[0] * step;
                    int nz = current.z + dir[1] * step;

                    double moveCost = costModel.moveCost(current.x, current.z, nx, nz, cache) * step;
                    double devCost = costModel.deviationCost(nx, nz, start, end);
                    double ng = current.g + moveCost + devCost;

                    long nKey = key(nx, nz);
                    double prevBest = forwardBestG.getOrDefault(nKey, Double.MAX_VALUE);
                    if (ng < prevBest) {
                        forwardBestG.put(nKey, ng);
                        double nh = costModel.heuristic(nx, nz, ex, ez);
                        Node newNode = new Node(nx, nz, ng, ng + nh, current);
                        forwardOpen.add(newNode);
                        forwardNodes.put(nKey, newNode);

                        // Meeting detection
                        if (backwardBestG.containsKey(nKey)) {
                            return buildMergedPath(newNode, backwardNodes.get(nKey), cache, start, end);
                        }
                    }
                }
            } else {
                Node current = backwardOpen.poll();
                double recorded = backwardBestG.getOrDefault(key(current.x, current.z), Double.MAX_VALUE);
                if (current.g > recorded) continue;

                // Success check: Manhattan distance <= step * 3
                if (Math.abs(current.x - sx) + Math.abs(current.z - sz) <= step * 3) {
                    return PathResult.success(reconstructBackwardPath(current, start, end, cache));
                }

                for (int[] dir : DIRECTIONS) {
                    int nx = current.x + dir[0] * step;
                    int nz = current.z + dir[1] * step;

                    double moveCost = costModel.moveCost(current.x, current.z, nx, nz, cache) * step;
                    double devCost = costModel.deviationCost(nx, nz, start, end);
                    double ng = current.g + moveCost + devCost;

                    long nKey = key(nx, nz);
                    double prevBest = backwardBestG.getOrDefault(nKey, Double.MAX_VALUE);
                    if (ng < prevBest) {
                        backwardBestG.put(nKey, ng);
                        // Bias backward heuristic with epsilon
                        double nh = costModel.heuristic(nx, nz, sx, sz) * (1.0 + HEURISTIC_EPSILON);
                        Node newNode = new Node(nx, nz, ng, ng + nh, current);
                        backwardOpen.add(newNode);
                        backwardNodes.put(nKey, newNode);

                        // Meeting detection
                        if (forwardBestG.containsKey(nKey)) {
                            return buildMergedPath(forwardNodes.get(nKey), newNode, cache, start, end);
                        }
                    }
                }
            }
        }

        return PathResult.failure("Bidirectional A* exhausted after " + steps + " steps without meeting");
    }

    private PathResult buildMergedPath(Node forwardNode, Node backwardNode,
                                       TerrainSamplingCache cache,
                                       BlockPos start, BlockPos end) {
        // Forward chain: start -> meeting point
        List<BlockPos> forwardChain = new ArrayList<>();
        Node n = forwardNode;
        while (n != null) {
            int y = cache.getHeight(n.x, n.z);
            forwardChain.add(new BlockPos(n.x, y, n.z));
            n = n.parent;
        }
        Collections.reverse(forwardChain);

        // Backward chain: meeting point -> end (skip the meeting node itself to avoid duplicate)
        List<BlockPos> backwardChain = new ArrayList<>();
        n = backwardNode.parent; // skip meeting point, already in forward chain
        while (n != null) {
            int y = cache.getHeight(n.x, n.z);
            backwardChain.add(new BlockPos(n.x, y, n.z));
            n = n.parent;
        }
        // backwardChain is currently meeting->end order already (parent chain goes toward end-start)
        // Actually the backward search started at 'end', so parent chain goes toward 'end'.
        // No reversal needed: the parent chain of the backward search traces back to 'end'.

        List<BlockPos> fullPath = new ArrayList<>(forwardChain);
        fullPath.addAll(backwardChain);

        // Ensure exact end position is present
        if (!fullPath.isEmpty()) {
            BlockPos last = fullPath.get(fullPath.size() - 1);
            if (last.getX() != end.getX() || last.getZ() != end.getZ()) {
                int ey = cache.getHeight(end.getX(), end.getZ());
                fullPath.add(new BlockPos(end.getX(), ey, end.getZ()));
            }
        }

        return PathResult.success(fullPath);
    }

    private List<BlockPos> reconstructForwardPath(Node goalNode, BlockPos end, TerrainSamplingCache cache) {
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

    private List<BlockPos> reconstructBackwardPath(Node goalNode, BlockPos start, BlockPos end, TerrainSamplingCache cache) {
        // The backward search traces from near-start back to end via parent chain
        List<BlockPos> path = new ArrayList<>();
        Node n = goalNode;
        while (n != null) {
            int y = cache.getHeight(n.x, n.z);
            path.add(new BlockPos(n.x, y, n.z));
            n = n.parent;
        }
        // path is now: [near-start, ..., end] — reverse to get [end, ..., near-start] then reverse again for [start->end]
        // Actually parent chain goes toward 'end' (backward search started at end), so path = [near-start, ..., end]
        // We need start->end order, so no reversal needed, but we need to prepend start
        // Wait: the backward search parent chain goes: goalNode -> ... -> bwdStart(end). So path = [near-start, ..., end].
        // That's already in start-to-end order. Just ensure start and end are present.
        BlockPos first = path.get(0);
        if (first.getX() != start.getX() || first.getZ() != start.getZ()) {
            int sy = cache.getHeight(start.getX(), start.getZ());
            path.add(0, new BlockPos(start.getX(), sy, start.getZ()));
        }
        BlockPos last = path.get(path.size() - 1);
        if (last.getX() != end.getX() || last.getZ() != end.getZ()) {
            int ey = cache.getHeight(end.getX(), end.getZ());
            path.add(new BlockPos(end.getX(), ey, end.getZ()));
        }
        return path;
    }
}
