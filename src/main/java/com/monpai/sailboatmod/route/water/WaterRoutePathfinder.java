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

/**
 * 水路寻路:双向加权 A*(借鉴 RoadWeaver HighwayBidirectionalAStarPathfinder)。
 *
 * <p>关键设计:
 * <ul>
 *   <li>cost 与 heuristic 单位一致(都线性,正交 step、对角 step·√2),不再 distSqr vs sqrt 退化成 Dijkstra。</li>
 *   <li>weighted A*:f = g + (1+ε)·h(ε=0.2),牺牲一点最优换更快收敛。</li>
 *   <li>tie-breaking:偏离起终点连线的垂距小惩罚,收窄搜索带。</li>
 *   <li>双向:前向从起点、后向从终点,交替扩展 f 较小侧,相遇即拼接。</li>
 *   <li>采样走 {@link ServerWaterRouteWorld} 的噪声+列缓存,零区块加载 → 删掉旧的 PAUSED/chunk 预算暂停回滚。</li>
 * </ul>
 */
public final class WaterRoutePathfinder {
    private static final double HEURISTIC_EPSILON = 0.2D;
    private static final double DEVIATION_WEIGHT = 0.001D;

    private final WaterRouteWorld world;
    private final BlockPos start;
    private final BlockPos goal;
    private final WaterRoutePolicy policy;
    private final int step;

    private final Search forward;
    private final Search backward;

    private final List<BlockPos> path = new ArrayList<>();
    private Status status = Status.RUNNING;
    private WaterRouteFailureReason failureReason = WaterRouteFailureReason.NONE;
    private int expandedNodes;

    public WaterRoutePathfinder(WaterRouteWorld world, BlockPos start, BlockPos goal, WaterRoutePolicy policy) {
        this.world = world;
        this.start = start;
        this.goal = goal;
        this.policy = policy == null ? WaterRoutePolicy.defaults() : policy;
        this.step = Math.max(1, this.policy.stepSize());
        this.forward = new Search();
        this.backward = new Search();
        seed();
    }

    private void seed() {
        if (world == null || start == null || goal == null) {
            fail(WaterRouteFailureReason.NO_WATER_PATH);
            return;
        }
        WaterColumn startColumn = world.sample(start.getX(), start.getZ(), policy);
        WaterColumn goalColumn = world.sample(goal.getX(), goal.getZ(), policy);
        if (startColumn == null || !startColumn.passable() || goalColumn == null || !goalColumn.passable()) {
            fail(WaterRouteFailureReason.NO_WATER_PATH);
            return;
        }
        BlockPos s = startColumn.surfacePos();
        BlockPos g = goalColumn.surfacePos();
        Node startNode = new Node(s, 0.0D, heuristic(s, g), null);
        Node goalNode = new Node(g, 0.0D, heuristic(g, s), null);
        forward.open.add(startNode);
        forward.best.put(key(s), startNode);
        backward.open.add(goalNode);
        backward.best.put(key(g), goalNode);
    }

    /** chunkBudget 参数保留以兼容调用方,但噪声采样零区块加载,已忽略。 */
    public Status step(int maxNodeExpansions, int chunkBudget) {
        if (status != Status.RUNNING) {
            return status;
        }
        int budget = Math.max(1, maxNodeExpansions);
        for (int i = 0; i < budget; i++) {
            if (expandedNodes >= policy.maxExpandedNodes()) {
                fail(WaterRouteFailureReason.NODE_BUDGET_EXCEEDED);
                return status;
            }
            if (forward.open.isEmpty() && backward.open.isEmpty()) {
                fail(WaterRouteFailureReason.NO_WATER_PATH);
                return status;
            }
            // 交替扩展 f 较小的一侧(平衡双向前沿)
            double fF = forward.open.isEmpty() ? Double.MAX_VALUE : forward.open.peek().f();
            double fB = backward.open.isEmpty() ? Double.MAX_VALUE : backward.open.peek().f();
            boolean expandForward = fF <= fB;
            if (expandStep(expandForward)) {
                return status; // 相遇 → complete 已设状态
            }
        }
        return status;
    }

    /** 扩展一侧一个节点;返回 true 表示已相遇并完成。 */
    private boolean expandStep(boolean isForward) {
        Search self = isForward ? forward : backward;
        Search other = isForward ? backward : forward;
        BlockPos target = isForward ? goal : start;

        if (self.open.isEmpty()) {
            return false;
        }
        Node current = self.open.poll();
        long curKey = key(current.pos);
        if (!self.closed.add(curKey)) {
            return false;
        }
        expandedNodes++;

        // 相遇检测:当前节点已被对侧确定(closed)→ 拼接
        if (other.best.containsKey(curKey) && other.closed.contains(curKey)) {
            meet(isForward, current, other.best.get(curKey));
            return true;
        }

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
            BlockPos npos = column.surfacePos();
            if (self.closed.contains(key(npos))) {
                continue;
            }
            if (!segmentPassable(current.pos, npos)) {
                continue;
            }
            if (dx[i] != 0 && dz[i] != 0 && !diagonalCorridorPassable(current.pos, nx, nz)) {
                continue;
            }
            double edge = (dx[i] != 0 && dz[i] != 0) ? step * 1.4142135623730951D : step;
            double g = current.gCost + edge + column.extraCost();
            long nkey = key(npos);
            Node prev = self.best.get(nkey);
            if (prev != null && prev.gCost <= g) {
                continue;
            }
            Node next = new Node(npos, g, heuristic(npos, target), current);
            self.best.put(nkey, next);
            self.open.add(next);
        }
        return false;
    }

    /** 前向链(start→meet) + 后向链(meet→goal)拼接成完整路径。 */
    private void meet(boolean currentIsForward, Node currentSide, Node otherSide) {
        Node fwdMeet = currentIsForward ? currentSide : otherSide;
        Node bwdMeet = currentIsForward ? otherSide : currentSide;

        List<BlockPos> forwardChain = new ArrayList<>();
        for (Node n = fwdMeet; n != null; n = n.parent) {
            forwardChain.add(n.pos);
        }
        Collections.reverse(forwardChain); // start → meet

        path.clear();
        path.addAll(forwardChain);
        // 后向链:从相遇点的 parent 开始(避免重复相遇点),meet → goal
        for (Node n = bwdMeet.parent; n != null; n = n.parent) {
            path.add(n.pos);
        }

        if (!path.isEmpty() && !path.get(path.size() - 1).equals(goal)) {
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

    private boolean segmentPassable(BlockPos from, BlockPos to) {
        if (from == null || to == null) {
            return false;
        }
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        int spacing = Math.max(1, Math.min(2, step));
        int samples = Math.max(1, (int) Math.ceil(distance / spacing));
        for (int s = 1; s < samples; s++) {
            double t = s / (double) samples;
            int x = (int) Math.round(from.getX() + dx * t);
            int z = (int) Math.round(from.getZ() + dz * t);
            WaterColumn column = world.sample(x, z, policy);
            if (column == null || !column.passable()) {
                return false;
            }
        }
        return true;
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

    /** 加权启发:线性距离 ×(1+ε)+ 偏离起终点连线的垂距小惩罚(tie-breaking,收窄搜索带)。 */
    private double heuristic(BlockPos pos, BlockPos target) {
        long dx = target.getX() - pos.getX();
        long dz = target.getZ() - pos.getZ();
        double straight = Math.sqrt((double) dx * dx + (double) dz * dz);
        return straight * (1.0D + HEURISTIC_EPSILON) + deviation(pos) * DEVIATION_WEIGHT;
    }

    /** 点到 start-goal 连线的垂距(用于 tie-breaking)。 */
    private double deviation(BlockPos pos) {
        double sx = start.getX();
        double sz = start.getZ();
        double gx = goal.getX();
        double gz = goal.getZ();
        double lineDx = gx - sx;
        double lineDz = gz - sz;
        double lenSq = lineDx * lineDx + lineDz * lineDz;
        if (lenSq < 1.0E-6D) {
            return 0.0D;
        }
        double cross = Math.abs((pos.getX() - sx) * lineDz - (pos.getZ() - sz) * lineDx);
        return cross / Math.sqrt(lenSq);
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

    /** 一侧搜索状态(前向或后向)。 */
    private static final class Search {
        private final PriorityQueue<Node> open = new PriorityQueue<>();
        private final Map<Long, Node> best = new HashMap<>();
        private final Set<Long> closed = new HashSet<>();
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

        private double f() {
            return gCost + hCost;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(f(), other.f());
        }
    }
}
