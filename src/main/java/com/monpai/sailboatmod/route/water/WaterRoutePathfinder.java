package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class WaterRoutePathfinder {
    private final WaterRouteWorld world;
    private final BlockPos start;
    private final BlockPos goal;
    private final WaterRoutePolicy policy;
    private final PriorityQueue<Node> open = new PriorityQueue<>();
    private final Map<Long, Node> bestNodes = new HashMap<>();
    private final Set<Long> closed = new HashSet<>();
    private final List<BlockPos> path = new ArrayList<>();
    private Status status = Status.RUNNING;
    private WaterRouteFailureReason failureReason = WaterRouteFailureReason.NONE;
    private int expandedNodes;

    public WaterRoutePathfinder(WaterRouteWorld world, BlockPos start, BlockPos goal, WaterRoutePolicy policy) {
        this.world = world;
        this.start = start;
        this.goal = goal;
        this.policy = policy == null ? WaterRoutePolicy.defaults() : policy;
        seed();
    }

    private void seed() {
        if (world == null || start == null || goal == null) {
            fail(WaterRouteFailureReason.NO_WATER_PATH);
            return;
        }
        WaterColumn startColumn = world.sample(start.getX(), start.getZ(), policy);
        if (startColumn == null || !startColumn.passable()) {
            fail(WaterRouteFailureReason.NO_WATER_PATH);
            return;
        }
        Node startNode = new Node(startColumn.surfacePos(), 0.0D, heuristic(startColumn.surfacePos()), null);
        open.add(startNode);
        bestNodes.put(key(startNode.pos), startNode);
    }

    public Status step(int maxNodeExpansions, int maxChunkLoads) {
        if (status != Status.RUNNING) {
            return status;
        }
        int expandedThisStep = 0;
        int nodeBudget = Math.max(1, maxNodeExpansions);
        int chunkBudget = Math.max(0, maxChunkLoads);
        while (!open.isEmpty() && expandedThisStep < nodeBudget) {
            if (expandedNodes >= policy.maxExpandedNodes()) {
                fail(WaterRouteFailureReason.NODE_BUDGET_EXCEEDED);
                return status;
            }
            if (world.consumedChunkLoads() > policy.maxChunkLoads() || !world.canLoadMoreChunks(chunkBudget)) {
                fail(WaterRouteFailureReason.CHUNK_BUDGET_EXCEEDED);
                return status;
            }

            Node current = open.poll();
            long currentKey = key(current.pos);
            if (!closed.add(currentKey)) {
                continue;
            }
            expandedNodes++;
            expandedThisStep++;

            if (isGoal(current.pos)) {
                complete(current);
                return status;
            }

            expand(current);
        }
        if (open.isEmpty()) {
            fail(WaterRouteFailureReason.NO_WATER_PATH);
        }
        return status;
    }

    private void expand(Node current) {
        int step = Math.max(1, policy.stepSize());
        int[] dx = {0, step, 0, -step, step, step, -step, -step};
        int[] dz = {step, 0, -step, 0, step, -step, step, -step};
        for (int i = 0; i < dx.length; i++) {
            int nx = current.pos.getX() + dx[i];
            int nz = current.pos.getZ() + dz[i];
            if (!withinSearchRadius(nx, nz)) {
                continue;
            }
            WaterColumn column = world.sample(nx, nz, policy);
            if (column == null || !column.passable() || column.surfacePos() == null) {
                continue;
            }
            if (dx[i] != 0 && dz[i] != 0 && !diagonalCorridorPassable(current.pos, nx, nz)) {
                continue;
            }
            double cost = current.gCost + current.pos.distSqr(column.surfacePos()) + column.extraCost();
            long key = key(column.surfacePos());
            Node previous = bestNodes.get(key);
            if (previous != null && previous.gCost <= cost) {
                continue;
            }
            Node next = new Node(column.surfacePos(), cost, heuristic(column.surfacePos()), current);
            bestNodes.put(key, next);
            open.add(next);
        }
    }

    private boolean diagonalCorridorPassable(BlockPos current, int nx, int nz) {
        WaterColumn left = world.sample(nx, current.getZ(), policy);
        WaterColumn right = world.sample(current.getX(), nz, policy);
        return left != null && left.passable() && right != null && right.passable();
    }

    private boolean withinSearchRadius(int x, int z) {
        long dx = x - start.getX();
        long dz = z - start.getZ();
        long radius = Math.max(1, policy.maxSearchRadius());
        return dx * dx + dz * dz <= radius * radius;
    }

    private boolean isGoal(BlockPos pos) {
        return Math.abs(pos.getX() - goal.getX()) <= policy.stepSize()
                && Math.abs(pos.getZ() - goal.getZ()) <= policy.stepSize();
    }

    private double heuristic(BlockPos pos) {
        long dx = goal.getX() - pos.getX();
        long dz = goal.getZ() - pos.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void complete(Node end) {
        path.clear();
        Node current = end;
        while (current != null) {
            path.add(current.pos);
            current = current.parent;
        }
        Collections.reverse(path);
        if (!path.get(path.size() - 1).equals(goal)) {
            WaterColumn goalColumn = world.sample(goal.getX(), goal.getZ(), policy);
            if (goalColumn != null && goalColumn.passable() && goalColumn.surfacePos() != null) {
                path.add(goalColumn.surfacePos());
            }
        }
        List<BlockPos> simplified = simplify(path);
        path.clear();
        path.addAll(simplified);
        status = Status.SUCCESS;
    }

    private static List<BlockPos> simplify(List<BlockPos> input) {
        if (input.size() <= 2) {
            return List.copyOf(input);
        }
        List<BlockPos> out = new ArrayList<>();
        out.add(input.get(0));
        for (int i = 1; i < input.size() - 1; i++) {
            BlockPos previous = out.get(out.size() - 1);
            BlockPos current = input.get(i);
            BlockPos next = input.get(i + 1);
            int dx1 = Integer.compare(current.getX() - previous.getX(), 0);
            int dz1 = Integer.compare(current.getZ() - previous.getZ(), 0);
            int dx2 = Integer.compare(next.getX() - current.getX(), 0);
            int dz2 = Integer.compare(next.getZ() - current.getZ(), 0);
            if (dx1 != dx2 || dz1 != dz2) {
                out.add(current);
            }
        }
        out.add(input.get(input.size() - 1));
        return out;
    }

    private void fail(WaterRouteFailureReason reason) {
        status = Status.FAILED;
        failureReason = reason == null ? WaterRouteFailureReason.NO_WATER_PATH : reason;
    }

    public List<BlockPos> path() {
        return List.copyOf(path);
    }

    public WaterRouteFailureReason failureReason() {
        return failureReason;
    }

    public int expandedNodes() {
        return expandedNodes;
    }

    private static long key(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
    }

    public enum Status {
        RUNNING,
        SUCCESS,
        FAILED
    }

    private static final class Node implements Comparable<Node> {
        private final BlockPos pos;
        private final double gCost;
        private final double hCost;
        private final Node parent;

        private Node(BlockPos pos, double gCost, double hCost, Node parent) {
            this.pos = pos;
            this.gCost = gCost;
            this.hCost = hCost;
            this.parent = parent;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(gCost + hCost, other.gCost + other.hCost);
        }
    }
}
