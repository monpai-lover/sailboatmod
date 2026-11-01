package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 远航全局导向(2026-11,治「台北→阿姆斯特丹」类超远航线算不出):
 * <ol>
 *   <li><b>全局水域网格 A*</b>:格边长 {@code cell}(随距离取 16~128,不通再逐级减半)。原始数组 + 开放寻址哈希,
 *       百万级节点也只需几秒;双向搜索,<b>任一侧开集耗尽 = 该侧水域在此解析度下封闭</b>,立即判不通换更细格,
 *       不再像旧版那样在一侧封闭时让另一侧把整个大洋扩展完、耗尽预算。</li>
 *   <li><b>任意角拉直</b>:沿网格路径做视线(line-of-sight)拉直,得到大洋上的长直航段。</li>
 *   <li><b>航段分级</b>:每条拉直航段用全解析度水图检查「船宽 + 余量」走廊;全水 = 开阔水域,直接采用(不读区块、
 *       不跑精寻);碰到陆地/近岸 = 沿原网格中心点切成 ≤{@link #DETAIL_PIECE_BLOCKS} 格的小段交给精寻 A*。
 *       起终两端(泊位 ↔ 网格)永远精寻。</li>
 * </ol>
 * 水判定只经由传入的 {@link WaterRouteWorld}(stepSize 当作可接受的数据解析度),本类不碰任何区块/网络 API。
 */
public final class GlobalWaterGridPlanner {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int DETAIL_PIECE_BLOCKS = 2048;
    public static final int MIN_CELL = 16;
    public static final int MAX_CELL = 128;
    static final int MAX_EXPANSIONS = 1_500_000; // 台北→阿姆斯特丹实测 0.82M;内存约 200MB 以内
    /** 加权 A*:实测(台北→阿姆斯特丹)2.0 比 1.25 扩展少 35%、拉直后航线反而更短。 */
    private static final double WEIGHT = 2.0D;
    private static final double SQRT2 = Math.sqrt(2.0D);
    /** 开阔水域判定:航段两侧各此格数内全是水(船半宽 1 + 海岸线误差余量)。 */
    static final int DIRECT_CLEARANCE = 4;
    /** 优先的离岸距离:开阔航段尽量两侧各留这么多格水,只有窄处才退到 {@link #DIRECT_CLEARANCE}。 */
    static final int PREFERRED_CLEARANCE = 24;
    /** 贴岸网格格(8 邻有陆)的额外代价(以格为单位):有余地时走廊离岸一格,海峡里各格同价不影响通过。 */
    private static final double COAST_PENALTY = 0.6D;

    public record Leg(BlockPos from, BlockPos to, boolean direct) { }

    /** 概览用的「方块矩形内是否有水」(按地形源取样的概览;Tellus 世界改用 {@link SeaDistanceSource})。 */
    @FunctionalInterface
    public interface OverviewWater {
        boolean anyWater(int minX, int minZ, int maxX, int maxZ);
    }

    /** 到某端点的海路距离场(方块);-1 = 未知(调用方退回直线距离)。 */
    public interface SeaDistance {
        double distance(int x, int z);
        int settled();
    }

    /** 为一对端点建距离场(target 方向);Tellus 世界由内置全球概览提供,零下载。 */
    @FunctionalInterface
    public interface SeaDistanceSource {
        SeaDistance toward(BlockPos target, BlockPos other);
    }

    public record Guidance(List<Leg> legs, int cellSize, int expandedNodes, WaterRouteFailureReason failure) {
        public boolean successful() { return failure == WaterRouteFailureReason.NONE; }
    }

    private final WaterRouteWorld world;
    private final SeaDistanceSource seaSource;
    private final int seaY;
    private final WaterRoutePolicy[] policies = new WaterRoutePolicy[32];
    private final LongIntMap[] sampleCache = new LongIntMap[32];
    private static final int SAMPLE_CACHE_LIMIT = 1 << 20;
    private int expanded;

    private GlobalWaterGridPlanner(WaterRouteWorld world, SeaDistanceSource seaSource, int seaY) {
        this.world = world;
        this.seaSource = seaSource;
        this.seaY = seaY;
    }

    public static int initialCell(BlockPos start, BlockPos goal) {
        double distance = Math.hypot((double) goal.getX() - start.getX(), (double) goal.getZ() - start.getZ());
        int cell = MIN_CELL;
        while (cell < MAX_CELL && distance / cell > 2048) cell *= 2;
        return cell;
    }

    /** 全局导向 + 航段分级。不通时格边长逐级减半直到 {@link #MIN_CELL}。 */
    public static Guidance plan(WaterRouteWorld world, BlockPos start, BlockPos goal, int seaY) {
        return plan(world, null, start, goal, seaY);
    }

    public static Guidance plan(WaterRouteWorld world, SeaDistanceSource overview, BlockPos start, BlockPos goal, int seaY) {
        return start == null || goal == null ? plan(world, overview, start, goal, seaY, MIN_CELL)
                : plan(world, overview, start, goal, seaY, initialCell(start, goal));
    }

    static Guidance plan(WaterRouteWorld world, BlockPos start, BlockPos goal, int seaY, int firstCell) {
        return plan(world, null, start, goal, seaY, firstCell);
    }

    static Guidance plan(WaterRouteWorld world, SeaDistanceSource overview, BlockPos start, BlockPos goal, int seaY, int firstCell) {
        if (world == null || start == null || goal == null) {
            return new Guidance(List.of(), 0, 0, WaterRouteFailureReason.NO_WATER_PATH);
        }
        GlobalWaterGridPlanner planner = new GlobalWaterGridPlanner(world, overview, seaY);
        WaterRouteFailureReason failure = WaterRouteFailureReason.NO_WATER_PATH;
        for (int cell = firstCell; cell >= MIN_CELL; cell /= 2) {
            long t0 = System.nanoTime();
            int before = planner.expanded;
            Grid grid = planner.new Grid(cell, before + MAX_EXPANSIONS);
            List<BlockPos> centers = grid.search(start, goal);
            LOGGER.info("[WaterPath] 全局水域网格 cell={} 扩展={} 结果={} 耗时={}ms", cell, planner.expanded - before,
                    centers == null ? grid.failure : "航点" + centers.size(), (System.nanoTime() - t0) / 1_000_000L);
            if (centers != null) {
                List<Leg> legs = planner.classify(start, goal, centers, cell);
                return new Guidance(legs, cell, planner.expanded, WaterRouteFailureReason.NONE);
            }
            failure = grid.failure;
            if (failure != WaterRouteFailureReason.NO_WATER_PATH) break;
        }
        return new Guidance(List.of(), 0, planner.expanded, failure);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Water sampling

    private boolean water(int x, int z, int resolution) {
        WaterRouteCancellation.check();
        int r = Math.max(1, resolution);
        int slot = 31 - Integer.numberOfLeadingZeros(r);
        // 网格边/视线的取样点落在同一格点阵上大量重复:每个(解析度, x, z)只问一次地形源(Tellus 每次取样含投影换算与缓存查找)。
        LongIntMap cache = sampleCache[slot];
        if (cache == null || cache.size() > SAMPLE_CACHE_LIMIT) sampleCache[slot] = cache = new LongIntMap();
        long key = key(x, z);
        int cached = cache.get(key);
        if (cached >= 0) return cached == 1;
        boolean result = sampleWorld(x, z, slot);
        cache.put(key, result ? 1 : 0);
        return result;
    }

    private boolean sampleWorld(int x, int z, int slot) {
        WaterRoutePolicy policy = policies[slot];
        if (policy == null) {
            policy = new WaterRoutePolicy(1 << slot, 2, 0, 3, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 1, 0, 1);
            policies[slot] = policy;
        }
        WaterColumn column = world.sample(x, z, policy);
        return column != null && column.passable();
    }

    private boolean lineClear(int ax, int az, int bx, int bz, int spacing, int lateral, int resolution) {
        return lineClear(ax, az, bx, bz, spacing, lateral, resolution, Math.max(1, lateral / 2));
    }

    private boolean lineClear(int ax, int az, int bx, int bz, int spacing, int lateral, int resolution, int lateralStep) {
        double dx = bx - ax, dz = bz - az;
        double length = Math.sqrt(dx * dx + dz * dz);
        int samples = Math.max(1, (int) Math.ceil(length / spacing));
        double nx = length == 0 ? 0 : -dz / length, nz = length == 0 ? 0 : dx / length;
        for (int s = 0; s <= samples; s++) {
            double t = s / (double) samples;
            double x = ax + dx * t, z = az + dz * t;
            for (int o = -lateral; o <= lateral; o += Math.max(1, lateralStep)) {
                if (!water((int) Math.round(x + nx * o), (int) Math.round(z + nz * o), resolution)) return false;
                if (lateral == 0) break;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Leg classification

    private List<Leg> classify(BlockPos start, BlockPos goal, List<BlockPos> centers, int cell) {
        int coarseRes = Math.max(2, cell / 4);
        int lateral = Math.max(2, cell / 8);
        // 拉直:指数 + 二分找最远可视点(粗解析度视线,带少量横向余量)。
        List<Integer> pulled = new ArrayList<>();
        int i = 0;
        pulled.add(0);
        while (i < centers.size() - 1) {
            int lo = i + 1, hi = i + 2, stride = 2;
            while (hi < centers.size() && visible(centers.get(i), centers.get(hi), coarseRes, lateral)) {
                lo = hi;
                stride *= 2;
                hi = i + stride;
            }
            hi = Math.min(hi, centers.size());
            while (hi - lo > 1) {
                int mid = (lo + hi) >>> 1;
                if (visible(centers.get(i), centers.get(mid), coarseRes, lateral)) lo = mid; else hi = mid;
            }
            pulled.add(lo);
            i = lo;
        }

        List<Leg> legs = new ArrayList<>();
        BlockPos first = centers.getFirst(), last = centers.getLast();
        if (!sameXZ(start, first)) legs.add(new Leg(start, first, false));
        int direct = 0, detailed = 0;
        for (int k = 1; k < pulled.size(); k++) {
            int from = pulled.get(k - 1), to = pulled.get(k);
            BlockPos a = centers.get(from), b = centers.get(to);
            if (fineClear(a, b, PREFERRED_CLEARANCE)) {
                legs.add(new Leg(a, b, true));
                direct++;
                continue;
            }
            // 粗视线可见但全解析度不净空(近岸/小岛):在这一段网格中心上再做一次全解析度拉直,
            // 能净空的子段仍直航;只有相邻网格中心之间都不净空的地方才交精寻(近岸窄处)。
            int c = from;
            int pieceStart = -1;
            while (c < to) {
                int far = farthestFineVisible(centers, c, to, PREFERRED_CLEARANCE);
                if (far == c) far = farthestFineVisible(centers, c, to, DIRECT_CLEARANCE);
                if (far > c) {
                    if (pieceStart >= 0) { detailed += addPieces(legs, centers, pieceStart, c, cell); pieceStart = -1; }
                    legs.add(new Leg(centers.get(c), centers.get(far), true));
                    direct++;
                    c = far;
                } else {
                    if (pieceStart < 0) pieceStart = c;
                    c++;
                }
            }
            if (pieceStart >= 0) detailed += addPieces(legs, centers, pieceStart, to, cell);
        }
        if (!sameXZ(goal, last)) legs.add(new Leg(last, goal, false));
        if (legs.isEmpty()) legs.add(new Leg(start, goal, false));
        LOGGER.info("[WaterPath] 全局导向:网格点={} 拉直后={} 开阔直航段={} 精寻小段={}", centers.size(), pulled.size(), direct, detailed);
        return legs;
    }

    private boolean fineClear(BlockPos a, BlockPos b, int clearance) {
        if (!lineClear(a.getX(), a.getZ(), b.getX(), b.getZ(), 2, DIRECT_CLEARANCE, 1)) return false;
        // 外圈稀疏检查(每 4 格一列、横向每 6 格一点):只为离岸偏好,内圈 ±4 已逐 2 格密查保证船体安全。
        return clearance <= DIRECT_CLEARANCE || lineClear(a.getX(), a.getZ(), b.getX(), b.getZ(), 4, clearance, 1, 6);
    }

    /** 从 i 起全解析度净空可达的最远网格中心(≤ to);连下一点都不净空返回 i。 */
    private int farthestFineVisible(List<BlockPos> centers, int i, int to, int clearance) {
        if (!fineClear(centers.get(i), centers.get(i + 1), clearance)) return i;
        int lo = i + 1, hi = i + 2, stride = 2;
        while (hi <= to && fineClear(centers.get(i), centers.get(hi), clearance)) {
            lo = hi;
            stride *= 2;
            hi = i + stride;
        }
        hi = Math.min(hi, to + 1);
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (fineClear(centers.get(i), centers.get(mid), clearance)) lo = mid; else hi = mid;
        }
        return lo;
    }

    /** 网格中心 [from..to] 切成 ≤ DETAIL_PIECE_BLOCKS 的精寻小段。 */
    private static int addPieces(List<Leg> legs, List<BlockPos> centers, int from, int to, int cell) {
        int pieces = 0;
        BlockPos pieceStart = centers.get(from);
        double run = 0;
        for (int c = from + 1; c <= to; c++) {
            run += Math.sqrt(centers.get(c - 1).distSqr(centers.get(c)));
            if (c == to || run + cell * SQRT2 > DETAIL_PIECE_BLOCKS) {
                legs.add(new Leg(pieceStart, centers.get(c), false));
                pieces++;
                pieceStart = centers.get(c);
                run = 0;
            }
        }
        return pieces;
    }

    private boolean visible(BlockPos a, BlockPos b, int resolution, int lateral) {
        return lineClear(a.getX(), a.getZ(), b.getX(), b.getZ(), resolution, lateral, resolution);
    }

    private static boolean sameXZ(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Grid search

    private final class Grid {
        private final int cell, edgeSpacing, resolution, expansionLimit;
        private final LongByteMap cellWater = new LongByteMap();
        private WaterRouteFailureReason failure = WaterRouteFailureReason.NO_WATER_PATH;
        private SeaDistance towardGoal, towardStart;

        Grid(int cell, int expansionLimit) {
            this.cell = cell;
            this.expansionLimit = expansionLimit;
            this.edgeSpacing = Math.max(2, cell / 4);
            this.resolution = Math.max(2, cell / 4);
        }

        int center(int c) { return c * cell + cell / 2; }

        boolean cellWater(int cx, int cz) {
            long key = key(cx, cz);
            int v = cellWater.get(key);
            if (v < 0) {
                v = water(center(cx), center(cz), resolution) ? 1 : 0;
                cellWater.put(key, (byte) v);
            }
            return v == 1;
        }

        /** 8 邻里有陆格(格本身是水)。只读已缓存/廉价的格中心取样。 */
        boolean coastal(int cx, int cz) {
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && !cellWater(cx + dx, cz + dz)) return true;
            }
            return false;
        }

        boolean edge(int ax, int az, int bx, int bz) {
            return lineClear(center(ax), center(az), center(bx), center(bz), edgeSpacing, 0, resolution);
        }

        /** 网格上离 pos 最近的水格(8 圈内)。 */
        long nearestWaterCell(BlockPos pos) {
            int pcx = Math.floorDiv(pos.getX(), cell), pcz = Math.floorDiv(pos.getZ(), cell);
            long best = Long.MIN_VALUE;
            double bestD = Double.MAX_VALUE;
            for (int r = 0; r <= 8; r++) {
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int cx = pcx + dx, cz = pcz + dz;
                    if (!cellWater(cx, cz)) continue;
                    double d = Math.hypot(center(cx) - pos.getX(), center(cz) - pos.getZ());
                    if (d < bestD) { bestD = d; best = key(cx, cz); }
                }
                if (best != Long.MIN_VALUE && r >= 1) break;
            }
            return best;
        }

        List<BlockPos> search(BlockPos start, BlockPos goal) {
            long s = nearestWaterCell(start), g = nearestWaterCell(goal);
            if (s == Long.MIN_VALUE || g == Long.MIN_VALUE) return null;
            if (s == g) return List.of(new BlockPos(center(kx(s)), seaY, center(kz(s))));
            int overviewCell = cell * OVERVIEW_RATIO;
            BlockPos sc = new BlockPos(center(kx(s)), seaY, center(kz(s))), gc = new BlockPos(center(kx(g)), seaY, center(kz(g)));
            long t0 = System.nanoTime();
            towardGoal = seaSource != null ? seaSource.toward(gc, sc) : new Overview(overviewCell, gc, sc);
            towardStart = seaSource != null ? seaSource.toward(sc, gc) : new Overview(overviewCell, sc, gc);
            LOGGER.info("[WaterPath] 全局概览 cell={} 距离场 目标侧={}格 起点侧={}格 耗时={}ms", overviewCell,
                    towardGoal.settled(), towardStart.settled(), (System.nanoTime() - t0) / 1_000_000L);
            Side fwd = new Side(kx(g), kz(g), towardGoal), bwd = new Side(kx(s), kz(s), towardStart);
            fwd.seed(s);
            bwd.seed(g);
            int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
            int[] dz = {0, 0, 1, -1, 1, -1, 1, -1};
            while (true) {
                if (expanded >= expansionLimit) { failure = WaterRouteFailureReason.NODE_BUDGET_EXCEEDED; return null; }
                // 任一侧开集耗尽 = 该侧连通水域已全部扩展仍未相遇 → 此解析度下不连通(快速失败)。
                if (!fwd.clean() || !bwd.clean()) { failure = WaterRouteFailureReason.NO_WATER_PATH; return null; }
                // 严格交替:加权启发下「扩 f 小的一侧」会让一侧独占(f 一路下降),两前沿永不相遇。
                Side self = (expanded & 1) == 0 ? fwd : bwd;
                Side other = self == fwd ? bwd : fwd;
                int node = self.pop();
                self.closed[node] = true;
                expanded++;
                long nodeKey = self.keys[node];
                int otherNode = other.index.get(nodeKey);
                if (otherNode >= 0 && other.closed[otherNode]) {
                    return self == fwd ? join(fwd, node, bwd, otherNode) : join(fwd, otherNode, bwd, node);
                }
                int cx = kx(nodeKey), cz = kz(nodeKey);
                for (int d = 0; d < 8; d++) {
                    int nx = cx + dx[d], nz = cz + dz[d];
                    long nk = key(nx, nz);
                    int existing = self.index.get(nk);
                    if (existing >= 0 && self.closed[existing]) continue;
                    int across = other.index.get(nk);
                    if (across >= 0 && other.closed[across] && cellWater(nx, nz) && edge(cx, cz, nx, nz)) {
                        // 邻格已被对侧确定:直接拼接(self 链 → 邻格 → 对侧链)。
                        int linked = self.size;
                        self.push(nk, self.g[node] + (d < 4 ? 1.0D : SQRT2), node, existing);
                        int selfNode = existing >= 0 ? existing : linked;
                        return self == fwd ? join(fwd, selfNode, bwd, across) : join(fwd, across, bwd, selfNode);
                    }
                    double ng = self.g[node] + (d < 4 ? 1.0D : SQRT2) + (coastal(nx, nz) ? COAST_PENALTY : 0);
                    if (existing >= 0 && self.g[existing] <= ng) continue;
                    if (!cellWater(nx, nz) || !edge(cx, cz, nx, nz)) continue;
                    if (d >= 4 && !cellWater(cx, nz) && !cellWater(nx, cz)) continue; // 两侧都是陆:不切角穿过
                    self.push(nk, ng, node, existing);
                }
            }
        }

        private List<BlockPos> join(Side fwd, int fNode, Side bwd, int bNode) {
            List<BlockPos> out = new ArrayList<>();
            for (int n = fNode; n >= 0; n = fwd.parent[n]) out.add(pos(fwd.keys[n]));
            java.util.Collections.reverse(out);
            for (int n = bwd.parent[bNode]; n >= 0; n = bwd.parent[n]) out.add(pos(bwd.keys[n]));
            return out;
        }

        private BlockPos pos(long key) {
            return new BlockPos(center(kx(key)), seaY, center(kz(key)));
        }

        private final class Side {
            final int tx, tz;
            final SeaDistance overview;
            final LongIntMap index = new LongIntMap();
            long[] keys = new long[1024];
            double[] g = new double[1024];
            int[] parent = new int[1024];
            boolean[] closed = new boolean[1024];
            int size;
            double[] heapF = new double[1024];
            int[] heapN = new int[1024];
            int heapSize;

            Side(int tx, int tz, SeaDistance overview) { this.tx = tx; this.tz = tz; this.overview = overview; }

            void seed(long key) { push(key, 0, -1, -1); }

            double h(long key) {
                double dx = kx(key) - tx, dz = kz(key) - tz;
                double straight = Math.sqrt(dx * dx + dz * dz);
                // 概览距离场 = 绕开大陆的海路距离(以细格为单位):让搜索沿海路走廊推进,而不是对着大陆另一侧的目标
                // 把整片大洋扩展完。概览格比细格粗 OVERVIEW_RATIO 倍,减一格的量化误差后再与直线距离取大。
                double sea = overview == null ? -1 : overview.distance(center(kx(key)), center(kz(key)));
                return (sea < 0 ? straight : Math.max(straight, sea / cell - OVERVIEW_RATIO)) * WEIGHT;
            }

            void push(long key, double gCost, int parentNode, int existing) {
                int node = existing;
                if (node < 0) {
                    if (size == keys.length) grow();
                    node = size++;
                    keys[node] = key;
                    index.put(key, node);
                }
                g[node] = gCost;
                parent[node] = parentNode;
                heapPush(gCost + h(key), node);
            }

            /** Drops stale heap entries; false when the open set is empty. */
            boolean clean() {
                while (heapSize > 0) {
                    int n = heapN[0];
                    if (!closed[n] && Math.abs(heapF[0] - (g[n] + h(keys[n]))) < 1.0E-9D) return true;
                    heapPop();
                }
                return false;
            }

            int pop() {
                int n = heapN[0];
                heapPop();
                return n;
            }

            private void grow() {
                int cap = keys.length * 2;
                keys = Arrays.copyOf(keys, cap);
                g = Arrays.copyOf(g, cap);
                parent = Arrays.copyOf(parent, cap);
                closed = Arrays.copyOf(closed, cap);
            }

            private void heapPush(double f, int n) {
                if (heapSize == heapF.length) {
                    heapF = Arrays.copyOf(heapF, heapSize * 2);
                    heapN = Arrays.copyOf(heapN, heapSize * 2);
                }
                int i = heapSize++;
                while (i > 0) {
                    int p = (i - 1) >>> 1;
                    if (heapF[p] <= f) break;
                    heapF[i] = heapF[p];
                    heapN[i] = heapN[p];
                    i = p;
                }
                heapF[i] = f;
                heapN[i] = n;
            }

            private void heapPop() {
                double f = heapF[--heapSize];
                int n = heapN[heapSize];
                int i = 0;
                while (true) {
                    int c = 2 * i + 1;
                    if (c >= heapSize) break;
                    if (c + 1 < heapSize && heapF[c + 1] < heapF[c]) c++;
                    if (heapF[c] >= f) break;
                    heapF[i] = heapF[c];
                    heapN[i] = heapN[c];
                    i = c;
                }
                if (heapSize > 0) {
                    heapF[i] = f;
                    heapN[i] = n;
                }
            }
        }
    }

    static final int OVERVIEW_RATIO = 8;
    private static final int OVERVIEW_MAX_SETTLED = 200_000;

    /**
     * 概览距离场:比细格粗 {@link #OVERVIEW_RATIO} 倍的乐观水网(格内 3×3 任一取样是水即可航,邻格直接相连),
     * 从 {@code target} 朝 {@code other} 跑 A*(一致启发,已确定格的距离精确),直到 other 确定后继续到 f ≤ 1.3×最优,
     * 只确定航线走廊一带,不铺满整个大洋。
     * 取样解析度很粗(Tellus 只用最低几级遮罩瓦片,整条航线几十张),是细搜索的启发式,不决定可航性。
     */
    private final class Overview implements SeaDistance {
        private final int cell;
        private final LongIntMap index = new LongIntMap();
        private final LongByteMap water = new LongByteMap();
        private double[] dist = new double[1024];
        private int size;

        Overview(int cell, BlockPos target, BlockPos other) {
            this.cell = cell;
            long t = key(Math.floorDiv(target.getX(), cell), Math.floorDiv(target.getZ(), cell));
            long o = key(Math.floorDiv(other.getX(), cell), Math.floorDiv(other.getZ(), cell));
            double[] heapF = new double[1024];
            long[] heapK = new long[1024];
            int heap = 0;
            boolean[] closed = new boolean[1024];
            index.put(t, size);
            dist[size++] = 0;
            int ox = kx(o), oz = kz(o);
            heapF[0] = Math.hypot(kx(t) - ox, kz(t) - oz);
            heapK[0] = t;
            heap = 1;
            double stopAt = Double.MAX_VALUE;
            int settled = 0;
            int[] dx = {1, -1, 0, 0, 1, 1, -1, -1};
            int[] dz = {0, 0, 1, -1, 1, -1, 1, -1};
            while (heap > 0 && settled < OVERVIEW_MAX_SETTLED) {
                double f = heapF[0];
                long k = heapK[0];
                // pop
                heap--;
                double lf = heapF[heap];
                long lk = heapK[heap];
                int i = 0;
                while (true) {
                    int c = 2 * i + 1;
                    if (c >= heap) break;
                    if (c + 1 < heap && heapF[c + 1] < heapF[c]) c++;
                    if (heapF[c] >= lf) break;
                    heapF[i] = heapF[c];
                    heapK[i] = heapK[c];
                    i = c;
                }
                if (heap > 0) { heapF[i] = lf; heapK[i] = lk; }
                int n = index.get(k);
                if (n >= closed.length) closed = Arrays.copyOf(closed, Math.max(n + 1, closed.length * 2));
                if (closed[n] || f > dist[n] + Math.hypot(kx(k) - ox, kz(k) - oz) + 1.0E-9D) continue;
                closed[n] = true;
                settled++;
                if (f > stopAt) break;
                if (k == o) stopAt = dist[n] * 1.3D + 4;
                int cx = kx(k), cz = kz(k);
                for (int d = 0; d < 8; d++) {
                    int nx = cx + dx[d], nz = cz + dz[d];
                    if (!anyWater(nx, nz)) continue;
                    long nk = key(nx, nz);
                    double nd = dist[n] + (d < 4 ? 1.0D : SQRT2);
                    int m = index.get(nk);
                    if (m >= 0 && dist[m] <= nd) continue;
                    if (m < 0) {
                        if (size == dist.length) dist = Arrays.copyOf(dist, size * 2);
                        m = size++;
                        index.put(nk, m);
                    }
                    dist[m] = nd;
                    double nf = nd + Math.hypot(nx - ox, nz - oz);
                    if (heap == heapF.length) { heapF = Arrays.copyOf(heapF, heap * 2); heapK = Arrays.copyOf(heapK, heap * 2); }
                    int j = heap++;
                    while (j > 0) {
                        int p = (j - 1) >>> 1;
                        if (heapF[p] <= nf) break;
                        heapF[j] = heapF[p];
                        heapK[j] = heapK[p];
                        j = p;
                    }
                    heapF[j] = nf;
                    heapK[j] = nk;
                }
            }
            this.settledCount = settled;
            this.closedFlags = closed;
        }

        private final int settledCount;
        private final boolean[] closedFlags;

        @Override public int settled() { return settledCount; }

        /** 方块坐标到 target 的概览海路距离(方块);未确定的概览格返回 -1(调用方退回直线距离)。 */
        @Override public double distance(int x, int z) {
            int n = index.get(key(Math.floorDiv(x, cell), Math.floorDiv(z, cell)));
            if (n < 0 || n >= closedFlags.length || !closedFlags[n]) return -1;
            return dist[n] * cell;
        }

        private boolean anyWater(int cx, int cz) {
            long k = key(cx, cz);
            int v = water.get(k);
            if (v < 0) {
                v = 0;
                int res = Math.max(2, cell / 4);
                for (int i = 0; i < 3 && v == 0; i++) {
                    for (int j = 0; j < 3 && v == 0; j++) {
                        if (water(cx * cell + cell * (2 * i + 1) / 6, cz * cell + cell * (2 * j + 1) / 6, res)) v = 1;
                    }
                }
                water.put(k, (byte) v);
            }
            return v == 1;
        }
    }

    static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }
    static int kx(long key) { return (int) (key >> 32); }
    static int kz(long key) { return (int) key; }

    /** 开放寻址 long→int(缺省 -1)。 */
    static final class LongIntMap {
        private long[] keys = new long[1 << 12];
        private int[] values = new int[1 << 12];
        private boolean[] used = new boolean[1 << 12];
        private int size;

        int size() { return size; }

        int get(long key) {
            int mask = keys.length - 1;
            for (int i = mix(key) & mask; used[i]; i = (i + 1) & mask) if (keys[i] == key) return values[i];
            return -1;
        }

        void put(long key, int value) {
            if (size * 2 >= keys.length) rehash();
            int mask = keys.length - 1;
            int i = mix(key) & mask;
            while (used[i] && keys[i] != key) i = (i + 1) & mask;
            if (!used[i]) { used[i] = true; keys[i] = key; size++; }
            values[i] = value;
        }

        private void rehash() {
            long[] ok = keys; int[] ov = values; boolean[] ou = used;
            keys = new long[ok.length * 2]; values = new int[ok.length * 2]; used = new boolean[ok.length * 2];
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ou[i]) put(ok[i], ov[i]);
        }
    }

    /** 开放寻址 long→byte(缺省 -1)。 */
    static final class LongByteMap {
        private final LongIntMap map = new LongIntMap();
        int get(long key) { return map.get(key); }
        void put(long key, byte value) { map.put(key, value); }
    }

    private static int mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }
}
