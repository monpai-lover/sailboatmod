package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.nation.model.NationDiplomacyStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;

import java.util.*;

public final class AutoRouteService {

    public static boolean canCreateAutoRoute(Level level, DockBlockEntity startDock, DockBlockEntity endDock) {
        if (level == null || startDock == null || endDock == null) return false;

        String startNationId = startDock.getNationId();
        String endNationId = endDock.getNationId();

        if (startNationId.equals(endNationId) && !startNationId.isBlank()) return true;
        if (startNationId.isBlank() || endNationId.isBlank()) return false;

        NationSavedData data = NationSavedData.get(level);
        NationDiplomacyRecord relation = data.getDiplomacy(startNationId, endNationId);

        if (relation == null) return false;
        return relation.statusId().equals(NationDiplomacyStatus.ALLIED.id())
            || relation.statusId().equals(NationDiplomacyStatus.TRADE.id());
    }

    public static List<BlockPos> findWaterRoute(ServerLevel level, BlockPos start, BlockPos end) {
        if (level == null || start == null || end == null) return List.of();

        PriorityQueue<RouteNode> openSet = new PriorityQueue<>();
        Map<Long, RouteNode> allNodes = new HashMap<>();
        Set<Long> closedSet = new HashSet<>();

        RouteNode startNode = new RouteNode(start.getX(), start.getZ());
        startNode.gCost = 0;
        startNode.hCost = heuristic(start.getX(), start.getZ(), end.getX(), end.getZ());
        startNode.fCost = startNode.hCost;

        openSet.add(startNode);
        allNodes.put(posToLong(start.getX(), start.getZ()), startNode);

        int maxSteps = 1000;
        int steps = 0;

        while (!openSet.isEmpty() && steps < maxSteps) {
            steps++;
            RouteNode current = openSet.poll();
            long currentKey = posToLong(current.x, current.z);

            if (Math.abs(current.x - end.getX()) <= 16 && Math.abs(current.z - end.getZ()) <= 16) {
                return reconstructPath(current);
            }

            closedSet.add(currentKey);

            for (RouteNode neighbor : getNeighbors(level, current, end)) {
                long neighborKey = posToLong(neighbor.x, neighbor.z);
                if (closedSet.contains(neighborKey)) continue;

                double tentativeG = current.gCost + neighbor.moveCost;

                RouteNode existing = allNodes.get(neighborKey);
                if (existing == null) {
                    neighbor.gCost = tentativeG;
                    neighbor.hCost = heuristic(neighbor.x, neighbor.z, end.getX(), end.getZ());
                    neighbor.fCost = neighbor.gCost + neighbor.hCost;
                    neighbor.parent = current;
                    openSet.add(neighbor);
                    allNodes.put(neighborKey, neighbor);
                } else if (tentativeG < existing.gCost) {
                    openSet.remove(existing);
                    existing.gCost = tentativeG;
                    existing.fCost = existing.gCost + existing.hCost;
                    existing.parent = current;
                    openSet.add(existing);
                }
            }
        }

        return List.of();
    }

    private static List<RouteNode> getNeighbors(ServerLevel level, RouteNode node, BlockPos goal) {
        List<RouteNode> neighbors = new ArrayList<>();
        int[] dx = {0, 16, 0, -16, 16, 16, -16, -16};
        int[] dz = {16, 0, -16, 0, 16, -16, 16, -16};

        for (int i = 0; i < dx.length; i++) {
            int nx = node.x + dx[i];
            int nz = node.z + dz[i];
            BlockPos checkPos = new BlockPos(nx, 63, nz);
            if (!level.hasChunkAt(checkPos)) continue;

            double cost = 1.0;
            boolean isWater = false;
            for (int y = 50; y < 80; y++) {
                FluidState fluid = level.getFluidState(new BlockPos(nx, y, nz));
                if (!fluid.isEmpty()) {
                    isWater = true;
                    break;
                }
            }
            if (!isWater) cost += 50.0;

            RouteNode neighbor = new RouteNode(nx, nz);
            neighbor.moveCost = cost;
            neighbors.add(neighbor);
        }
        return neighbors;
    }

    private static double heuristic(int x1, int z1, int x2, int z2) {
        return Math.sqrt((x2 - x1) * (x2 - x1) + (z2 - z1) * (z2 - z1));
    }

    private static List<BlockPos> reconstructPath(RouteNode endNode) {
        List<BlockPos> path = new ArrayList<>();
        RouteNode current = endNode;
        while (current != null) {
            path.add(new BlockPos(current.x, 63, current.z));
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private static long posToLong(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static boolean createAndSaveAutoRoute(ServerLevel level, DockBlockEntity startDock, DockBlockEntity endDock) {
        if (!canCreateAutoRoute(level, startDock, endDock)) return false;

        BlockPos startPos = startDock.getBlockPos();
        BlockPos endPos = endDock.getBlockPos();

        List<BlockPos> path = findWaterRoute(level, startPos, endPos);
        if (path.isEmpty()) return false;

        List<net.minecraft.world.phys.Vec3> waypoints = new java.util.ArrayList<>();
        for (BlockPos pos : path) {
            waypoints.add(new net.minecraft.world.phys.Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5));
        }

        double routeLength = 0;
        for (int i = 1; i < waypoints.size(); i++) {
            routeLength += waypoints.get(i - 1).distanceTo(waypoints.get(i));
        }

        String routeName = "Auto: " + endDock.getDockName();
        RouteDefinition route = new RouteDefinition(
            routeName,
            waypoints,
            "System",
            "",
            System.currentTimeMillis(),
            routeLength,
            startDock.getDockName(),
            endDock.getDockName()
        );

        java.util.List<RouteDefinition> routes = new java.util.ArrayList<>(startDock.getRoutesForMap());
        routes.add(route);
        startDock.setRoutes(routes, routes.size() - 1);
        return true;
    }

    private static class RouteNode implements Comparable<RouteNode> {
        int x, z;
        double gCost, hCost, fCost, moveCost;
        RouteNode parent;

        RouteNode(int x, int z) {
            this.x = x;
            this.z = z;
            this.moveCost = 1.0;
        }

        @Override
        public int compareTo(RouteNode other) {
            return Double.compare(this.fCost, other.fCost);
        }
    }
}
