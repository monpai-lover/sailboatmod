package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

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
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int LOG_EVERY_NODES = 2000; // 每扩展这么多节点打一次进度

    private static final double HEURISTIC_EPSILON = 0.2D;
    private static final double DEVIATION_WEIGHT = 0.001D;

    private int lastLoggedAt = 0; // 上次打进度时的 expandedNodes

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
        // 关键:前向/后向必须落在同一套全局 step 网格上,否则两边格点错位(start/goal 在 step 上不整除时)
        // 永远命中不到同一格 → 双向 A* 永不相遇(实测前后向各扩展上千节点、前沿在目标附近横跳却碰不上)。
        // snap 把任意坐标对齐到 step 的整数倍,从 start 还是 goal 出发,落点都在同一网格,必能相遇。
        BlockPos snappedStart = snapToGrid(start);
        BlockPos snappedGoal = snapToGrid(goal);
        WaterColumn startColumn = world.sample(snappedStart.getX(), snappedStart.getZ(), policy);
        WaterColumn goalColumn = world.sample(snappedGoal.getX(), snappedGoal.getZ(), policy);
        boolean startOk = startColumn != null && startColumn.passable();
        boolean goalOk = goalColumn != null && goalColumn.passable();
        long straight = Math.round(Math.sqrt(start.distSqr(goal)));
        LOGGER.info("[WaterPath] 开始 start={} goal={} (网格对齐 s={} g={}) 直线={}格 起点可航={} 终点可航={}",
                start, goal, snappedStart, snappedGoal, straight, startOk, goalOk);
        if (!startOk || !goalOk) {
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

    /** 把坐标 snap 到 step 网格(向下取整到 step 倍数),保证前后向格点同网格、可相遇。 */
    private BlockPos snapToGrid(BlockPos pos) {
        int sx = Math.floorDiv(pos.getX(), step) * step;
        int sz = Math.floorDiv(pos.getZ(), step) * step;
        return new BlockPos(sx, pos.getY(), sz);
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
            if (expandedNodes - lastLoggedAt >= LOG_EVERY_NODES) {
                lastLoggedAt = expandedNodes;
                BlockPos fFront = forward.open.isEmpty() ? null : forward.open.peek().pos;
                BlockPos bFront = backward.open.isEmpty() ? null : backward.open.peek().pos;
                long fToGoal = fFront == null ? -1 : Math.round(Math.sqrt(fFront.distSqr(goal)));
                long bToStart = bFront == null ? -1 : Math.round(Math.sqrt(bFront.distSqr(start)));
                LOGGER.info("[WaterPath] 扩展{} 前向开集={} 后向开集={} 前向前沿离目标={}格 后向前沿离起点={}格",
                        expandedNodes, forward.open.size(), backward.open.size(), fToGoal, bToStart);
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

        // 相遇检测:对侧已生成过该格(在 best 里,无论 open/closed)→ 拼接。
        // 不再要求对侧 closed:若某侧因 f 较大长期不被扩展(其前沿节点一直留在 open),
        // 要求 closed 会导致已网格对齐、明明能碰上的两条链永远拼不起来 → 又超时。
        Node otherNode = other.best.get(curKey);
        if (otherNode != null) {
            meet(isForward, current, otherNode);
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

        // 首尾补回真实泊位坐标(搜索走 snap 网格,首尾可能偏真实泊位几格;补上让船精确停靠)。
        // Y 沿用路径首/尾航点(海平面),start/goal 的 Y 即泊位 Y,二者一致。
        int surfaceY = path.isEmpty() ? start.getY() : path.get(0).getY();
        BlockPos realStart = new BlockPos(start.getX(), surfaceY, start.getZ());
        BlockPos realGoal = new BlockPos(goal.getX(), surfaceY, goal.getZ());
        if (!path.isEmpty() && !path.get(0).equals(realStart)) {
            path.add(0, realStart);
        }
        if (!path.isEmpty() && !path.get(path.size() - 1).equals(realGoal)) {
            path.add(realGoal);
        }
        List<BlockPos> simplified = simplify(path);
        path.clear();
        path.addAll(simplified);
        status = Status.SUCCESS;
        LOGGER.info("[WaterPath] 成功 扩展{}节点 航点{}个", expandedNodes, path.size());
    }

    /**
     * 检测 from→to 这条 step 边中间是否全程可航(纯密度采样便宜,逐段插值堵 step=8 跳步漏检窄陆)。
     * 2026-06 回退:曾因 getBaseHeight 昂贵把这里砍成空(直线穿陆),现采样退回纯密度(便宜),恢复完整插值。
     */
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
        LOGGER.info("[WaterPath] 失败 原因={} 扩展{}节点 前向开集={} 后向开集={}",
                failureReason, expandedNodes,
                forward == null ? 0 : forward.open.size(),
                backward == null ? 0 : backward.open.size());
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

    /**
     * 接力绕行用:寻路未接回 goal(预算耗光)时,从前向已生成节点(best 集,均真实可航且从 start 可达)里取
     * <b>离 goal 最近</b>者作为「接力落点」,返回 start→该落点的回溯路径(含首尾)。无前向节点或落点即 start
     * (没推进)返回空。落点格保证可航(是 A* 扩展出来的合法节点),可当新好节点继续接力绕。
     */
    public List<BlockPos> forwardRelayLanding() {
        Node best = null;
        double bestH = Double.MAX_VALUE;
        for (Node n : forward.best.values()) {
            double h = n.pos.distSqr(goal);
            if (h < bestH) {
                bestH = h;
                best = n;
            }
        }
        if (best == null) {
            return List.of();
        }
        List<BlockPos> chain = new ArrayList<>();
        for (Node n = best; n != null; n = n.parent) {
            chain.add(n.pos);
        }
        Collections.reverse(chain); // start → landing
        if (chain.size() < 2) {
            return List.of(); // 落点即 start,没推进
        }
        return chain;
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
