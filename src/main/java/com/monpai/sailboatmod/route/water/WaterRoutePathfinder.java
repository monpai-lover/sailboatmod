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
    /** 软代价(extraCost)按步长归一化的基准步长:每步软代价 = extraCost × step / NORMALIZE_STEP,使不同 step 的
     *  阶段(coarse step24 / refine step8)跨同样格数累积的总软代价一致,消除 refine 因密度高而虚高绕远。取 8(refine 步长)。 */
    private static final int NORMALIZE_STEP = 8;

    private int lastLoggedAt = 0; // 上次打进度时的 expandedNodes

    /** 合法 step 边最长是对角 step·√2;×1.2 余量容首尾 snap 微偏。超此即拼接出现空隙=假相遇。 */
    private static final double LEGAL_HOP_FACTOR = 1.4142135623730951D * 1.2D;

    private final WaterRouteWorld world;
    private final BlockPos start;
    private final BlockPos goal;
    private final WaterRoutePolicy policy;
    private final int step;
    private final double MAX_LEGAL_HOP;

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
        this.MAX_LEGAL_HOP = this.step * LEGAL_HOP_FACTOR;
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
            // 精确原因:3×3 在哪格过不去(中心非水/区块读不到/占地哪格陆/约束外/泊位信任)。定位 seed 0 节点失败。
            if (!startOk) {
                LOGGER.warn("[WaterPath] 起点不可航 snap={} 原因={}", snappedStart,
                        world.sampleDiagnostic(snappedStart.getX(), snappedStart.getZ(), policy));
            }
            if (!goalOk) {
                LOGGER.warn("[WaterPath] 终点不可航 snap={} 原因={}", snappedGoal,
                        world.sampleDiagnostic(snappedGoal.getX(), snappedGoal.getZ(), policy));
            }
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

        // 相遇检测:<b>必须对侧已 closed(确定最优到达)该格</b>才拼接,不能只看 best 里生成过。
        // 根因([[water_route_real_root_autopilot]] 的真正下游 bug):best 里有「仅 open 待扩展」的陈旧节点,
        // 其 parent 是 PriorityQueue 懒删除/覆盖遗留的 stale 链——后向早期把远处格临时挂在「goal 一跳可达」的
        // 松估算子树上(parent 指 goal 邻域)。前向撞上这种节点误判相遇,meet 回溯一跳从相遇点跳回 goal 邻域
        // (实测 1664 格),拼出带空隙的假成功路径 → 空隙经 PathSmoother 加密成直线穿陆。
        // closed 节点在扩展那刻 parent 已定型、链合法连续到源点 → 只认 closed 从根上消除 stale 跳格。
        // 网格已 snap 对齐(seed),双向必能在同一水格 closed-closed 真实碰头;凑不齐则正常失败交上层放宽走廊。
        if (other.closed.contains(curKey)) {
            Node otherNode = other.best.get(curKey);
            if (otherNode != null && meet(isForward, current, otherNode)) {
                return true; // 合法相遇(拼接后连续性校验通过)
            }
            // 假相遇被拒(拼接出现异常大跳)→ 不 return,把 current 当普通节点继续扩展邻居,让 A* 继续搜索。
            // current 已 closed(确实处理过),邻居照常入 open,直到出现真正逐格连续的合法相遇才收敛。
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
            // 2026-06 软代价按步长归一化(修 refine 比 coarse 还差):extraCost(贴岸/占地软惩罚)原来每步加一个固定量,
            // 不随 step 缩放 → refine(step8)在同一窄道扩展步数是 coarse(step24)的 3 倍 → 累积软代价 ~3 倍 → A* 误判
            // 窄道「贵 3 倍」宁可绕远,结果 refine 比 coarse 更差。修法:每步软代价 = extraCost × step / NORMALIZE_STEP,
            // 这样跨同样格数累积的总软代价与 step 无关(coarse 步少但每步重、refine 步多但每步轻,总和相等)。
            // NORMALIZE_STEP=8(refine 步长)使 refine 权重不变、coarse 跟着加重到一致。([[water_route_real_root_autopilot]] 相关)
            double normalizedExtra = column.extraCost() * step / (double) NORMALIZE_STEP;
            double g = current.gCost + edge + normalizedExtra;
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

    /**
     * 前向链(start→meet) + 后向链(meet→goal)拼接成完整路径。
     * <b>拼接前做逐格连续性校验</b>:纯网格链(补首尾泊位前)任一相邻航点间距 &gt; {@link #MAX_LEGAL_HOP} 即
     * 判<b>假相遇</b>(两条链没真正在同一水格逐格接上,中间是 stale parent 跳格留的空隙)→ 不拼、返回 false,
     * 由 {@link #expandStep} 继续搜索。校验通过才落 SUCCESS。
     * @return true=合法相遇已拼接;false=假相遇被拒(继续搜索)。
     */
    private boolean meet(boolean currentIsForward, Node currentSide, Node otherSide) {
        Node fwdMeet = currentIsForward ? currentSide : otherSide;
        Node bwdMeet = currentIsForward ? otherSide : currentSide;

        // 纯网格链(不含首尾泊位回贴):前向 start→meet + 后向 meet.parent→goal。
        List<BlockPos> gridChain = new ArrayList<>();
        for (Node n = fwdMeet; n != null; n = n.parent) {
            gridChain.add(n.pos);
        }
        Collections.reverse(gridChain); // start → meet
        for (Node n = bwdMeet.parent; n != null; n = n.parent) {
            gridChain.add(n.pos); // meet → goal
        }

        // 逐格连续性校验:相邻航点最大间距。合法 step 边最长是对角 step·√2;给 1.2 余量容首尾 snap 微偏。
        double maxHop = 0.0D;
        BlockPos worstA = null, worstB = null;
        for (int i = 1; i < gridChain.size(); i++) {
            double hop = Math.sqrt(gridChain.get(i - 1).distSqr(gridChain.get(i)));
            if (hop > maxHop) {
                maxHop = hop;
                worstA = gridChain.get(i - 1);
                worstB = gridChain.get(i);
            }
        }
        if (maxHop > MAX_LEGAL_HOP) {
            // 假相遇:相遇点两侧链没真正逐格接上(stale parent 跳格)。拒绝拼接,继续搜索。
            LOGGER.warn("[WaterPath] 假相遇已拒绝 meet={} 最大跳={}格({}→{}) 对侧closed=true 继续搜索",
                    currentSide.pos, (int) maxHop, worstA, worstB);
            return false;
        }

        path.clear();
        path.addAll(gridChain);
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
        LOGGER.info("[WaterPath] 成功 扩展{}节点 航点{}个 相遇点={} 最大跳={}格", expandedNodes, path.size(), currentSide.pos, (int) maxHop);
        return true;
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

    public Status status() {
        return status;
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
