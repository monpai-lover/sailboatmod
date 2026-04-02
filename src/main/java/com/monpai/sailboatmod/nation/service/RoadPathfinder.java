package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class RoadPathfinder {

    private RoadPathfinder() {}

    public static List<BlockPos> findPath(ServerLevel level, BlockPos from, BlockPos to) {
        PriorityQueue<PathNode> open = new PriorityQueue<>();
        Map<BlockPos, PathNode> allNodes = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();

        BlockPos start = findSurface(level, from.getX(), from.getZ());
        BlockPos end = findSurface(level, to.getX(), to.getZ());
        if (start == null || end == null) return Collections.emptyList();

        PathNode startNode = new PathNode(start, null, 0, heuristic(start, end));
        open.add(startNode);
        allNodes.put(start, startNode);

        while (!open.isEmpty()) {
            PathNode current = open.poll();
            if (current.pos.equals(end)) {
                return reconstructPath(current);
            }

            closed.add(current.pos);

            for (BlockPos neighbor : getNeighbors(level, current.pos)) {
                if (closed.contains(neighbor)) continue;

                int moveCost = getMoveCost(level, current.pos, neighbor);
                int newG = current.gCost + moveCost;

                PathNode neighborNode = allNodes.get(neighbor);
                if (neighborNode == null) {
                    neighborNode = new PathNode(neighbor, current, newG, heuristic(neighbor, end));
                    allNodes.put(neighbor, neighborNode);
                    open.add(neighborNode);
                } else if (newG < neighborNode.gCost) {
                    open.remove(neighborNode);
                    neighborNode.parent = current;
                    neighborNode.gCost = newG;
                    neighborNode.fCost = newG + heuristic(neighbor, end);
                    open.add(neighborNode);
                }
            }
        }

        return Collections.emptyList();
    }

    private static List<BlockPos> getNeighbors(ServerLevel level, BlockPos pos) {
        List<BlockPos> neighbors = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos neighbor = findSurface(level, pos.getX() + dx, pos.getZ() + dz);
                if (neighbor != null) neighbors.add(neighbor);
            }
        }
        return neighbors;
    }

    private static int getMoveCost(ServerLevel level, BlockPos from, BlockPos to) {
        int baseCost = (from.getX() != to.getX() && from.getZ() != to.getZ()) ? 14 : 10;
        int heightDiff = Math.abs(to.getY() - from.getY());
        return baseCost + heightDiff * 5;
    }

    private static int heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(to.getX() - from.getX());
        int dz = Math.abs(to.getZ() - from.getZ());
        return (dx + dz) * 10;
    }

    private static List<BlockPos> reconstructPath(PathNode end) {
        List<BlockPos> path = new ArrayList<>();
        PathNode current = end;
        while (current != null) {
            path.add(current.pos);
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private static BlockPos findSurface(ServerLevel level, int x, int z) {
        BlockPos pos = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).below();
        BlockState state = level.getBlockState(pos);

        if (state.isAir() || state.liquid()) {
            pos = pos.below();
        }

        BlockState below = level.getBlockState(pos);
        return (!below.isAir() && !below.liquid()) ? pos : null;
    }

    private static class PathNode implements Comparable<PathNode> {
        BlockPos pos;
        PathNode parent;
        int gCost;
        int fCost;

        PathNode(BlockPos pos, PathNode parent, int gCost, int hCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.fCost = gCost + hCost;
        }

        @Override
        public int compareTo(PathNode other) {
            return Integer.compare(this.fCost, other.fCost);
        }
    }
}
