package com.monpai.sailboatmod.resident.pathfinding;

import com.monpai.sailboatmod.resident.pathfinding.goal.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Enhanced A* pathfinder inspired by Maple
 */
public class MaplePathfinder {
    private static final int MAX_NODES = 5000;
    private static final long DEFAULT_TIMEOUT = 500; // ms

    public static CompletableFuture<List<BlockPos>> findPathAsync(Level level, BlockPos start, Goal goal) {
        return CompletableFuture.supplyAsync(() -> findPath(level, start, goal, MAX_NODES, DEFAULT_TIMEOUT));
    }

    public static List<BlockPos> findPath(Level level, BlockPos start, Goal goal, int maxNodes, long timeout) {
        PriorityQueue<PathNode> openSet = new PriorityQueue<>();
        Set<BlockPos> closedSet = new HashSet<>();
        Map<BlockPos, PathNode> allNodes = new HashMap<>();

        PathNode startNode = new PathNode(start, 0, goal.heuristic(start), null);
        openSet.add(startNode);
        allNodes.put(start, startNode);

        long startTime = System.currentTimeMillis();
        int explored = 0;

        while (!openSet.isEmpty() && explored < maxNodes) {
            if (System.currentTimeMillis() - startTime > timeout) break;

            PathNode current = openSet.poll();
            explored++;

            if (goal.isGoal(current.pos)) {
                return buildPath(current);
            }

            closedSet.add(current.pos);

            for (BlockPos neighbor : getNeighbors(level, current.pos)) {
                if (closedSet.contains(neighbor)) continue;

                double newG = current.g + cost(current.pos, neighbor);
                PathNode neighborNode = allNodes.get(neighbor);

                if (neighborNode == null) {
                    neighborNode = new PathNode(neighbor, newG, goal.heuristic(neighbor), current);
                    allNodes.put(neighbor, neighborNode);
                    openSet.add(neighborNode);
                } else if (newG < neighborNode.g) {
                    openSet.remove(neighborNode);
                    neighborNode.g = newG;
                    neighborNode.f = newG + neighborNode.h;
                    neighborNode.parent = current;
                    openSet.add(neighborNode);
                }
            }
        }

        return Collections.emptyList();
    }

    private static List<BlockPos> getNeighbors(Level level, BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();

        // 8 directions + up/down
        int[][] dirs = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{1,0,1},{1,0,-1},{-1,0,1},{-1,0,-1}};

        for (int[] d : dirs) {
            BlockPos n = pos.offset(d[0], d[1], d[2]);
            if (isWalkable(level, n)) neighbors.add(n);

            // Try climbing up
            BlockPos up = n.above();
            if (isWalkable(level, up)) neighbors.add(up);
        }

        // Try falling down
        BlockPos down = pos.below();
        if (isWalkable(level, down)) neighbors.add(down);

        return neighbors;
    }

    private static boolean isWalkable(Level level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        BlockState at = level.getBlockState(pos);
        BlockState above = level.getBlockState(pos.above());
        return !below.isAir() && at.isAir() && above.isAir();
    }

    private static double cost(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dy = Math.abs(to.getY() - from.getY());
        int dz = Math.abs(to.getZ() - from.getZ());

        if (dx + dz == 2) return 1.414; // diagonal
        if (dy > 0) return 1.5; // climbing
        return 1.0;
    }

    private static List<BlockPos> buildPath(PathNode goal) {
        List<BlockPos> path = new ArrayList<>();
        PathNode current = goal;
        while (current != null) {
            path.add(current.pos);
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }
}
