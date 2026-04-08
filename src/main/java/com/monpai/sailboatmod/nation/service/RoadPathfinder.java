package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class RoadPathfinder {
    private static final int[][] DIRECTIONS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
    };

    private static final double DISTANCE_COST = 0.55D;
    private static final double HEIGHT_PENALTY = 3.0D;
    private static final double TURN_PENALTY = 0.65D;
    private static final double WATER_PENALTY = 18.0D;
    private static final double NEAR_WATER_PENALTY = 3.5D;
    private static final double CLIFF_PENALTY = 4.5D;
    private static final double ROAD_BONUS = 2.1D;
    private static final double SOFT_GROUND_PENALTY = 1.8D;
    private static final double MAX_STEP_HEIGHT = 5.0D;
    private static final int MAX_VISITED_NODES = 32000;

    private RoadPathfinder() {}

    public static List<BlockPos> findPath(Level level, BlockPos from, BlockPos to) {
        BlockPos start = findSurface(level, from.getX(), from.getZ());
        BlockPos end = findSurface(level, to.getX(), to.getZ());
        if (start == null || end == null) {
            return Collections.emptyList();
        }

        SearchBounds bounds = SearchBounds.around(start, end);
        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(node -> node.fCost));
        Map<Long, PathNode> allNodes = new HashMap<>();

        PathNode startNode = new PathNode(start, null, 0.0D, heuristic(start, end));
        open.add(startNode);
        allNodes.put(start.asLong(), startNode);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_VISITED_NODES) {
            PathNode current = open.poll();
            if (current.closed) {
                continue;
            }
            current.closed = true;

            if (sameColumn(current.pos, end)) {
                return finalizePath(level, reconstructPath(current));
            }

            for (int[] direction : DIRECTIONS) {
                int nextX = current.pos.getX() + direction[0];
                int nextZ = current.pos.getZ() + direction[1];
                if (!bounds.contains(nextX, nextZ)) {
                    continue;
                }

                BlockPos neighbor = findSurface(level, nextX, nextZ);
                if (neighbor == null || Math.abs(neighbor.getY() - current.pos.getY()) > MAX_STEP_HEIGHT) {
                    continue;
                }

                double stepCost = getMoveCost(level, current, neighbor);
                if (Double.isInfinite(stepCost)) {
                    continue;
                }

                double newG = current.gCost + stepCost;
                PathNode neighborNode = allNodes.get(neighbor.asLong());
                if (neighborNode == null) {
                    neighborNode = new PathNode(neighbor, current, newG, heuristic(neighbor, end));
                    allNodes.put(neighbor.asLong(), neighborNode);
                    open.add(neighborNode);
                } else if (!neighborNode.closed && newG < neighborNode.gCost) {
                    neighborNode.parent = current;
                    neighborNode.gCost = newG;
                    neighborNode.fCost = newG + heuristic(neighbor, end);
                    open.add(neighborNode);
                }
            }
        }

        return Collections.emptyList();
    }

    private static double getMoveCost(Level level, PathNode current, BlockPos next) {
        boolean diagonal = current.pos.getX() != next.getX() && current.pos.getZ() != next.getZ();
        double terrainCost = 1.0D + DISTANCE_COST + (diagonal ? 0.45D : 0.0D);

        int heightDiff = Math.abs(next.getY() - current.pos.getY());
        terrainCost += heightDiff * heightDiff * HEIGHT_PENALTY;

        if (crossesWater(level, next)) {
            terrainCost += WATER_PENALTY;
        }
        terrainCost += adjacentWaterCount(level, next) * NEAR_WATER_PENALTY;
        if (isSoftGround(level, next)) {
            terrainCost += SOFT_GROUND_PENALTY;
        }
        if (isRoad(level, next)) {
            terrainCost = Math.max(0.35D, terrainCost - ROAD_BONUS);
        }
        if (heightDiff >= 3) {
            terrainCost += CLIFF_PENALTY * heightDiff;
        }

        if (current.parent != null) {
            int prevDx = Integer.compare(current.pos.getX(), current.parent.pos.getX());
            int prevDz = Integer.compare(current.pos.getZ(), current.parent.pos.getZ());
            int nextDx = Integer.compare(next.getX(), current.pos.getX());
            int nextDz = Integer.compare(next.getZ(), current.pos.getZ());
            if (prevDx != nextDx || prevDz != nextDz) {
                terrainCost += TURN_PENALTY;
            }
        }

        return terrainCost;
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * (double) dx + dz * (double) dz);
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

    private static List<BlockPos> finalizePath(Level level, List<BlockPos> rawPath) {
        if (rawPath.size() <= 2) {
            return rawPath;
        }
        List<BlockPos> simplified = simplifyPath(level, rawPath);
        return smoothPath(level, rasterizeSegments(level, simplified));
    }

    private static List<BlockPos> simplifyPath(Level level, List<BlockPos> rawPath) {
        List<BlockPos> simplified = new ArrayList<>();
        int index = 0;
        simplified.add(rawPath.get(0));
        while (index < rawPath.size() - 1) {
            int furthest = index + 1;
            for (int candidate = rawPath.size() - 1; candidate > index + 1; candidate--) {
                if (hasLineOfTravel(level, rawPath.get(index), rawPath.get(candidate))) {
                    furthest = candidate;
                    break;
                }
            }
            simplified.add(rawPath.get(furthest));
            index = furthest;
        }
        return simplified;
    }

    private static List<BlockPos> rasterizeSegments(Level level, List<BlockPos> points) {
        LinkedHashMap<Long, BlockPos> result = new LinkedHashMap<>();
        BlockPos previous = null;
        for (BlockPos point : points) {
            if (previous == null) {
                result.put(point.asLong(), point);
                previous = point;
                continue;
            }
            for (BlockPos segmentPos : bresenham(previous, point)) {
                BlockPos surface = findNearestSurface(level, segmentPos.getX(), segmentPos.getZ(), previous.getY());
                if (surface != null) {
                    result.put(surface.asLong(), surface);
                    previous = surface;
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    private static boolean hasLineOfTravel(Level level, BlockPos from, BlockPos to) {
        BlockPos previous = from;
        for (BlockPos step : bresenham(from, to)) {
            BlockPos surface = findNearestSurface(level, step.getX(), step.getZ(), previous.getY());
            if (surface == null || Math.abs(surface.getY() - previous.getY()) > MAX_STEP_HEIGHT) {
                return false;
            }
            if (crossesWater(level, surface) && !isRoad(level, surface)) {
                return false;
            }
            previous = surface;
        }
        return true;
    }

    private static List<BlockPos> bresenham(BlockPos from, BlockPos to) {
        List<BlockPos> line = new ArrayList<>();
        int x0 = from.getX();
        int z0 = from.getZ();
        int x1 = to.getX();
        int z1 = to.getZ();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;
        int err = dx - dz;

        while (true) {
            line.add(new BlockPos(x0, 0, z0));
            if (x0 == x1 && z0 == z1) {
                break;
            }
            int e2 = err * 2;
            if (e2 > -dz) {
                err -= dz;
                x0 += sx;
            }
            if (e2 < dx) {
                err += dx;
                z0 += sz;
            }
        }
        return line;
    }

    private static List<BlockPos> smoothPath(Level level, List<BlockPos> path) {
        if (path.size() <= 3) {
            return path;
        }
        List<BlockPos> smoothed = new ArrayList<>(path);
        boolean changed;
        do {
            changed = false;
            for (int i = 1; i < smoothed.size() - 1; i++) {
                BlockPos previous = smoothed.get(i - 1);
                BlockPos current = smoothed.get(i);
                BlockPos next = smoothed.get(i + 1);
                int prevDx = Integer.compare(current.getX(), previous.getX());
                int prevDz = Integer.compare(current.getZ(), previous.getZ());
                int nextDx = Integer.compare(next.getX(), current.getX());
                int nextDz = Integer.compare(next.getZ(), current.getZ());
                boolean zigZag = prevDx == -nextDx && prevDz == -nextDz;
                if (zigZag || hasLineOfTravel(level, previous, next)) {
                    smoothed.remove(i);
                    changed = true;
                    break;
                }
            }
        } while (changed && smoothed.size() > 2);
        return smoothed;
    }

    private static BlockPos findNearestSurface(Level level, int x, int z, int preferredY) {
        BlockPos direct = findSurface(level, x, z);
        if (direct != null && Math.abs(direct.getY() - preferredY) <= MAX_STEP_HEIGHT) {
            return direct;
        }

        BlockPos best = direct;
        int bestDiff = direct == null ? Integer.MAX_VALUE : Math.abs(direct.getY() - preferredY);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos candidate = findSurface(level, x + dx, z + dz);
                if (candidate == null) {
                    continue;
                }
                int diff = Math.abs(candidate.getY() - preferredY);
                if (diff < bestDiff) {
                    best = candidate;
                    bestDiff = diff;
                }
            }
        }
        return bestDiff <= MAX_STEP_HEIGHT + 1 ? best : null;
    }

    private static BlockPos findSurface(Level level, int x, int z) {
        BlockPos pos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).below();
        for (int i = 0; i < 5; i++) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.liquid() && state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP)) {
                return pos;
            }
            pos = pos.below();
        }
        return null;
    }

    private static boolean crossesWater(Level level, BlockPos pos) {
        return !level.getBlockState(pos).getFluidState().isEmpty()
                || !level.getBlockState(pos.above()).getFluidState().isEmpty()
                || !level.getBlockState(pos.below()).getFluidState().isEmpty();
    }

    private static int adjacentWaterCount(Level level, BlockPos pos) {
        int count = 0;
        for (BlockPos nearby : List.of(pos.north(), pos.south(), pos.east(), pos.west())) {
            if (!level.getBlockState(nearby).getFluidState().isEmpty()
                    || !level.getBlockState(nearby.below()).getFluidState().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static boolean isSoftGround(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY);
    }

    private static boolean isRoad(Level level, BlockPos pos) {
        return level.getBlockState(pos.above()).is(Blocks.STONE_BRICK_SLAB);
    }

    private static boolean sameColumn(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    private record SearchBounds(int minX, int maxX, int minZ, int maxZ) {
        static SearchBounds around(BlockPos start, BlockPos end) {
            int margin = Mth.clamp((int) (start.distManhattan(end) * 0.35D), 16, 96);
            return new SearchBounds(
                    Math.min(start.getX(), end.getX()) - margin,
                    Math.max(start.getX(), end.getX()) + margin,
                    Math.min(start.getZ(), end.getZ()) - margin,
                    Math.max(start.getZ(), end.getZ()) + margin
            );
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static final class PathNode {
        private final BlockPos pos;
        private PathNode parent;
        private double gCost;
        private double fCost;
        private boolean closed;

        private PathNode(BlockPos pos, PathNode parent, double gCost, double hCost) {
            this.pos = pos;
            this.parent = parent;
            this.gCost = gCost;
            this.fCost = gCost + hCost;
        }
    }
}
