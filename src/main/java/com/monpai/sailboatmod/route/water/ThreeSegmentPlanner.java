package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.road.pathfinding.cache.NoiseChunkHeightSampler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 两段式航行编排(在后台线程串行跑;类名沿用 ThreeSegmentPlanner 保持接线不变,实为「真实起终 + 噪声中段」两段):
 * <ol>
 *   <li>起始段:从港口泊位用真实区块(RealChunkRouteWorld + 近海偏好 + 泊位信任)驶到快照边缘大洋方向衔接点 A,
 *       pathA 末点 = nodeA。</li>
 *   <li>尾段:目标港口泊位同理驶到衔接点 B,pathB 末点 = nodeB(最终 reverse 即进港方向)。</li>
 *   <li>中段:nodeA→nodeB 两阶段长距离(粗大step粗寻 + 沿粗路走廊精寻,ServerWaterRouteWorld 噪声)。</li>
 * </ol>
 * 拼接 pathA + pathMid + reverse(pathB),去重接头。执行顺序「先两端求 A/B 再中段连接」。
 *
 * <p><b>取消贴岸高精度段</b>:旧版单独跑 coastalHighPrecision A* 求出口节点,但 berth 由噪声世界解析、贴岸段用
 * 真实区块判定,两套采样器对同一泊位打架 → 起点判不可航必降级 → 中段从 near-岸点直线穿陆。现起终段直接用真实
 * 区块 + 泊位信任(泊位无条件可航)+ 近海偏好驶到大洋衔接点,不再有「贴岸段失败→降级」退化路径。
 *
 * <p>降级:起终段失败(罕见,泊位已信任) → 退泊位直连中段;中段失败 → 硬 NO_WATER_PATH。
 */
public final class ThreeSegmentPlanner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CORRIDOR_RADIUS = 48;      // 中段精阶段走廊半径
    private static final int DEDUP_DIST = 16;           // 拼接接头去重阈值(格)

    // ---- 中段真实区块参数(REALCHUNK 模式;NOISE 模式走 NoiseChunk 精判,不加载真实区块)----
    // 沿 A→B 直线带的宽走廊半径,渐进放宽:先窄(路直时快),绕不开大陆逐级放宽(给 A* 绕行空间)。
    private static final int[] REAL_BAND_RADII = {192, 384, 768};
    private static final long MID_DEADLINE_MS = 300000;  // 中段真实加载/寻路总 wall-clock 上限(5 分钟,只防死循环,不防总量)

    /** 进度回调(percent 0~100,stage 文案);后台各阶段调用,实现里切主线程更新 BossBar。 */
    @FunctionalInterface
    public interface ProgressSink {
        void update(int percent, String stage);

        ProgressSink NOOP = (p, s) -> { };
    }

    private ThreeSegmentPlanner() {
    }

    /**
     * 拼接后的折线 + 两个段边界 index(去折后,以下游平滑前的原始折线为准)。
     * jointA = 起始段→中段 边界,jointB = 中段→尾段 边界。供 debug 工具按"平滑点到原折线最近投影 index"分段。
     */
    public record PlannedPath(List<BlockPos> path, int jointA, int jointB) {
    }

    public static WaterRouteResult<List<BlockPos>> runSerial(
            RealChunkRouteWorld startWorld,
            RealChunkRouteWorld endWorld,
            ServerWaterRouteWorld midWorld,
            BlockPos srcBerth,
            BlockPos tgtBerth,
            int radius,
            int threshold) {
        WaterRouteResult<PlannedPath> r = runSerialPlanned(startWorld, endWorld, midWorld, srcBerth, tgtBerth, radius, threshold, WaterMidMode.current(), ProgressSink.NOOP);
        return r.successful() ? WaterRouteResult.success(r.value().path()) : WaterRouteResult.failure(r.reason());
    }

    /** 旧签名:只要折线不要 segment 边界(测试 / 非 debug 路径用)。 */
    public static WaterRouteResult<List<BlockPos>> runSerial(
            RealChunkRouteWorld startWorld,
            RealChunkRouteWorld endWorld,
            ServerWaterRouteWorld midWorld,
            BlockPos srcBerth,
            BlockPos tgtBerth,
            int radius,
            int threshold,
            WaterMidMode mode,
            ProgressSink progress) {
        WaterRouteResult<PlannedPath> r = runSerialPlanned(startWorld, endWorld, midWorld, srcBerth, tgtBerth, radius, threshold, mode, progress);
        return r.successful() ? WaterRouteResult.success(r.value().path()) : WaterRouteResult.failure(r.reason());
    }

    /** 带 segment 边界的编排:返回去折后折线 + jointA/jointB,供 debug 工具分段。 */
    public static WaterRouteResult<PlannedPath> runSerialPlanned(
            RealChunkRouteWorld startWorld,
            RealChunkRouteWorld endWorld,
            ServerWaterRouteWorld midWorld,
            BlockPos srcBerth,
            BlockPos tgtBerth,
            int radius,
            int threshold,
            WaterMidMode mode,
            ProgressSink progress) {
        ProgressSink prog = progress == null ? ProgressSink.NOOP : progress;
        try {
            WaterRoutePolicy segPolicy = WaterRoutePolicy.realChunkSegment();
            // 泊位信任:起终段对各自泊位邻域无条件可航(消除真实区块 vs 噪声泊位解析器判定打架 → 不再降级)。
            startWorld.setTrustedBerth(srcBerth);
            endWorld.setTrustedBerth(tgtBerth);

            // ---- 段1:起点真实区块驶向快照边缘大洋衔接点 A;nodeA = pathA 真实末点 ----
            prog.update(3, "起点港口寻路");
            BlockPos startGoal = CoastalExitResolver.oceanGoal(startWorld, srcBerth, radius);
            List<BlockPos> pathA = runPathfinder(startWorld, srcBerth, startGoal, segPolicy);
            BlockPos nodeA;
            if (pathA == null || pathA.size() < 2) {
                LOGGER.warn("[WaterPath] 两段:起点真实区块段失败,降级泊位直连中段 berth={}", srcBerth);
                pathA = new ArrayList<>(List.of(new BlockPos(srcBerth.getX(), midWorld.waterSurfaceY(srcBerth.getX(), srcBerth.getZ()), srcBerth.getZ())));
                nodeA = pathA.get(0);
            } else {
                pathA = new ArrayList<>(pathA);
                nodeA = pathA.get(pathA.size() - 1);
            }
            int outA = 0;
            for (BlockPos p : pathA) {
                if (!startWorld.covers(p.getX(), p.getZ())) {
                    outA++;
                }
            }
            LOGGER.info("[WaterPath] 诊断起点段:berth={} startGoal={} nodeA={} pathA={}航点 跨快照外={}航点",
                    srcBerth, startGoal, nodeA, pathA.size(), outA);

            // ---- 段3:终点真实区块驶向衔接点 B;nodeB = pathB 真实末点 ----
            prog.update(6, "终点港口寻路");
            BlockPos endGoal = CoastalExitResolver.oceanGoal(endWorld, tgtBerth, radius);
            List<BlockPos> pathB = runPathfinder(endWorld, tgtBerth, endGoal, segPolicy);
            BlockPos nodeB;
            if (pathB == null || pathB.size() < 2) {
                LOGGER.warn("[WaterPath] 两段:终点真实区块段失败,降级泊位直连中段 berth={}", tgtBerth);
                pathB = new ArrayList<>(List.of(new BlockPos(tgtBerth.getX(), midWorld.waterSurfaceY(tgtBerth.getX(), tgtBerth.getZ()), tgtBerth.getZ())));
                nodeB = pathB.get(0);
            } else {
                pathB = new ArrayList<>(pathB);
                nodeB = pathB.get(pathB.size() - 1);
            }
            // 诊断绕陆:终点段 goal/末点/有多少航点跨出 96 快照(跨出=用 NoiseChunk 判,WorldPainter 图判错)。
            int outB = 0;
            for (BlockPos p : pathB) {
                if (!endWorld.covers(p.getX(), p.getZ())) {
                    outB++;
                }
            }
            LOGGER.info("[WaterPath] 诊断终点段:berth={} endGoal={} nodeB={} pathB={}航点 跨快照外={}航点",
                    tgtBerth, endGoal, nodeB, pathB.size(), outB);

            // ---- 段2:中段 A→B(噪声粗路导向 + 走廊精寻;模式见 WaterMidMode)----
            List<BlockPos> pathMid = runMidTwoPhase(midWorld, nodeA, nodeB, mode, prog);
            if (pathMid == null || pathMid.size() < 2) {
                LOGGER.warn("[WaterPath] 三段:中段长距离失败 → NO_WATER_PATH");
                return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
            }
            LOGGER.info("[WaterPath] 诊断中段:期望 nodeA={} nodeB={} | 实际 pathMid首={} pathMid末={} ({}航点)",
                    nodeA, nodeB, pathMid.get(0), pathMid.get(pathMid.size() - 1), pathMid.size());
            prog.update(92, "拼接平滑");

            // ---- 拼接 pathA + pathMid + reverse(pathB) ----
            List<BlockPos> full = new ArrayList<>(pathA);
            int jointA = full.size() - 1;          // 起始段→中段 接头索引(去重前;dedup 后会微移,够近似)
            BlockPos jointAPos = full.get(jointA);  // 记坐标:去折改点数后用坐标重定位 joint
            appendDedup(full, pathMid);
            int jointB = full.size() - 1;          // 中段→尾段 接头索引
            BlockPos jointBPos = full.get(jointB);
            List<BlockPos> revB = new ArrayList<>(pathB);
            Collections.reverse(revB);
            appendDedup(full, revB);

            // 去回头折点:<b>只在两个拼接接头附近小窗口</b>去 zigzag(起终段 step=4 末点 vs 中段网格首点错位易成
            // 回折赘点)。<b>中段寻路出来的航点原样保留</b>——它们是真实/噪声寻路的合理弯路,整条去折会把大洋弯路
            // 磨成直线、把绕岛拐点删掉切回陆地(实测 89→31 退化成大直线 + 甩陆)。
            int before = full.size();
            full = dropBackfoldsNearJoints(full, new int[]{jointA, jointB}, JOINT_DEFOLD_WINDOW);
            // 去折改了点数:用接头坐标在新 full 里重定位 jointA/jointB(就近 index),供分段。
            jointA = nearestIndex(full, jointAPos);
            jointB = nearestIndex(full, jointBPos);
            LOGGER.info("[WaterPath] 两段拼接:起始{}+中段{}+尾段{} → 接头去折{}→{} 航点 (A={} B={}) jointA={} jointB={}",
                    pathA.size(), pathMid.size(), pathB.size(), before, full.size(), nodeA, nodeB, jointA, jointB);
            // 诊断「直线插大陆」:打印相邻航点距离 > 50 格的大跳段(直连/穿陆嫌疑),看它在哪两个点之间。
            for (int di = 1; di < full.size(); di++) {
                double gap = Math.sqrt(full.get(di - 1).distSqr(full.get(di)));
                if (gap > 50.0D) {
                    LOGGER.warn("[WaterPath] 诊断大跳段:航点[{}]{} → 航点[{}]{} 距离={}格(直线插大陆嫌疑)",
                            di - 1, full.get(di - 1), di, full.get(di), (int) gap);
                }
            }
            return full.size() < 2
                    ? WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH)
                    : WaterRouteResult.success(new PlannedPath(full, jointA, jointB));
        } catch (Throwable t) {
            LOGGER.error("[WaterPath] 三段编排异常", t);
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        }
    }

    /** 折线上离 target 最近的点的 index(欧氏 2D;去折后用接头坐标重定位 joint)。 */
    private static int nearestIndex(List<BlockPos> path, BlockPos target) {
        int best = 0;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            double dq = path.get(i).distSqr(target);
            if (dq < bestSq) {
                bestSq = dq;
                best = i;
            }
        }
        return best;
    }

    /**
     * 中段:<b>噪声粗路只当导向走廊</b> → 沿走廊用「真相采样器」精寻得不穿陆的真实地形路。
     * 真相源由 {@link WaterMidMode} 指令切换:
     * <ul>
     *   <li>{@link WaterMidMode#NOISE}(默认):走廊内用 NoiseChunk 精判(零加载、精确到方块、修穿陆),几秒出路。</li>
     *   <li>{@link WaterMidMode#REALCHUNK}:沿走廊<b>分批加载真实区块</b>(玩家运河/填海算数),慢、带进度条。
     *       缝隙(批与批之间)由 NoiseChunk 兜底,不切断走廊。</li>
     * </ul>
     * 走廊精寻失败 → 放宽走廊半径重试一次 → 仍失败退回噪声粗路 base(有路、可能穿陆,极少触发)。
     */
    private static List<BlockPos> runMidTwoPhase(ServerWaterRouteWorld midWorld, BlockPos a, BlockPos b,
                                                 WaterMidMode mode, ProgressSink prog) {
        ServerLevel level = midWorld.level();
        int seaY = midWorld.waterSurfaceY(0, 0);
        WaterMidMode m = mode == null ? WaterMidMode.current() : mode;
        if (level != null) {
            if (m == WaterMidMode.NBT) {
                List<BlockPos> real = runMidNbt(level, a, b, seaY, prog);
                if (real != null && real.size() >= 2) {
                    return real;
                }
                LOGGER.warn("[WaterPath] 中段 NBT 失败,退回 NOISE");
            } else if (m == WaterMidMode.HYBRID) {
                List<BlockPos> hyb = runMidHybrid(midWorld, level, a, b, seaY, prog);
                if (hyb != null && hyb.size() >= 2) {
                    return hyb;
                }
                LOGGER.warn("[WaterPath] 中段 HYBRID 失败,退回 NOISE");
            }
        }
        return runMidNoise(midWorld, a, b, seaY, prog);
    }

    /**
     * HYBRID(2026-06 重做):<b>粗路也走真实 NBT 水体连通性</b>当走廊中心线 → 真实方块精寻细化。
     *
     * <p><b>为什么不再用噪声粗寻当走廊中心</b>:旧版粗路用噪声({@link WaterRoutePolicy#longDistance()},
     * {@link ServerWaterRouteWorld} 噪声判水),但 WorldPainter「原版生成器+populate」地图噪声≠真实方块
     * ([[worldpainter_noise_mismatch]]),噪声粗路不知真实海峡在哪 → 走廊(中心线 ±radius)把真实海峡框在外 →
     * 真实精寻在走廊内找不到海峡 → 失败或退回穿陆噪声路。真实海峡偏离噪声走向越远越必现。
     *
     * <p><b>现在</b>:阶段一 {@link RealBlockWaterWorld#coarse}(无约束全开 + 泊位信任,真实 NBT 判水,onDemand
     * 前沿后台读)+ {@link WaterRoutePolicy#hybridCoarse()}(step=12 找窄海峡、halfWidth=0 不卡船宽)跑出
     * <b>真实</b>粗路(必经真实可航海峡;内陆湖与主航道不连通,A* 跨不过陆地,粗路天然不进湖)。阶段二
     * {@link RealBlockWaterWorld#corridor}(粗路 ±{@link #CORRIDOR_RADIUS} 走廊 + 3×3 软校验 + 泊位信任)
     * + {@link WaterRoutePolicy#nbtRefine()} 精寻细化。复用生产 NBT 单段同款两阶段链(见
     * {@code WaterAutoRouteService.runSingleSegmentNbt}),不再预加载整走廊(onDemand 自动按前沿读)。
     */
    private static List<BlockPos> runMidHybrid(ServerWaterRouteWorld midWorld, ServerLevel level,
                                               BlockPos a, BlockPos b, int seaY, ProgressSink prog) {
        RealBlockWaterMap map = new RealBlockWaterMap(level, seaY).enableOnDemand();
        // ---- 阶段一:真实 NBT 粗寻(无约束全开,onDemand 前沿后台读;step=12 找窄海峡,halfWidth=0 只求连通走向)----
        prog.update(10, "真实粗寻走向");
        List<BlockPos> guide = runPathfinder(RealBlockWaterWorld.coarse(map, seaY, a, b),
                a, b, WaterRoutePolicy.hybridCoarse());
        if (guide == null || guide.size() < 2) {
            return null; // 真实粗寻失败 → 上层退回 NOISE
        }
        LOGGER.info("[WaterPath] 中段 HYBRID 阶段一真实粗寻:{}航点", guide.size());
        // ---- 阶段二:沿真实粗路 ±走廊精寻细化(3×3 船宽 + 泊位信任)----
        int radius = CORRIDOR_RADIUS; // 48:真实粗路已在主航道/海峡上,精寻只需小幅细化,48 够;失败再放宽到 96。
        prog.update(80, "走廊精寻");
        List<BlockPos> refined = runPathfinder(
                RealBlockWaterWorld.corridor(map, guide, radius, seaY, 1, a, b),
                a, b, WaterRoutePolicy.nbtRefine());
        if (refined == null || refined.size() < 2) {
            refined = runPathfinder(
                    RealBlockWaterWorld.corridor(map, guide, radius * 2, seaY, 1, a, b),
                    a, b, WaterRoutePolicy.nbtRefine());
        }
        map.logLayerStats("hybrid");
        prog.update(90, "中段完成");
        if (refined != null && refined.size() >= 2) {
            LOGGER.info("[WaterPath] 中段 HYBRID 完成:真实粗导向{}→真实精路{}航点", guide.size(), refined.size());
            map.diagnosePathLandCrossings(refined);
            return refined;
        }
        return null;
    }

    /**
     * NOISE 模式(默认,原版噪声世界):噪声粗寻当导向走廊 → CorridorRealChunkWorld(NoiseChunk 精判 + 缝隙回退)精寻。
     * WorldPainter populate 地图下噪声真相错,此模式无意义(用 /sailboat watermid realchunk 切真实)。
     */
    private static List<BlockPos> runMidNoise(ServerWaterRouteWorld midWorld, BlockPos a, BlockPos b, int seaY, ProgressSink prog) {
        prog.update(10, "粗寻导向");
        List<BlockPos> coarse = runPathfinder(midWorld, a, b, WaterRoutePolicy.longDistance());
        if (coarse == null || coarse.size() < 2) {
            return null;
        }
        ServerLevel level = midWorld.level();
        NoiseChunkHeightSampler noiseFallback = level == null ? null : NoiseChunkHeightSampler.createOrNull(level);
        prog.update(30, "精寻");
        List<BlockPos> refined = refineNoiseCorridor(coarse, noiseFallback, seaY, a, b, CORRIDOR_RADIUS);
        if (refined == null || refined.size() < 2) {
            refined = refineNoiseCorridor(coarse, noiseFallback, seaY, a, b, CORRIDOR_RADIUS * 2);
        }
        prog.update(90, "中段完成");
        return (refined != null && refined.size() >= 2) ? refined : coarse;
    }

    private static List<BlockPos> refineNoiseCorridor(List<BlockPos> coarse, NoiseChunkHeightSampler fallback,
                                                      int seaY, BlockPos a, BlockPos b, int corridorRadius) {
        CorridorRealChunkWorld world = new CorridorRealChunkWorld(new ArrayList<>(), coarse, corridorRadius, fallback, seaY);
        return runPathfinder(world, a, b, WaterRoutePolicy.longDistanceRefine());
    }

    /**
     * REALCHUNK 模式(WorldPainter populate 地图):全程读真实方块(NBT 直读为主,force 兜底),tick 节流不卡服。
     * <ol>
     *   <li><b>粗寻带</b>:预加载 A→B 直线带真实区块 → 在带内真实粗寻(导向),失败放宽带宽×2 重试。</li>
     *   <li><b>精寻走廊</b>:预加载粗路走廊真实区块 → 在走廊内真实精寻,失败放宽走廊×2 重试。</li>
     * </ol>
     * 末端兜底返回 real-block coarse(已真实地形,最坏也不穿陆)。加载/搜索都在后台,加载经主线程乒乓节流。
     */
    private static List<BlockPos> runMidNbt(ServerLevel level, BlockPos a, BlockPos b, int seaY, ProgressSink prog) {
        RealBlockWaterMap map = new RealBlockWaterMap(level, seaY);
        RealBlockChunkLoader loader = new RealBlockChunkLoader(level, map);

        // 彻底脱噪声:沿 A→B 直线带<b>宽走廊</b>预加载真实区块,真实 A* 直接在宽走廊里绕大陆寻路。
        // 渐进放宽:先窄(快,路直时够);绕不开(中间横大陆/岛)逐级放宽半径补加载,给 A* 足够绕行空间。
        List<BlockPos> band = List.of(a, b);
        int[] radii = REAL_BAND_RADII; // {192, 384, 768}
        List<BlockPos> refined = null;
        for (int level2 = 0; level2 < radii.length; level2++) {
            int radius = radii[level2];
            int p0 = 15 + level2 * 25, p1 = 35 + level2 * 25; // 加载进度窗口随级递进
            prog.update(p0, "走廊加载(半径" + radius + ")");
            loader.loadAll(chunksAlongPolyline(band, radius), (d, t) ->
                    prog.update(scale(d, t, p0, p1), "走廊加载 " + d + "/" + t), MID_DEADLINE_MS);
            prog.update(p1, "真实寻路");
            refined = runPathfinder(new RealBlockWaterWorld(map, band, radius, seaY),
                    a, b, WaterRoutePolicy.longDistanceRefine());
            if (refined != null && refined.size() >= 2) {
                map.logLayerStats("半径" + radius);
                LOGGER.info("[WaterPath] 中段 NBT 真实寻路成功(走廊半径{}):{}航点", radius, refined.size());
                map.diagnosePathLandCrossings(refined); // 诊断:路径上 NBT 判水但真实是陆的穿陆点
                prog.update(90, "中段完成");
                return refined;
            }
            LOGGER.info("[WaterPath] 中段 NBT 真实寻路失败(走廊半径{}),放宽重试", radius);
        }
        map.logLayerStats("失败");
        LOGGER.warn("[WaterPath] 中段 NBT 真实寻路全部半径均失败,诊断起点/中点 NBT 判水:");
        map.diagnoseAround(a.getX(), a.getZ(), 4);
        map.diagnoseAround((a.getX() + b.getX()) / 2, (a.getZ() + b.getZ()) / 2, 4);
        return null; // 退回 runMidNoise
    }

    /** 进度线性缩放到 [from,to]。 */
    private static int scale(int done, int total, int from, int to) {
        if (total <= 0) {
            return to;
        }
        return from + (int) Math.round(Math.min(done, total) / (double) total * (to - from));
    }

    /** 折线 ± radius 覆盖的所有区块(去重)。沿每段按 8 格步插值取点,收集其 ±radius 区块范围。 */
    private static List<ChunkPos> chunksAlongPolyline(List<BlockPos> path, int radius) {
        java.util.LinkedHashSet<Long> set = new java.util.LinkedHashSet<>();
        if (path == null || path.isEmpty()) {
            return new ArrayList<>();
        }
        for (int i = 0; i < path.size(); i++) {
            BlockPos p = path.get(i);
            addChunkBox(set, p.getX(), p.getZ(), radius);
            if (i + 1 < path.size()) {
                BlockPos q = path.get(i + 1);
                double dist = Math.sqrt(p.distSqr(q));
                int steps = Math.max(1, (int) Math.ceil(dist / 8.0));
                for (int s = 1; s < steps; s++) {
                    double t = s / (double) steps;
                    int x = (int) Math.round(p.getX() + (q.getX() - p.getX()) * t);
                    int z = (int) Math.round(p.getZ() + (q.getZ() - p.getZ()) * t);
                    addChunkBox(set, x, z, radius);
                }
            }
        }
        List<ChunkPos> out = new ArrayList<>(set.size());
        for (long k : set) {
            out.add(new ChunkPos((int) (k >> 32), (int) (long) k));
        }
        return out;
    }

    /** 把以 (x,z) 为心、半径 radius 的方框覆盖的区块加入 set。 */
    private static void addChunkBox(java.util.Set<Long> set, int x, int z, int radius) {
        int cxLo = (x - radius) >> 4, cxHi = (x + radius) >> 4;
        int czLo = (z - radius) >> 4, czHi = (z + radius) >> 4;
        for (int cx = cxLo; cx <= cxHi; cx++) {
            for (int cz = czLo; cz <= czHi; cz++) {
                set.add((((long) cx) << 32) | (cz & 0xFFFFFFFFL));
            }
        }
    }

    /** 复用 WaterRoutePathfinder 跑一段(后台同步 do/while step 到完成或 wall-clock 超时)。 */
    private static List<BlockPos> runPathfinder(WaterRouteWorld world, BlockPos start, BlockPos goal, WaterRoutePolicy policy) {
        WaterRoutePathfinder pf = new WaterRoutePathfinder(world, start, goal, policy);
        long deadline = System.nanoTime() + (long) policy.timeoutTicks() * 50L * 1_000_000L;
        WaterRoutePathfinder.Status st;
        do {
            st = pf.step(policy.nodesPerTick(), policy.chunkLoadsPerTick());
        } while (st == WaterRoutePathfinder.Status.RUNNING && System.nanoTime() < deadline);
        return st == WaterRoutePathfinder.Status.SUCCESS ? pf.path() : null;
    }

    private static final double BACKFOLD_PERP_DIST = 6.0D; // 去折:中间点到 a→c 直线垂距 < 此值视作微绕/回折,删
    private static final int JOINT_DEFOLD_WINDOW = 8;       // 接头去折窗口(航点数):只在接头 ±此范围去 zigzag

    /**
     * <b>只在拼接接头附近小窗口</b>去回头折点(连续三点 a-b-c,b 到 a→c 垂距 < {@link #BACKFOLD_PERP_DIST} 视作
     * 回折赘点,删 b)。窗口外的中段寻路航点<b>原样保留</b>(不把寻路的合理弯路磨成直线/删绕岛拐点)。
     * @param jointIndices 接头在 path 里的索引(起始→中段、中段→尾段);只对这些索引 ±window 范围去折。
     */
    private static List<BlockPos> dropBackfoldsNearJoints(List<BlockPos> path, int[] jointIndices, int window) {
        if (path == null || path.size() <= 2) {
            return path;
        }
        // 标记哪些索引在接头窗口内(可去折);窗口外的点必留。
        boolean[] inWindow = new boolean[path.size()];
        for (int j : jointIndices) {
            int lo = Math.max(1, j - window), hi = Math.min(path.size() - 2, j + window);
            for (int k = lo; k <= hi; k++) {
                inWindow[k] = true;
            }
        }
        List<BlockPos> cur = new ArrayList<>(path);
        boolean[] win = inWindow;
        boolean changed = true;
        while (changed && cur.size() > 2) {
            changed = false;
            List<BlockPos> out = new ArrayList<>();
            boolean[] outWin = new boolean[cur.size()];
            out.add(cur.get(0));
            outWin[0] = win[0];
            int i = 1;
            while (i < cur.size() - 1) {
                BlockPos a = out.get(out.size() - 1);
                BlockPos b = cur.get(i);
                BlockPos c = cur.get(i + 1);
                if (win[i] && perpDistToLine(b, a, c) < BACKFOLD_PERP_DIST) {
                    changed = true; // b 在接头窗口内且是回折赘点 → 删
                    i++;
                } else {
                    outWin[out.size()] = win[i];
                    out.add(b);
                    i++;
                }
            }
            outWin[out.size()] = win[cur.size() - 1];
            out.add(cur.get(cur.size() - 1)); // 末点必留
            cur = out;
            win = outWin;
        }
        return cur;
    }

    /** 点 p 到直线 a→c 的垂距(2D,XZ)。a==c 时退化为 p 到 a 的距离。 */
    private static double perpDistToLine(BlockPos p, BlockPos a, BlockPos c) {
        double acx = c.getX() - a.getX(), acz = c.getZ() - a.getZ();
        double lenSq = acx * acx + acz * acz;
        if (lenSq < 1.0E-9D) {
            double dx = p.getX() - a.getX(), dz = p.getZ() - a.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
        double cross = Math.abs((p.getX() - a.getX()) * acz - (p.getZ() - a.getZ()) * acx);
        return cross / Math.sqrt(lenSq);
    }

    /** 把 src 接到 dst 尾部:若 src 首点与 dst 末点很近(<DEDUP_DIST)则跳过首点,避免接头重复/折角。 */
    private static void appendDedup(List<BlockPos> dst, List<BlockPos> src) {
        if (src == null || src.isEmpty()) {
            return;
        }
        int from = 0;
        if (!dst.isEmpty()) {
            BlockPos tail = dst.get(dst.size() - 1);
            BlockPos head = src.get(0);
            long dx = tail.getX() - head.getX(), dz = tail.getZ() - head.getZ();
            if (dx * dx + dz * dz < (long) DEDUP_DIST * DEDUP_DIST) {
                from = 1;
            }
        }
        for (int i = from; i < src.size(); i++) {
            dst.add(src.get(i));
        }
    }
}
