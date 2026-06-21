package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.route.PathSmoother;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.WaypointMeta;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class WaterAutoRouteService {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    private WaterAutoRouteService() {
    }

    public static WaterRouteResult<Void> canListCandidate(Level level, DockBlockEntity source, DockBlockEntity target) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        }
        if (source == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_SOURCE_DOCK);
        }
        if (target == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_TARGET_DOCK);
        }
        if (source instanceof PostStationBlockEntity || target instanceof PostStationBlockEntity) {
            return WaterRouteResult.failure(WaterRouteFailureReason.INVALID_TERMINAL_KIND);
        }
        WaterRouteResult<BerthPair> result = canListCandidate(
                snapshot(source),
                snapshot(target),
                new ServerWaterRouteWorld(serverLevel),
                relationLookup(level),
                WaterRoutePolicy.defaults());
        return result.successful() ? WaterRouteResult.success(null) : WaterRouteResult.failure(result.reason());
    }

    static WaterRouteResult<BerthPair> canListCandidate(DockSnapshot source,
                                                        DockSnapshot target,
                                                        DockBerthResolver.BerthWorld berthWorld,
                                                        WaterRoutePermissionService.RelationLookup relations,
                                                        WaterRoutePolicy policy) {
        if (source == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_SOURCE_DOCK);
        }
        if (target == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_TARGET_DOCK);
        }
        // 候选列表不再按直线距离硬性过滤：远距离码头也应可见、可选。寻路本身靠
        // maxExpandedNodes / maxChunkLoads / timeoutTicks 算力预算 + maxSearchRadius 外层护栏兜底，
        // 而非用距离把目标提前淘汰（先进寻路应能处理远航线，真无水路时由算力预算判失败）。
        WaterRouteResult<Void> permission = WaterRoutePermissionService.evaluate(
                source.access(),
                target.access(),
                relations);
        if (!permission.successful()) {
            return WaterRouteResult.failure(permission.reason());
        }
        WaterRouteResult<DockBerthResolver.DockBerth> sourceBerth = DockBerthResolver.resolve(
                berthWorld,
                source.zone(),
                policy,
                WaterRouteFailureReason.NO_SOURCE_BERTH);
        if (!sourceBerth.successful()) {
            return WaterRouteResult.failure(sourceBerth.reason());
        }
        WaterRouteResult<DockBerthResolver.DockBerth> targetBerth = DockBerthResolver.resolve(
                berthWorld,
                target.zone(),
                policy,
                WaterRouteFailureReason.NO_TARGET_BERTH);
        if (!targetBerth.successful()) {
            return WaterRouteResult.failure(targetBerth.reason());
        }
        // 连通性溯源(WaterConnectivityProbe.reachesOpenWater)已移除:采样从纯密度换成 getBaseHeight 后,
        // 它逐格 flood-fill 最多 4000 格 × footprint 9 列 × 2 次 getBaseHeight ≈ 7.2 万次 new NoiseChunk,
        // 对每个候选码头各跑两次 → 点「自动」列目的地直接卡服 25 秒。价值低(本意:过滤封闭水域/护城河里的
        // 码头),而真正建航线时 WaterRoutePathfinder 寻不通会以 NO_WATER_PATH 失败兜底,封闭水域码头不会
        // 误建出无效航线。故砍掉这个昂贵预探测,封闭水域码头允许进候选列表,由建航线寻路把关。
        return WaterRouteResult.success(new BerthPair(sourceBerth.value(), targetBerth.value()));
    }

    public static WaterRouteResult<Void> submitAutoRoute(ServerLevel level,
                                                         DockBlockEntity source,
                                                         DockBlockEntity target,
                                                         @Nullable ServerPlayer player) {
        return submitAutoRoute(level, source, target, player, WaterMidMode.current());
    }

    public static WaterRouteResult<Void> submitAutoRoute(ServerLevel level,
                                                         DockBlockEntity source,
                                                         DockBlockEntity target,
                                                         @Nullable ServerPlayer player,
                                                         WaterMidMode midMode) {
        if (level == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        }
        if (source == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_SOURCE_DOCK);
        }
        if (target == null) {
            return WaterRouteResult.failure(WaterRouteFailureReason.MISSING_TARGET_DOCK);
        }
        if (source instanceof PostStationBlockEntity || target instanceof PostStationBlockEntity) {
            return WaterRouteResult.failure(WaterRouteFailureReason.INVALID_TERMINAL_KIND);
        }
        DockSnapshot sourceSnapshot = snapshot(source);
        DockSnapshot targetSnapshot = snapshot(target);
        WaterRoutePolicy policy = WaterRoutePolicy.defaults();
        ServerWaterRouteWorld berthWorld = new ServerWaterRouteWorld(level);
        WaterRouteResult<BerthPair> candidate = canListCandidate(
                sourceSnapshot,
                targetSnapshot,
                berthWorld,
                relationLookup(level),
                policy);
        if (!candidate.successful()) {
            return WaterRouteResult.failure(candidate.reason());
        }
        BlockPos sourceBerth = blockPos(candidate.value().source().pos());
        BlockPos targetBerth = blockPos(candidate.value().target().pos());
        // 命名用城镇名(Auto-源城镇-目标城镇)。townId 解析不到则回退 dock 名。
        String sourceTownName = resolveTownName(level, sourceSnapshot);
        String targetTownName = resolveTownName(level, targetSnapshot);

        // 进度条(原版 BossBar,顶部血条):航线计算可能跑几十秒~几分钟(REALCHUNK 模式),让玩家实时看到进度。
        String srcLabel = sourceTownName.isBlank() ? source.getDockName() : sourceTownName;
        String dstLabel = targetTownName.isBlank() ? target.getDockName() : targetTownName;
        WaterRouteProgressBar bar = new WaterRouteProgressBar(level.getServer(), player, srcLabel, dstLabel);
        bar.show();

        // metas 旁路:后台 Supplier 计算平滑点的同时算出每点 segment/origin,经此 AtomicReference 传到主线程
        // applyCompletedRoute(同一次请求内,后台写主线程读,用 Atomic 保可见性)。最终仍塞进 RouteDefinition 持久化
        // ——不是绕过正式结构的缓存,只是把 metas 从后台搬到主线程的通道(task 链路泛型保持 List<BlockPos> 不变)。
        java.util.concurrent.atomic.AtomicReference<List<WaypointMeta>> metasOut = new java.util.concurrent.atomic.AtomicReference<>(List.of());
        WaterRouteTask.CompletionHandler onComplete = result -> {
            if (result != null && result.successful()) {
                bar.done();
            } else {
                bar.fail("无水路");
            }
            applyCompletedRoute(level, source, target, sourceSnapshot, targetSnapshot, sourceTownName, targetTownName, result, metasOut.get(), player);
        };

        // ---- NBT 单段双向 A*(默认,WorldPainter/已存盘地图):源泊位直接搜到目标泊位,全程真实方块判定 ----
        // A* 节点扩展时用 3×3 船宽校验(占地内无陆才可航)→ 从源头根除搁浅;区块按需 NBT 后台读 + 滑动窗口卸载
        // (前沿过的区块淘汰,常驻有界)。不再拆三段、不噪声粗寻、不样条过冲。NOISE/HYBRID 仍走下方三段编排(原版/生成式地图)。
        if (midMode == WaterMidMode.NBT) {
            int seaY = level.getSeaLevel();
            int boatHalfWidth = Math.max(1, policy.boatHalfWidth()); // 1 = 船 3×3
            java.util.function.Supplier<WaterRouteResult<java.util.List<BlockPos>>> singleNbt =
                    () -> runSingleSegmentNbt(level, sourceBerth, targetBerth, seaY, boatHalfWidth, policy, metasOut, bar);
            WaterRouteTask nbtTask = new WaterRouteTask(
                    level.dimension().location().toString(),
                    source.getBlockPos(), target.getBlockPos(), sourceBerth, targetBerth,
                    playerName(player), policy, singleNbt, onComplete);
            return WaterRouteTaskService.global().submit(nbtTask).successful()
                    ? WaterRouteResult.success(null)
                    : WaterRouteResult.failure(WaterRouteFailureReason.ALREADY_PENDING);
        }

        // ---- 三段式贴岸(NOISE/HYBRID,原版/生成式地图):主线程预读两端港口真实区块快照,后台串行跑三段。----
        // 真实区块加载只在此主线程小范围临时(port 附近 ~7x7 区块,读完即释放);后台三段全用快照/噪声(线程安全)。
        WaterRouteTask task;
        try {
            int radius = RealChunkRouteWorld.DEFAULT_RADIUS;
            int threshold = RealChunkRouteWorld.OFFSHORE_THRESHOLD;
            RealChunkRouteWorld startWorld = RealChunkRouteWorld.load(level, sourceBerth, radius);
            RealChunkRouteWorld endWorld = RealChunkRouteWorld.load(level, targetBerth, radius);
            ServerWaterRouteWorld midWorld = new ServerWaterRouteWorld(level);
            // 三段编排 + smooth 全在后台线程跑(消除主线程卡服):smooth 纯数学,线程安全。
            // 2026-06:中段已改「真实地形走廊精寻」(NoiseChunk 精判 / 真实区块分批加载),三段全程真实地形判定、
            // 不再穿陆 → 去掉末端 RealWaterVerifier(它原是噪声中段的兜底,现中段不穿陆,末端再绕反而可能把好航点
            // 推偏)。只保留 size<2 的空路拒绝。船自救仍用 RealWaterVerifier(它走噪声 rescue,需要兜底)。
            int seaY = level.getSeaLevel();
            java.util.function.Supplier<WaterRouteResult<java.util.List<BlockPos>>> threeSeg =
                    () -> {
                        WaterRouteResult<ThreeSegmentPlanner.PlannedPath> raw =
                                ThreeSegmentPlanner.runSerialPlanned(startWorld, endWorld, midWorld, sourceBerth, targetBerth, radius, threshold,
                                        midMode, (percent, stage) -> bar.update(percent, stage));
                        if (!raw.successful()) {
                            return WaterRouteResult.failure(raw.reason());
                        }
                        ThreeSegmentPlanner.PlannedPath planned = raw.value();
                        // 平滑同时拿每点 origin(原始拐点/样条插值);segment 按平滑点到原折线最近投影 index vs jointA/jointB 判定。
                        PathSmoother.SmoothWithOrigin sm = PathSmoother.smooth2DWithOrigin(planned.path(), seaY, 2.0D);
                        java.util.List<BlockPos> smoothed = sm.points();
                        if (smoothed == null || smoothed.size() < 2) {
                            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
                        }
                        // 统一航点 NBT 校验(所有模式通用):读每个航点底下真实方块判水,删非水点 + 局部绕行重连,迭代到全水。
                        // NOISE/HYBRID 走噪声/NoiseChunk 判障可能漏陆 → 末端真实 NBT 兜底,保证最终全程可航水域。
                        int verifyHalfWidth = Math.max(1, policy.boatHalfWidth());
                        RealBlockWaterMap verifyMap = new RealBlockWaterMap(level, seaY).enableOnDemand();
                        java.util.List<BlockPos> verified = WaterRouteNbtVerifier.verify(verifyMap, smoothed, verifyHalfWidth);
                        verifyMap.logLayerStats("三段末端NBT校验");
                        if (verified == null || verified.size() < 2) {
                            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
                        }
                        boolean verifyChanged = verified.size() != smoothed.size();
                        smoothed = verified;
                        // 改了点数时 origins 与新 smoothed 错位 → 传 null(buildMetas 全 RAW 兜底)。
                        java.util.List<Byte> origins = verifyChanged ? null : sm.origins();
                        metasOut.set(buildMetas(smoothed, origins, planned.path(), planned.jointA(), planned.jointB()));
                        return WaterRouteResult.success(smoothed);
                    };
            task = new WaterRouteTask(
                    level.dimension().location().toString(),
                    source.getBlockPos(), target.getBlockPos(), sourceBerth, targetBerth,
                    playerName(player), policy, threeSeg, onComplete);
        } catch (Throwable t) {
            // 真实区块快照加载失败 → 回退现有单段噪声寻路(berth→berth)。也走 Supplier 形式,
            // 统一在后台 smooth+verify(主线程不背重活)。
            LOGGER.warn("[WaterPath] 真实区块快照加载失败,回退单段寻路", t);
            int seaY = level.getSeaLevel();
            java.util.function.Supplier<WaterRouteResult<java.util.List<BlockPos>>> singleSeg =
                    () -> {
                        ServerWaterRouteWorld routeWorld = new ServerWaterRouteWorld(level);
                        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(routeWorld, sourceBerth, targetBerth, policy);
                        long deadline = System.nanoTime() + (long) policy.timeoutTicks() * 50L * 1_000_000L;
                        WaterRoutePathfinder.Status st;
                        do {
                            st = pathfinder.step(policy.nodesPerTick(), policy.chunkLoadsPerTick());
                        } while (st == WaterRoutePathfinder.Status.RUNNING && System.nanoTime() < deadline);
                        if (st != WaterRoutePathfinder.Status.SUCCESS) {
                            return WaterRouteResult.failure(
                                    st == WaterRoutePathfinder.Status.FAILED ? pathfinder.failureReason() : WaterRouteFailureReason.TIMEOUT);
                        }
                        java.util.List<BlockPos> smoothed = PathSmoother.smooth2D(pathfinder.path(), seaY, 2.0D);
                        java.util.List<BlockPos> verified = RealWaterVerifier.verifyAndRepair(level, smoothed);
                        if (verified == null || verified.size() < 2) {
                            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
                        }
                        return WaterRouteResult.success(verified);
                    };
            task = new WaterRouteTask(
                    level.dimension().location().toString(),
                    source.getBlockPos(), target.getBlockPos(), sourceBerth, targetBerth,
                    playerName(player), policy, singleSeg, onComplete);
        }
        return WaterRouteTaskService.global().submit(task).successful()
                ? WaterRouteResult.success(null)
                : WaterRouteResult.failure(WaterRouteFailureReason.ALREADY_PENDING);
    }

    /**
     * <b>NBT 两阶段双向 A*</b>(后台线程):源泊位→目标泊位,全程真实方块判定,区块按需 NBT 后台读 + 滑动窗口卸载。
     * <ol>
     *   <li><b>阶段一·粗走廊</b>:大 step 粗网格 + 中心格判水(halfWidth=0,不卡船宽,只求大致走向)→ 出粗导向折线。
     *       (解单段无约束全开发散搜不到:粗阶段省节点跑出走向,把搜索收敛。)</li>
     *   <li><b>阶段二·走廊精寻</b>:用粗路作约束走廊(±半径外 blocked)+ step=8 + 3×3 船宽校验 → 出精细航线。
     *       搜索空间被走廊收死 → 双向 A* 前沿不发散。</li>
     * </ol>
     * 出来的折线贝塞尔平滑 → 平滑后 3×3 搁浅校验 → <b>统一航点 NBT 校验</b>(删非水点 + 局部绕行重连,迭代到全水)。
     * 失败 → NO_WATER_PATH。
     */
    private static final int NBT_CORRIDOR_RADIUS = 96; // 阶段二走廊半径(粗路中心线 ±此格内可搜)

    private static WaterRouteResult<List<BlockPos>> runSingleSegmentNbt(
            ServerLevel level, BlockPos sourceBerth, BlockPos targetBerth, int seaY, int boatHalfWidth,
            WaterRoutePolicy policy,
            java.util.concurrent.atomic.AtomicReference<List<WaypointMeta>> metasOut,
            WaterRouteProgressBar bar) {
        RealBlockWaterMap map = new RealBlockWaterMap(level, seaY).enableOnDemand();

        // ---- 阶段一:粗走廊(大 step + 中心判水,跑大致走向)----
        bar.update(5, "粗寻走向");
        RealBlockWaterWorld coarseWorld = RealBlockWaterWorld.coarse(map, seaY, sourceBerth, targetBerth);
        WaterRoutePolicy coarsePolicy = WaterRoutePolicy.nbtCoarse();
        WaterRoutePathfinder coarsePf = runToCompletion(coarseWorld, sourceBerth, targetBerth, coarsePolicy);
        if (coarsePf.status() != WaterRoutePathfinder.Status.SUCCESS) {
            map.logLayerStats("NBT粗走廊");
            LOGGER.warn("[WaterPath] NBT 阶段一粗走廊失败 st={} 节点={} 原因={}",
                    coarsePf.status(), coarsePf.expandedNodes(), coarsePf.failureReason());
            return WaterRouteResult.failure(
                    coarsePf.status() == WaterRoutePathfinder.Status.FAILED ? coarsePf.failureReason() : WaterRouteFailureReason.TIMEOUT);
        }
        List<BlockPos> coarse = coarsePf.path();
        LOGGER.info("[WaterPath] NBT 阶段一粗走廊成功 节点={} 粗航点={}", coarsePf.expandedNodes(), coarse.size());

        // ---- 阶段二:走廊精寻(粗路 ±半径约束 + step=8 + 3×3 船宽)----
        bar.update(40, "走廊精寻");
        RealBlockWaterWorld fineWorld = RealBlockWaterWorld.corridor(
                map, coarse, NBT_CORRIDOR_RADIUS, seaY, boatHalfWidth, sourceBerth, targetBerth);
        WaterRoutePolicy finePolicy = WaterRoutePolicy.nbtRefine();
        WaterRoutePathfinder finePf = runToCompletion(fineWorld, sourceBerth, targetBerth, finePolicy);
        map.logLayerStats("NBT走廊精寻");
        if (finePf.status() != WaterRoutePathfinder.Status.SUCCESS) {
            LOGGER.warn("[WaterPath] NBT 阶段二走廊精寻失败 st={} 节点={} 原因={}",
                    finePf.status(), finePf.expandedNodes(), finePf.failureReason());
            return WaterRouteResult.failure(
                    finePf.status() == WaterRoutePathfinder.Status.FAILED ? finePf.failureReason() : WaterRouteFailureReason.TIMEOUT);
        }
        List<BlockPos> raw = finePf.path();
        LOGGER.info("[WaterPath] NBT 阶段二走廊精寻成功 节点={} 航点={}", finePf.expandedNodes(), raw == null ? 0 : raw.size());

        // 2026-06 择优兜底(保证 refine 永不比 coarse 差):代价归一化已治本,但仍加一道安全网——比较 refine 折线与
        // coarse 折线的质量(撞陆段数优先,同撞陆数比总长),refine 劣于 coarse 才退回 coarse。两者都过同一 verify+平滑。
        if (raw != null && raw.size() >= 2) {
            long[] qFine = pathQuality(map, raw, boatHalfWidth);
            long[] qCoarse = pathQuality(map, coarse, boatHalfWidth);
            boolean fineWorse = qFine[0] > qCoarse[0] || (qFine[0] == qCoarse[0] && qFine[1] > qCoarse[1]);
            if (fineWorse) {
                LOGGER.info("[WaterPath] NBT 择优:refine(撞陆段={} 长={}) 劣于 coarse(撞陆段={} 长={}) → 退回 coarse",
                        qFine[0], qFine[1], qCoarse[0], qCoarse[1]);
                raw = coarse;
            }
        }

        bar.update(80, "拼接平滑");
        // 2026-06 撞陆感知平滑(治本:所有撞陆都是平滑过冲造成):前-折线离岸预留、中-过冲段逐级收紧重平滑、
        // 后-逐点抽陆回推。取代裸 PathSmoother.smooth2DWithOrigin(只纯几何,过冲甩岸靠 verify 事后绕)。
        List<BlockPos> smoothed = WaterPathSmoother.smooth(map, raw, seaY, 2.0D, boatHalfWidth);
        if (smoothed == null || smoothed.size() < 2) {
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        }
        // 统一航点 NBT 校验:读每个航点底下真实方块判水,删非水点 + 局部绕行重连,迭代到全水(平滑已治本,这里是最终兜底)。
        bar.update(90, "航点NBT校验");
        List<BlockPos> verified = WaterRouteNbtVerifier.verify(map, smoothed, boatHalfWidth);
        if (verified == null || verified.size() < 2) {
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        }
        smoothed = verified;
        // WaterPathSmoother 已改点数(回推/重采),origins 与最终点必错位 → 传 null(buildMetas 全 RAW 兜底),metas 仅 debug 着色。
        metasOut.set(buildMetas(smoothed, null, raw, -1, smoothed.size()));
        bar.update(95, "完成");
        return WaterRouteResult.success(smoothed);
    }

    /**
     * 折线质量度量(给 refine/coarse 择优):{@code [撞陆段数, 总长度(格,取整)]}。撞陆段 = 该段 segmentHullClear 不过
     * (2×2 船宽逐格 supercover 校验有陆)。比较时撞陆段数优先(越少越好),同撞陆数比总长(越短越好)。
     */
    // public:调试工具(RouteDebugService)复用同一质量判据,保证 debug 的 refine/coarse 择优与实际 100% 同口径。
    public static long[] pathQuality(RealBlockWaterMap map, List<BlockPos> path, int halfWidth) {
        long landSegs = 0;
        long totalLen = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1);
            BlockPos b = path.get(i);
            totalLen += Math.round(Math.sqrt(a.distSqr(b)));
            if (!map.segmentHullClear(a, b, halfWidth)) {
                landSegs++;
            }
        }
        return new long[]{landSegs, totalLen};
    }

    /** 跑一条双向 A* 到完成(SUCCESS/FAILED/超时),返回 pathfinder 供取 path/status/failureReason。 */
    private static WaterRoutePathfinder runToCompletion(WaterRouteWorld world, BlockPos start, BlockPos goal,
                                                        WaterRoutePolicy policy) {
        WaterRoutePathfinder pf = new WaterRoutePathfinder(world, start, goal, policy);
        long deadline = System.nanoTime() + (long) policy.timeoutTicks() * 50L * 1_000_000L;
        WaterRoutePathfinder.Status st;
        do {
            st = pf.step(policy.nodesPerTick(), policy.chunkLoadsPerTick());
        } while (st == WaterRoutePathfinder.Status.RUNNING && System.nanoTime() < deadline);
        return pf;
    }

    private static void applyCompletedRoute(ServerLevel level,
                                            DockBlockEntity source,
                                            DockBlockEntity target,
                                            DockSnapshot sourceSnapshot,
                                            DockSnapshot targetSnapshot,
                                            String sourceTownName,
                                            String targetTownName,
                                            WaterRouteResult<List<BlockPos>> result,
                                            List<WaypointMeta> waypointMetas,
                                            @Nullable ServerPlayer player) {
        if (!result.successful()) {
            if (player != null) {
                player.sendSystemMessage(messageFor(result.reason()));
            }
            return;
        }
        // 2026-06:smooth+verify 已移到后台 Supplier(消除主线程卡服),result.value() 即最终航点。
        // 这里只剩 setRoutes + 发消息(轻,主线程安全)。
        List<BlockPos> verified = result.value();
        if (verified == null || verified.size() < 2) {
            // 穿陆且绕不开 → 不落地无效航线(船会卡死),报无水路。
            if (player != null) {
                player.sendSystemMessage(messageFor(WaterRouteFailureReason.NO_WATER_PATH));
            }
            return;
        }
        RouteDefinition route = routeDefinitionFromPath(
                sourceSnapshot,
                targetSnapshot,
                sourceTownName,
                targetTownName,
                verified,
                waypointMetas,
                playerName(player),
                player == null ? "" : player.getUUID().toString(),
                System.currentTimeMillis());
        List<RouteDefinition> routes = upsertAutoRoute(source.getRoutesForMap(), route);
        int selectedIndex = Math.max(0, routes.indexOf(route));
        source.setRoutes(routes, selectedIndex);

        // 2026-06:航线也存到目的地码头,且是反向(B→A)——这样从目的地出发能直接用回程线路。
        // 反向 = 航点倒序 + metas 倒序(与航点对齐) + 起终 dock/town 对调。
        List<BlockPos> reversedPath = new ArrayList<>(verified);
        java.util.Collections.reverse(reversedPath);
        List<WaypointMeta> reversedMetas = new ArrayList<>(waypointMetas == null ? List.of() : waypointMetas);
        java.util.Collections.reverse(reversedMetas);
        RouteDefinition reverseRoute = routeDefinitionFromPath(
                targetSnapshot,   // 反向起点 = 原目的地
                sourceSnapshot,   // 反向终点 = 原出发
                targetTownName,
                sourceTownName,
                reversedPath,
                reversedMetas,
                playerName(player),
                player == null ? "" : player.getUUID().toString(),
                System.currentTimeMillis());
        List<RouteDefinition> targetRoutes = upsertAutoRoute(target.getRoutesForMap(), reverseRoute);
        int reverseIndex = Math.max(0, targetRoutes.indexOf(reverseRoute));
        target.setRoutes(targetRoutes, reverseIndex);

        if (player != null) {
            player.sendSystemMessage(Component.translatable(
                    "message.sailboatmod.auto_route.water.created",
                    target.getDockName(),
                    Integer.toString((int) Math.round(route.routeLengthMeters()))));
        }
    }

    /**
     * 把平滑后的航点 + origins(每点来源) + 原折线 jointA/jointB 合成 metas:
     * <ul>
     *   <li>segment:平滑点投影到原折线最近 index,≤jointA→START,≤jointB→MID,否则→END;</li>
     *   <li>origin:直接取 PathSmoother 输出的 origins[i](原始拐点 / 样条插值)。</li>
     * </ul>
     * 与 smoothed 等长。origins 缺位时按 RAW 兜底。
     */
    static List<WaypointMeta> buildMetas(List<BlockPos> smoothed, List<Byte> origins, List<BlockPos> original, int jointA, int jointB) {
        if (smoothed == null || smoothed.isEmpty()) {
            return List.of();
        }
        List<WaypointMeta> metas = new ArrayList<>(smoothed.size());
        for (int i = 0; i < smoothed.size(); i++) {
            int idx = nearestIndexOnPolyline(original, smoothed.get(i));
            byte seg = idx <= jointA ? WaypointMeta.SEGMENT_START
                    : (idx <= jointB ? WaypointMeta.SEGMENT_MID : WaypointMeta.SEGMENT_END);
            byte origin = origins != null && i < origins.size() ? origins.get(i) : WaypointMeta.ORIGIN_RAW;
            metas.add(new WaypointMeta(seg, origin));
        }
        return metas;
    }

    /** 折线上离 target 最近的点 index(欧氏 2D,XZ;给 segment 分段)。 */
    private static int nearestIndexOnPolyline(List<BlockPos> polyline, BlockPos target) {
        if (polyline == null || polyline.isEmpty()) {
            return 0;
        }
        int best = 0;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < polyline.size(); i++) {
            BlockPos p = polyline.get(i);
            double dx = p.getX() - target.getX();
            double dz = p.getZ() - target.getZ();
            double dq = dx * dx + dz * dz;
            if (dq < bestSq) {
                bestSq = dq;
                best = i;
            }
        }
        return best;
    }

    static RouteDefinition routeDefinitionFromPath(DockSnapshot source,
                                                   DockSnapshot target,
                                                   String sourceTownName,
                                                   String targetTownName,
                                                   List<BlockPos> path,
                                                   List<WaypointMeta> waypointMetas,
                                                   String authorName,
                                                   String authorUuid,
                                                   long createdAtEpochMillis) {
        // 不再 simplify:传入的 path 已由 PathSmoother 平滑+弧长重采样成等距密集航点,
        // 再 simplify 会把加密点抽回拐点、平滑白做。直接逐点构造 waypoints。
        List<BlockPos> waypointsSource = path == null ? List.of() : path;
        List<Vec3> waypoints = new ArrayList<>(waypointsSource.size());
        double routeLength = 0.0D;
        Vec3 previous = null;
        for (BlockPos pos : waypointsSource) {
            Vec3 waypoint = new Vec3(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            if (previous != null) {
                routeLength += previous.distanceTo(waypoint);
            }
            waypoints.add(waypoint);
            previous = waypoint;
        }
        String targetName = target == null ? "Dock" : target.name();
        String srcTown = sourceTownName == null || sourceTownName.isBlank()
                ? (source == null ? "Dock" : source.name()) : sourceTownName;
        String dstTown = targetTownName == null || targetTownName.isBlank() ? targetName : targetTownName;
        // metas 只在与 waypoints 等长时带入(三段主路径成立);单段 fallback / 长度不符 → 留空(debug 降级,不报错)。
        List<WaypointMeta> metas = waypointMetas != null && waypointMetas.size() == waypoints.size()
                ? waypointMetas : List.of();
        return new RouteDefinition(
                "Auto-" + srcTown + "-" + dstTown,
                waypoints,
                authorName,
                authorUuid,
                createdAtEpochMillis,
                routeLength,
                source == null ? "" : source.name(),
                targetName,
                metas);
    }

    static List<RouteDefinition> upsertAutoRouteForTest(List<RouteDefinition> existingRoutes, RouteDefinition route) {
        return upsertAutoRoute(existingRoutes, route);
    }

    private static List<RouteDefinition> upsertAutoRoute(List<RouteDefinition> existingRoutes, RouteDefinition route) {
        if (route == null) {
            return existingRoutes == null ? List.of() : List.copyOf(existingRoutes);
        }
        List<RouteDefinition> routes = new ArrayList<>();
        if (existingRoutes != null) {
            for (RouteDefinition existing : existingRoutes) {
                if (isSameGeneratedWaterRoute(existing, route)) {
                    continue;
                }
                routes.add(existing);
            }
        }
        routes.add(route);
        return List.copyOf(routes);
    }

    private static boolean isSameGeneratedWaterRoute(RouteDefinition existing, RouteDefinition replacement) {
        if (existing == null || replacement == null) {
            return false;
        }
        return isGeneratedWaterRoute(existing)
                && safeEquals(existing.startDockName(), replacement.startDockName())
                && safeEquals(existing.endDockName(), replacement.endDockName());
    }

    private static boolean isGeneratedWaterRoute(RouteDefinition route) {
        // 兼容旧前缀("Water Auto: ")与新前缀("Auto-"),保证去重对历史航线仍生效。
        return route != null && route.name() != null
                && (route.name().startsWith("Auto-") || route.name().startsWith("Water Auto: "));
    }

    /** townId → 城镇名;解析不到返回空串(由调用方回退 dock 名)。 */
    private static String resolveTownName(ServerLevel level, DockSnapshot snapshot) {
        if (level == null || snapshot == null || snapshot.townId() == null || snapshot.townId().isBlank()) {
            return "";
        }
        var town = NationSavedData.get(level).getTown(snapshot.townId());
        return town == null || town.name() == null ? "" : town.name();
    }

    private static boolean safeEquals(String left, String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.equals(safeRight);
    }

    public static Component messageFor(WaterRouteFailureReason reason) {
        return Component.translatable(messageKey(reason));
    }

    private static String messageKey(WaterRouteFailureReason reason) {
        return switch (reason == null ? WaterRouteFailureReason.NO_WATER_PATH : reason) {
            case MISSING_TOWN -> "message.sailboatmod.auto_route.water.failed.missing_town";
            case MISSING_NATION -> "message.sailboatmod.auto_route.water.failed.missing_nation";
            case NO_PERMISSION -> "message.sailboatmod.auto_route.water.failed.no_permission";
            case NO_SOURCE_BERTH -> "message.sailboatmod.auto_route.water.failed.no_source_berth";
            case NO_TARGET_BERTH -> "message.sailboatmod.auto_route.water.failed.no_target_berth";
            case RANGE_EXCEEDED -> "message.sailboatmod.auto_route.water.failed.range_exceeded";
            case CHUNK_BUDGET_EXCEEDED -> "message.sailboatmod.auto_route.water.failed.chunk_budget_exceeded";
            case NODE_BUDGET_EXCEEDED -> "message.sailboatmod.auto_route.water.failed.node_budget_exceeded";
            case TIMEOUT -> "message.sailboatmod.auto_route.water.failed.timeout";
            case ALREADY_PENDING -> "message.sailboatmod.auto_route.water.failed.already_pending";
            case NO_WATER_PATH -> "message.sailboatmod.auto_route.water.failed.no_water_path";
            case SOURCE_NOT_OPEN_WATER -> "message.sailboatmod.auto_route.water.failed.source_not_open_water";
            case TARGET_NOT_OPEN_WATER -> "message.sailboatmod.auto_route.water.failed.target_not_open_water";
            default -> "message.sailboatmod.auto_route.water.failed.generic";
        };
    }

    private static DockSnapshot snapshot(DockBlockEntity dock) {
        return new DockSnapshot(
                dock.getBlockPos(),
                dock.getDockName(),
                dock.getTownId(),
                dock.getNationId(),
                new DockBerthResolver.DockZone(
                        dock.getBlockPos(),
                        dock.getZoneMinX(),
                        dock.getZoneMaxX(),
                        dock.getZoneMinZ(),
                        dock.getZoneMaxZ()));
    }

    private static WaterRoutePermissionService.RelationLookup relationLookup(Level level) {
        return (left, right) -> {
            NationDiplomacyRecord relation = NationSavedData.get(level).getDiplomacy(left, right);
            return relation == null ? "" : relation.statusId();
        };
    }

    private static String playerName(@Nullable ServerPlayer player) {
        return player == null ? "System" : player.getGameProfile().getName();
    }

    private static BlockPos blockPos(Vec3 pos) {
        return new BlockPos(Mth.floor(pos.x), Mth.floor(pos.y), Mth.floor(pos.z));
    }

    private static List<BlockPos> simplify(List<BlockPos> input) {
        if (input == null || input.size() <= 2) {
            return input == null ? List.of() : List.copyOf(input);
        }
        List<BlockPos> out = new ArrayList<>();
        out.add(input.get(0));
        for (int i = 1; i < input.size() - 1; i++) {
            BlockPos previous = out.get(out.size() - 1);
            BlockPos current = input.get(i);
            BlockPos next = input.get(i + 1);
            int dx1 = Integer.compare(current.getX() - previous.getX(), 0);
            int dy1 = Integer.compare(current.getY() - previous.getY(), 0);
            int dz1 = Integer.compare(current.getZ() - previous.getZ(), 0);
            int dx2 = Integer.compare(next.getX() - current.getX(), 0);
            int dy2 = Integer.compare(next.getY() - current.getY(), 0);
            int dz2 = Integer.compare(next.getZ() - current.getZ(), 0);
            if (dx1 != dx2 || dy1 != dy2 || dz1 != dz2) {
                out.add(current);
            }
        }
        out.add(input.get(input.size() - 1));
        return List.copyOf(out);
    }

    public record DockSnapshot(BlockPos pos,
                               String name,
                               String townId,
                               String nationId,
                               DockBerthResolver.DockZone zone) {
        public DockSnapshot {
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
            name = name == null || name.isBlank() ? "Dock" : name.trim();
            townId = townId == null ? "" : townId.trim();
            nationId = nationId == null ? "" : nationId.trim();
            zone = zone == null ? new DockBerthResolver.DockZone(pos, -DockBlockEntity.ZONE_HALF_X, DockBlockEntity.ZONE_HALF_X, -DockBlockEntity.ZONE_HALF_Z, DockBlockEntity.ZONE_HALF_Z) : zone;
        }

        WaterRoutePermissionService.DockAccess access() {
            return new WaterRoutePermissionService.DockAccess(townId, nationId);
        }
    }

    public record BerthPair(DockBerthResolver.DockBerth source, DockBerthResolver.DockBerth target) {
    }
}
