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
        return submitAutoRoute(level, source, target, player, midMode, RoutePlanningPreference.OPTIMAL);
    }

    /** 建立(存进码头的)航线。preference 只影响 Tellus/NBT 路径是否复用航路网络。 */
    public static WaterRouteResult<Void> submitAutoRoute(ServerLevel level,
                                                         DockBlockEntity source,
                                                         DockBlockEntity target,
                                                         @Nullable ServerPlayer player,
                                                         WaterMidMode midMode,
                                                         RoutePlanningPreference preference) {
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
        // Resolve Tellus endpoints inside the cancellable worker task, not on the server thread.
        if (com.monpai.sailboatmod.route.terrain.TellusWorlds.isTellusLevel(level) || midMode == WaterMidMode.NBT) {
            return submitTellusAutoRoute(level, source, target, sourceSnapshot, targetSnapshot, player, true, null,
                    preference == null ? RoutePlanningPreference.OPTIMAL : preference);
        }
        // Tellus 地球世界:未生成区块用 Tellus 地形预测(NBT 优先),一律走单段 NBT,忽略 UI 选的模式。
        final com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor =
                com.monpai.sailboatmod.route.terrain.TellusTerrain.forLevel(level).orElse(null);
        final WaterMidMode mode = effectiveMode(midMode, predictor != null);
        // 2026-06 泊位解析改【真实方块判水】(与 debug 工具同口径):原 ServerWaterRouteWorld 用噪声判水,
        // WorldPainter/populate 地图下噪声≠真实方块,会解析到「噪声以为水、真实是陆」的坏泊位 → HYBRID 从陆地点
        // 出发、真实精寻找不到出水路 → refined==null → HYBRID 失败退回 NOISE → 出穿陆坏线。改真实判水后泊位必落
        // 真实可航水格,HYBRID 从真实水点出发即成功。berthWorld 仅用于本次 canListCandidate,不影响列目的地探测。
        int berthSeaY = level.getSeaLevel();
        RealBlockWaterMap berthProbeMap = new RealBlockWaterMap(level, berthSeaY).enableOnDemand();
        RealBlockWaterWorld berthWorld = RealBlockWaterWorld.coarse(berthProbeMap, berthSeaY, null, null);
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
        WaterRouteProgressBar bar = new WaterRouteProgressBar(level.getServer(), player, srcLabel, dstLabel, sourceSnapshot.pos(), targetSnapshot.pos());
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
        if (mode == WaterMidMode.NBT) {
            int seaY = level.getSeaLevel();
            int boatHalfWidth = Math.max(1, policy.boatHalfWidth()); // 1 = 船 3×3
            java.util.function.Supplier<WaterRouteResult<java.util.List<BlockPos>>> singleNbt =
                    () -> runSingleSegmentNbt(level, sourceBerth, targetBerth, seaY, boatHalfWidth, policy, metasOut, bar,
                            predictor, predictor == null ? null : com.monpai.sailboatmod.route.terrain.PredictionBudget.sixtySeconds());
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
                        // 校验前主动预加载航线沿途真实 NBT 区块(后台 Worker 线程,readOffThread 不卡主线程):
                        // 否则 verify 边查边按需读超时 → fallback 判全陆 → 把真实是水的海峡航点成片误删(中段覆盖真空根因)。
                        // 预读用宽松超时 4000ms,尽量读到真实数据填缓存,verify 时全部命中。
                        long preloadStart = System.currentTimeMillis();
                        verifyMap.preloadAlongPath(smoothed, verifyHalfWidth, 4000L);
                        LOGGER.info("[WaterPath] 航线区块预加载耗时={}ms", System.currentTimeMillis() - preloadStart);
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

    /** Destination jobs share the same verified network, solver, cancellation and final checks.
     * This entry is called only after carrier/market authorization on the server thread.
     */
    public static WaterRouteResult<Void> submitDestinationRoute(ServerLevel level, DockBlockEntity source,
            DockBlockEntity target, @Nullable ServerPlayer player,
            java.util.function.Consumer<WaterRouteResult<RouteDefinition>> completion) {
        return submitDestinationRoute(level, source, target, player, completion, RoutePlanningPreference.REUSE_FIRST);
    }

    public static WaterRouteResult<Void> submitDestinationRoute(ServerLevel level, DockBlockEntity source,
            DockBlockEntity target, @Nullable ServerPlayer player,
            java.util.function.Consumer<WaterRouteResult<RouteDefinition>> completion, RoutePlanningPreference preference) {
        if (level == null || !PortDockSnapshot.isPort(source) || !PortDockSnapshot.isPort(target))
            return WaterRouteResult.failure(WaterRouteFailureReason.INVALID_TERMINAL_KIND);
        return submitTellusAutoRoute(level, source, target, snapshot(source), snapshot(target), player, false, completion,
                preference == null ? RoutePlanningPreference.REUSE_FIRST : preference);
    }

    /** Tellus: real-first berth validation (including unsaved edits), then full background routing. */
    private static WaterRouteResult<Void> submitTellusAutoRoute(ServerLevel level, DockBlockEntity source,
            DockBlockEntity target, DockSnapshot sourceSnapshot, DockSnapshot targetSnapshot,
            @Nullable ServerPlayer player, boolean requireManagement,
            java.util.function.Consumer<WaterRouteResult<RouteDefinition>> completion,
            RoutePlanningPreference preference) {
        WaterRouteResult<Void> permission = WaterRouteCandidateService.canListCandidate(level, source, target);
        if (!permission.successful()) return permission;
        var predictor = com.monpai.sailboatmod.route.terrain.TellusTerrain.forLevel(level).orElse(null);
        // The main thread snapshots immutable geometry, never builds or searches the graph.
        WaterRouteNetworkSavedData infrastructure = WaterRouteNetworkSavedData.get(level);
        infrastructure.importDock(source);
        infrastructure.importDock(target);
        // 最优:不复用航路网络(建立基础设施时避免把别条航线的绕行固化进新航线);复用优先:先拼既有航段。
        List<WaterRouteNetwork.Route> network = preference.reuseNetwork() ? infrastructure.snapshot() : List.of();
        var sourceZone = PortTerrainProbe.zone(sourceSnapshot.zone());
        var targetZone = PortTerrainProbe.zone(targetSnapshot.zone());
        var probe = PortTerrainProbe.capture(level, List.of(sourceZone, targetZone), predictor);
        String sourceTown = resolveTownName(level, sourceSnapshot);
        String targetTown = resolveTownName(level, targetSnapshot);
        int seaY = level.getSeaLevel();
        WaterRoutePolicy policy = WaterRoutePolicy.defaults();
        WaterRouteProgressBar bar = new WaterRouteProgressBar(level.getServer(), player, sourceSnapshot.name(), targetSnapshot.name(),
                sourceSnapshot.pos(), targetSnapshot.pos());
        var metas = new java.util.concurrent.atomic.AtomicReference<List<WaypointMeta>>(List.of());
        java.util.function.Supplier<WaterRouteResult<List<BlockPos>>> search = () -> {
            var ports = resolveNbtPorts(probe, sourceZone, targetZone, bar);
            if (!ports.successful()) return WaterRouteResult.failure(ports.reason());
            BlockPos a = ports.value().source(), b = ports.value().target();
            WaterRouteResult<List<BlockPos>> reused = tryReuseNetwork(level, a, b, seaY,
                    Math.max(1, policy.boatHalfWidth()), policy, metas, bar, predictor, probe.realSnapshots(), network);
            if (reused != null && reused.successful()) return reused;
            WaterRouteCancellation.check();
            return runSingleSegmentNbt(level, a, b, seaY, Math.max(1, policy.boatHalfWidth()),
                    policy, metas, bar, predictor,
                    com.monpai.sailboatmod.route.terrain.PredictionBudget.sixtySeconds(), probe.realSnapshots());
        };
        WaterRouteTask task = new WaterRouteTask(level.dimension().location().toString(), sourceSnapshot.pos(),
                targetSnapshot.pos(), sourceSnapshot.pos(), targetSnapshot.pos(), playerName(player), policy, search, result -> {
            // Do not publish a late result after a dock/zone/ownership change.
            WaterRouteResult<List<BlockPos>> accepted = result;
            if (result.successful() && ((requireManagement && player != null && !source.canManageDock(player)) || source.isRemoved() || target.isRemoved()
                    || !snapshot(source).equals(sourceSnapshot) || !snapshot(target).equals(targetSnapshot)
                    || !WaterRouteCandidateService.canListCandidate(level, source, target).successful())) {
                accepted = WaterRouteResult.failure(WaterRouteFailureReason.TERRAIN_NOT_READY);
            }
            if (accepted.successful()) bar.done();
            else bar.fail(accepted.reason() == WaterRouteFailureReason.TERRAIN_NOT_READY ? "地形待验证" : "航线未建立");
            RouteDefinition created = applyCompletedRoute(level, source, target, sourceSnapshot, targetSnapshot, sourceTown, targetTown,
                    accepted, metas.get(), player);
            if (completion != null) completion.accept(created != null ? WaterRouteResult.success(created)
                    : WaterRouteResult.failure(accepted.successful() ? WaterRouteFailureReason.NO_WATER_PATH : accepted.reason()));
        });
        WaterRouteResult<WaterRouteTask> submitted = WaterRouteTaskService.global().submit(task);
        if (!submitted.successful()) return WaterRouteResult.failure(submitted.reason());
        var session = RouteUiSessions.forRoute(player, sourceSnapshot.pos(), targetSnapshot.pos());
        if (session != null) session.attach(task);
        bar.show();
        return WaterRouteResult.success(null);
    }

    /** A diagnostic calculation only: no route registration, network import, dock update, or save. */
    public record ReadOnlyNbtPlan(@Nullable BlockPos sourceBerth, @Nullable BlockPos targetBerth,
                                  WaterRouteResult<List<BlockPos>> result, List<WaypointMeta> metas) { }

    private record ResolvedNbtPorts(BlockPos source, BlockPos target) { }
    private record ReadOnlyNbtTerrain(com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor,
                                      PortTerrainProbe probe, int seaY) { }
    private static final long READ_ONLY_NBT_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(120);

    /** Worker-thread debug entry. Port validation and the solver are shared with OPTIMAL production requests. */
    public static ReadOnlyNbtPlan planReadOnlyNbt(ServerLevel level, DockBerthResolver.DockZone source,
                                                 DockBerthResolver.DockZone target) {
        if (level == null || source == null || target == null)
            return new ReadOnlyNbtPlan(null, null, WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH), List.of());
        var server = level.getServer();
        if (server.isStopped())
            return new ReadOnlyNbtPlan(null, null, WaterRouteResult.failure(WaterRouteFailureReason.CANCELLED), List.of());
        if (server.isSameThread()) throw new IllegalStateException("Read-only route planning requires a worker");
        return boundedReadOnlyNbt(null, null, deadline -> {
            var sourceZone = PortTerrainProbe.zone(source);
            var targetZone = PortTerrainProbe.zone(target);
            // MinecraftServer.submit can execute inline after shutdown. This mailbox is drained
            // only by server ticks, and cancelled entries never read live world metadata later.
            var capture = PortAsyncRuntime.onMain(server, () -> {
                if (server.isStopped() || System.nanoTime() >= deadline)
                    throw new java.util.concurrent.CancellationException("Read-only port capture expired");
                var predictor = com.monpai.sailboatmod.route.terrain.TellusTerrain.forLevel(level).orElse(null);
                return new ReadOnlyNbtTerrain(predictor,
                        PortTerrainProbe.capture(level, List.of(sourceZone, targetZone), predictor), level.getSeaLevel());
            });
            ReadOnlyNbtTerrain terrain;
            try {
                terrain = awaitReadOnlyCapture(capture, deadline);
            } catch (InterruptedException stopped) {
                capture.cancel(false);
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException("Read-only route interrupted");
            } catch (java.util.concurrent.TimeoutException expired) {
                capture.cancel(false);
                return new ReadOnlyNbtPlan(null, null, WaterRouteResult.failure(WaterRouteFailureReason.TIMEOUT), List.of());
            } catch (java.util.concurrent.ExecutionException failed) {
                if (server.isStopped())
                    throw new java.util.concurrent.CancellationException("Read-only route server stopped");
                throw new java.util.concurrent.CompletionException(failed.getCause());
            }
            WaterRouteCancellation.check();
            var ports = resolveNbtPorts(terrain.probe(), sourceZone, targetZone,
                    new WaterRouteProgressBar(null, null, "", ""));
            if (!ports.successful())
                return new ReadOnlyNbtPlan(null, null, WaterRouteResult.failure(ports.reason()), List.of());
            return runReadOnlyNbt(level, ports.value().source(), ports.value().target(), terrain.seaY(),
                    terrain.predictor(), terrain.probe().realSnapshots());
        });
    }

    private static <T> T awaitReadOnlyCapture(java.util.concurrent.CompletableFuture<T> capture, long deadline)
            throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
        try {
            while (true) {
                WaterRouteCancellation.check();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new java.util.concurrent.TimeoutException("Read-only port capture expired");
                try {
                    return capture.get(Math.min(remaining, java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50)),
                            java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException waitingForTick) {
                    if (System.nanoTime() >= deadline) throw waitingForTick;
                }
            }
        } finally {
            if (!capture.isDone()) capture.cancel(false);
        }
    }

    /** Same production solver for callers that already own validated berths and authoritative port snapshots. */
    public static ReadOnlyNbtPlan planReadOnlyNbt(@Nullable ServerLevel level, BlockPos sourceBerth, BlockPos targetBerth,
                                                 int seaY,
                                                 @Nullable com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor,
                                                 java.util.Map<Long, NbtChunkWaterReader.ChunkColumns> portSnapshots) {
        if (level != null && level.getServer().isSameThread())
            throw new IllegalStateException("Read-only route planning requires a worker");
        return boundedReadOnlyNbt(sourceBerth, targetBerth,
                deadline -> runReadOnlyNbt(level, sourceBerth, targetBerth, seaY, predictor, portSnapshots));
    }

    private static ReadOnlyNbtPlan boundedReadOnlyNbt(@Nullable BlockPos sourceBerth, @Nullable BlockPos targetBerth,
            java.util.function.LongFunction<ReadOnlyNbtPlan> work) {
        long deadline = System.nanoTime() + READ_ONLY_NBT_TIMEOUT_NANOS;
        try {
            return WaterRouteCancellation.run(() -> System.nanoTime() >= deadline, () -> work.apply(deadline));
        } catch (java.util.concurrent.CancellationException stopped) {
            var reason = System.nanoTime() >= deadline ? WaterRouteFailureReason.TIMEOUT : WaterRouteFailureReason.CANCELLED;
            return new ReadOnlyNbtPlan(sourceBerth, targetBerth, WaterRouteResult.failure(reason), List.of());
        }
    }

    private static ReadOnlyNbtPlan runReadOnlyNbt(@Nullable ServerLevel level, BlockPos sourceBerth, BlockPos targetBerth,
            int seaY, @Nullable com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor,
            java.util.Map<Long, NbtChunkWaterReader.ChunkColumns> portSnapshots) {
        var metas = new java.util.concurrent.atomic.AtomicReference<List<WaypointMeta>>(List.of());
        WaterRoutePolicy policy = WaterRoutePolicy.defaults();
        var result = runSingleSegmentNbt(level, sourceBerth, targetBerth, seaY, Math.max(1, policy.boatHalfWidth()),
                policy, metas, new WaterRouteProgressBar(null, null, "", ""), predictor,
                com.monpai.sailboatmod.route.terrain.PredictionBudget.sixtySeconds(), portSnapshots);
        return new ReadOnlyNbtPlan(sourceBerth, targetBerth, result, List.copyOf(metas.get()));
    }

    private static WaterRouteResult<ResolvedNbtPorts> resolveNbtPorts(PortTerrainProbe probe,
            PortBerthValidator.Zone sourceZone, PortBerthValidator.Zone targetZone, WaterRouteProgressBar bar) {
        probe.start(true, 10_000, WaterRouteCancellation::cancelled);
        var start = PortBerthValidator.Result.pending();
        var end = PortBerthValidator.Result.pending();
        do {
            WaterRouteCancellation.check();
            bar.update(1, "验证泊位 / 等待地形");
            if (!start.usable()) start = probe.resolve(sourceZone);
            if (start.verdict() == PortBerthValidator.Verdict.INVALID)
                return WaterRouteResult.failure(WaterRouteFailureReason.NO_SOURCE_BERTH);
            if (!end.usable()) end = probe.resolve(targetZone);
            if (end.verdict() == PortBerthValidator.Verdict.INVALID)
                return WaterRouteResult.failure(WaterRouteFailureReason.NO_TARGET_BERTH);
            if (start.usable() && end.usable())
                return WaterRouteResult.success(new ResolvedNbtPorts(
                        new BlockPos(start.berth().x(), start.berth().y(), start.berth().z()),
                        new BlockPos(end.berth().x(), end.berth().y(), end.berth().z())));
            probe.retryPending();
            java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
        } while (!probe.expired());
        return WaterRouteResult.failure(WaterRouteFailureReason.TERRAIN_NOT_READY);
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
    // 阶段一粗寻渐进 step 梯度:大 step 快(路宽时几十节点出走向),失败逐级缩小直到踩进 1-8 格窄海峡。
    // 4 是最小档(踩 4-8 格海峡);更小(1-3 格)海峡极罕见且船未必过得去,不再降以免节点爆炸。
    public static final int[] COARSE_STEP_LADDER = {24, 12, 8, 4};

    /** Reuse geometry, not a dock's selected route index. Gaps still run the real solver.
     * A rejected cache never publishes a route; the caller falls back to ordinary planning.
     */
    static WaterRouteResult<List<BlockPos>> tryReuseNetwork(@Nullable ServerLevel level, BlockPos source, BlockPos target,
            int seaY, int halfWidth, WaterRoutePolicy policy,
            java.util.concurrent.atomic.AtomicReference<List<WaypointMeta>> metas, WaterRouteProgressBar bar,
            @Nullable com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor,
            java.util.Map<Long,NbtChunkWaterReader.ChunkColumns> snapshots, List<WaterRouteNetwork.Route> network) {
        var planned=WaterRouteNetwork.plan(network,new Vec3(source.getX()+.5,source.getY(),source.getZ()+.5),
                new Vec3(target.getX()+.5,target.getY(),target.getZ()+.5));
        if(planned.isEmpty())return null;
        var plan=planned.get();
        bar.update(3,"复用航路 "+Math.round(plan.reusedLength())+"格 · 待补算 "+Math.round(plan.newLength())+"格");
        LOGGER.info("[WaterPath] 航路网络复用 routes={} reused={} gaps={} legs={}",plan.routeIds().size(),
                Math.round(plan.reusedLength()),Math.round(plan.newLength()),plan.legs().size());
        // 复用段整段重验:中段区块走陆地遮罩预测(秒级),不再为上万个远方区块逐个跑生成器预测(常超 60s 回退全量规划)。
        var validationPredictor=farRoutePredictor(farRouteTerrain(level),predictor,source,target,seaY);
        RealBlockWaterMap map=new RealBlockWaterMap(level,seaY).enableOnDemand().retryUnknownPrediction()
                .withPredictor(validationPredictor,com.monpai.sailboatmod.route.terrain.PredictionBudget.sixtySeconds());
        snapshots.forEach((k,v)->map.seedChunk(net.minecraft.world.level.ChunkPos.getX(k),net.minecraft.world.level.ChunkPos.getZ(k),v));
        try(RouteTerrainSource terrain=level==null?null:new RouteTerrainSource(level,validationPredictor,
                ()->com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers.forLevel(level).orElse(null))) {
            if(terrain!=null)map.withAsyncTerrain(terrain);
            long reuseStart=System.nanoTime();
            List<BlockPos> joined=new ArrayList<>();List<Boolean> joinedReused=new ArrayList<>();int completed=0;
            for(var leg:plan.legs()) {
                WaterRouteCancellation.check();
                List<BlockPos> points=new ArrayList<>();
                for(Vec3 v:leg.points()) {BlockPos b=blockPos(v);if(points.isEmpty()||!points.getLast().equals(b))points.add(b);}
                if(points.size()<2) {if(!points.isEmpty()&&joined.isEmpty())joined.add(points.getFirst());continue;}
                if(leg.reused()) {
                    // Check every existing edge against this world now. Retain completed edge results
                    // while cold neighbours are loading, rather than serially restarting at edge zero.
                    boolean[] checked=new boolean[points.size()-1];int remaining=checked.length;
                    long deadline=System.nanoTime()+60_000_000_000L;
                    while(remaining>0 && System.nanoTime()<deadline) {
                        WaterRouteCancellation.check();int probes=0;
                        for(int i=0;i<checked.length&&probes<128;i++)if(!checked[i]) {
                            probes++;int before=map.terrainMisses();boolean clear=map.segmentHullClear(points.get(i),points.get(i+1),halfWidth);
                            if(map.terrainMisses()!=before)continue;
                            if(!clear) {LOGGER.info("[WaterPath] 缓存航段已改变，退回完整规划");return null;}
                            checked[i]=true;remaining--;
                        }
                        if(remaining>0){if(terrain!=null)terrain.awaitProgress(20);else java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);}
                    }
                    if(remaining>0)return null;
                } else {
                    var fresh=runSingleSegmentNbt(level,points.getFirst(),points.getLast(),seaY,halfWidth,policy,
                            new java.util.concurrent.atomic.AtomicReference<>(List.of()),bar,predictor,
                            com.monpai.sailboatmod.route.terrain.PredictionBudget.sixtySeconds(),snapshots);
                    if(!fresh.successful())return null;
                    points=new ArrayList<>(fresh.value());
                }
                if(!joined.isEmpty()&&!joined.getLast().equals(points.getFirst()))return null;
                for(BlockPos p:points){
                    if(!joined.isEmpty()&&joined.getLast().equals(p))continue;
                    if(!joined.isEmpty())joinedReused.add(leg.reused());
                    joined.add(p);
                }
                bar.update(5+80*++completed/plan.legs().size(),"航路复用 / 补段 "+completed+"/"+plan.legs().size());
            }
            if(joined.size()<2||!joined.getFirst().equals(source)||!joined.getLast().equals(target))return null;
            LOGGER.info("[WaterPath] 航路网络复用 校验+补段耗时={}ms",(System.nanoTime()-reuseStart)/1_000_000L);
            // 复用段已在建线时后处理过、刚才也整段重验过;新补段由 runSingleSegmentNbt 处理过。只把段与段的接口
            // 两侧各 REUSE_JOINT_BLOCKS 格重新后处理(消除接驳急弯),不对整条航线或补段重跑。
            List<Boolean> legOf=new ArrayList<>(joinedReused), split=new ArrayList<>();
            java.util.Set<BlockPos> original=new java.util.HashSet<>(joined);
            joined=splitLongEdges(joined,legOf,split,REUSE_JOINT_BLOCKS);
            List<Boolean> keep=jointWindows(joined,split,REUSE_JOINT_BLOCKS);
            List<BlockPos> result;
            try { result=finishGuidedRoute(map,joined,keep,halfWidth,terrain==null?null:terrain::awaitProgress,bar::update,false); }
            catch (IllegalArgumentException invalidCachedGeometry) { return null; }
            if(result==null||result.size()<2)return null;
            result=dropSplitPoints(result,original);
            metas.set(buildMetas(result,null,result,-1,result.size()));
            return WaterRouteResult.success(result);
        }
    }

    private static WaterRouteResult<List<BlockPos>> runSingleSegmentNbt(
            ServerLevel level, BlockPos sourceBerth, BlockPos targetBerth, int seaY, int boatHalfWidth,
            WaterRoutePolicy policy,
            java.util.concurrent.atomic.AtomicReference<List<WaypointMeta>> metasOut,
            WaterRouteProgressBar bar,
            @Nullable com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor,
            @Nullable com.monpai.sailboatmod.route.terrain.PredictionBudget budget) {
        return runSingleSegmentNbt(level, sourceBerth, targetBerth, seaY, boatHalfWidth, policy, metasOut,
                bar, predictor, budget, java.util.Map.of());
    }

    static WaterRouteResult<List<BlockPos>> runSingleSegmentNbt(
            ServerLevel level, BlockPos sourceBerth, BlockPos targetBerth, int seaY, int boatHalfWidth,
            WaterRoutePolicy policy, java.util.concurrent.atomic.AtomicReference<List<WaypointMeta>> metasOut,
            WaterRouteProgressBar bar, @Nullable com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor,
            @Nullable com.monpai.sailboatmod.route.terrain.PredictionBudget budget,
            java.util.Map<Long, NbtChunkWaterReader.ChunkColumns> portSnapshots) {
        WaterRouteCancellation.check();
        // Every Tellus route, not just >8192 blocks, uses native LOD guidance + strict detailed terrain.
        if (useHierarchicalTerrain(predictor != null,
                Math.hypot((double) targetBerth.getX() - sourceBerth.getX(), (double) targetBerth.getZ() - sourceBerth.getZ()))) {
            return runHierarchicalNbt(level, sourceBerth, targetBerth, seaY, boatHalfWidth, metasOut, bar, predictor, budget, portSnapshots);
        }
        if (predictor != null) {
            prefetchTellusCorridor(predictor, budget, sourceBerth, targetBerth, bar);
        }
        RealBlockWaterMap map = new RealBlockWaterMap(level, seaY).enableOnDemand().withPredictor(predictor, budget);
        portSnapshots.forEach((key, columns) -> map.seedChunk(net.minecraft.world.level.ChunkPos.getX(key),
                net.minecraft.world.level.ChunkPos.getZ(key), columns));

        // ---- 阶段一:粗走廊(渐进 step + 中心判水,跑大致走向)----
        // 海峡可能只有 1-8 格宽,大 step 网格点踩不进去 → 起终水域在粗网格上不连通 → 失败。先大 step 快试(路宽时省节点),
        // 失败逐级缩小 step(24→12→8→4)直到网格点能踩进窄海峡。([[water_route_strait_progressive_step]])
        bar.update(5, "粗寻走向");
        List<BlockPos> coarse = null;
        WaterRouteFailureReason coarseFail = WaterRouteFailureReason.NO_WATER_PATH;
        for (int step : COARSE_STEP_LADDER) {
            RealBlockWaterWorld coarseWorld = RealBlockWaterWorld.coarse(map, seaY, sourceBerth, targetBerth);
            WaterRoutePathfinder coarsePf = runToCompletion(coarseWorld, sourceBerth, targetBerth,
                    WaterRoutePolicy.nbtCoarseStep(step));
            if (coarsePf.status() == WaterRoutePathfinder.Status.SUCCESS) {
                coarse = coarsePf.path();
                LOGGER.info("[WaterPath] NBT 阶段一粗走廊成功(step={}) 节点={} 粗航点={}",
                        step, coarsePf.expandedNodes(), coarse.size());
                break;
            }
            coarseFail = coarsePf.status() == WaterRoutePathfinder.Status.FAILED
                    ? coarsePf.failureReason() : WaterRouteFailureReason.TIMEOUT;
            LOGGER.info("[WaterPath] NBT 阶段一粗走廊失败(step={}) st={} 节点={} 原因={},缩小 step 重试",
                    step, coarsePf.status(), coarsePf.expandedNodes(), coarsePf.failureReason());
        }
        if (coarse == null) {
            map.logLayerStats("NBT粗走廊");
            LOGGER.warn("[WaterPath] NBT 阶段一粗走廊全 step 均失败,原因={}", coarseFail);
            return WaterRouteResult.failure(coarseFail);
        }

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
        // 2026-06 去回折(治窄水道出口三角岔路):双向 A* 前向饿死→相遇点偏置→窄→宽喇叭口拼出三角赘路。平滑前先删
        // 开阔水里的回折赘点(只删「删后 a→c 仍 2×2 全水」者,绕陆拐点删了会穿陆故保留,不切陆)。
        raw = WaterRouteNbtVerifier.dropOpenWaterBackfolds(map, raw, boatHalfWidth);
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
     * Tellus 远航专用地形:陆地遮罩取样(全局网格与开阔航段判定)+ 内置全球海路概览(搜索启发,零下载)。
     * 非 Tellus 世界为 null,沿用原有的 LOD/真实地形粗寻。
     */
    record FarRouteTerrain(com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler landMask,
                           @Nullable GlobalWaterGridPlanner.SeaDistanceSource seaDistances) { }

    /** Tests only: supplies the far-route terrain when there is no ServerLevel. */
    static volatile java.util.function.Supplier<FarRouteTerrain> farTerrainOverride;

    @Nullable
    static FarRouteTerrain farRouteTerrain(@Nullable ServerLevel level) {
        if (level == null) {
            java.util.function.Supplier<FarRouteTerrain> override = farTerrainOverride;
            return override == null ? null : override.get();
        }
        var mask = com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers.landMaskForLevel(level).orElse(null);
        if (mask == null) return null;
        java.util.OptionalDouble scale = com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers.worldScale(level);
        WorldWaterOverview overview = scale.isPresent() ? WorldWaterOverview.get() : null;
        return new FarRouteTerrain(mask, overview == null ? null : overview.seaDistances(scale.getAsDouble()));
    }

    /** 航线中段区块只读陆地遮罩;两端港口(1024 格内)仍走原预测器。 */
    @Nullable
    static com.monpai.sailboatmod.route.terrain.TerrainPredictor farRoutePredictor(
            @Nullable FarRouteTerrain far, @Nullable com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor,
            BlockPos source, BlockPos target, int seaY) {
        if (far == null || predictor == null) return predictor;
        return new com.monpai.sailboatmod.route.terrain.SurfaceSamplerPredictor(far.landMask(), predictor, source, target,
                com.monpai.sailboatmod.route.terrain.SurfaceSamplerPredictor.DEFAULT_NEAR_BLOCKS, seaY);
    }

    /** 同步的陆地遮罩世界(全局网格搜索在路线 worker 线程上运行;遮罩瓦片由 Tellus 缓存,取不到当陆)。 */
    static WaterRouteWorld landMaskWorld(com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler mask, int seaY) {
        return new WaterRouteWorld() {
            @Override public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
                WaterRouteCancellation.check();
                return mask.sample(x, z, Math.max(2, policy.stepSize()))
                        .map(s -> s.water() ? WaterColumn.passable(new BlockPos(x, seaY, z), 0) : WaterColumn.blocked())
                        .orElse(null);
            }
            @Override public boolean canLoadMoreChunks(int requested) { return false; }
            @Override public int consumedChunkLoads() { return 0; }
        };
    }

    /** Far routes use global guidance and independent detailed/verified stages, never a larger fine A* radius. */
    private static WaterRouteResult<List<BlockPos>> runHierarchicalNbt(
            @Nullable ServerLevel level, BlockPos source, BlockPos target, int seaY, int boatHalfWidth,
            java.util.concurrent.atomic.AtomicReference<List<WaypointMeta>> metasOut, WaterRouteProgressBar bar,
            @Nullable com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor,
            @Nullable com.monpai.sailboatmod.route.terrain.PredictionBudget budget,
            java.util.Map<Long, NbtChunkWaterReader.ChunkColumns> portSnapshots) {
        FarRouteTerrain far = farRouteTerrain(level);
        predictor = farRoutePredictor(far, predictor, source, target, seaY);
        RealBlockWaterMap map = new RealBlockWaterMap(level, seaY).enableOnDemand().retryUnknownPrediction().withPredictor(predictor, budget);
        portSnapshots.forEach((key, columns) -> map.seedChunk(net.minecraft.world.level.ChunkPos.getX(key),
                net.minecraft.world.level.ChunkPos.getZ(key), columns));
        WaterRouteWorld detailed = RealBlockWaterWorld.streaming(map, seaY, boatHalfWidth, source, target);
        com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler surface = level == null ? null
                : com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers.forLevel(level).orElse(null);
        // All production data fetches retain their futures across A* slices. No read or native
        // sampling (including prefetch startup) runs on the route's polling/search thread.
        try (RouteTerrainSource terrain = level == null ? null : new RouteTerrainSource(level, predictor,
                () -> com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers.forLevel(level).orElse(null))) {
            if (terrain != null) map.withAsyncTerrain(terrain);
            WaterRouteWorld coarse;
            if (surface != null) {
                coarse = CoarseWaterRouteWorld.realFirst((cx, cz) -> portSnapshots.containsKey(net.minecraft.world.level.ChunkPos.asLong(cx, cz))
                                ? NbtChunkWaterReader.ReadResult.ready(portSnapshots.get(net.minecraft.world.level.ChunkPos.asLong(cx, cz)))
                                : (terrain == null ? NbtChunkWaterReader.readForRouting(level, cx, cz, 200) : terrain.read(cx, cz)),
                        (x, z, spacing) -> terrain == null ? surface.sample(x, z, spacing)
                                : terrain.surface(x, z, spacing), seaY);
            } else if (predictor != null) {
                // Also supports injected predictors and existing NBT worlds without a cheap native surface API.
                coarse = RealBlockWaterWorld.streaming(map, seaY, 0, source, target);
            } else {
                coarse = CoarseWaterRouteWorld.realFirst((cx, cz) -> portSnapshots.containsKey(net.minecraft.world.level.ChunkPos.asLong(cx, cz))
                                ? NbtChunkWaterReader.ReadResult.ready(portSnapshots.get(net.minecraft.world.level.ChunkPos.asLong(cx, cz)))
                                : (terrain == null ? NbtChunkWaterReader.readForRouting(level, cx, cz, 200) : terrain.read(cx, cz)), null, seaY);
            }
            HierarchicalWaterRoutePlanner planner;
            if (far != null) {
                // Tellus:全局水域网格(陆地遮罩 + 内置海路概览启发)直接给出导向与开阔直航段,取代逐点 LOD 粗寻。
                bar.update(4, "全局水域走向");
                long guidanceStart = System.nanoTime();
                GlobalWaterGridPlanner.Guidance guidance = GlobalWaterGridPlanner.plan(
                        landMaskWorld(far.landMask(), source.getY()), far.seaDistances(), source, target, source.getY());
                LOGGER.info("[WaterPath] 远航全局导向 {} cell={} 扩展={} 航段={} 耗时={}ms", guidance.successful() ? "成功" : guidance.failure(),
                        guidance.cellSize(), guidance.expandedNodes(), guidance.legs().size(), (System.nanoTime() - guidanceStart) / 1_000_000L);
                if (!guidance.successful()) return WaterRouteResult.failure(guidance.failure());
                planner = HierarchicalWaterRoutePlanner.withGuidance(detailed, source, target,
                        WaterRoutePolicy.nbtRefine(), raw -> finishDetailedSegment(map, raw, seaY, boatHalfWidth), guidance.legs());
            } else {
                planner = new HierarchicalWaterRoutePlanner(coarse, detailed, source, target,
                        WaterRoutePolicy.nbtRefine(), raw -> finishDetailedSegment(map, raw, seaY, boatHalfWidth));
            }
            int previousCompleted = -1;
            long nextProgress = 0, nextDiagnostic = 0;
            LOGGER.info("[WaterPath] 地形分层规划: coarse={} detail=FULL_NBT/GENERATOR 严格船宽=2×2 距离={}格",
                    surface != null ? "TELLUS_NATIVE_LOD" : "REAL_FIRST_COLUMNS",
                    Math.round(Math.hypot((double) target.getX() - source.getX(), (double) target.getZ() - source.getZ())));
            HierarchicalWaterRoutePlanner.Status previousStatus = null;
            while (true) {
                WaterRouteCancellation.check();
                HierarchicalWaterRoutePlanner.Status status = planner.step(64);
                HierarchicalWaterRoutePlanner.Progress progress = planner.progress();
                if (progress.completedSegments() != previousCompleted || status != previousStatus || System.nanoTime() >= nextProgress) {
                    int percent = progress.totalSegments() == 0 ? 5 : 10 + 80 * progress.completedSegments() / progress.totalSegments();
                    String stage = status == HierarchicalWaterRoutePlanner.Status.WAITING ? "等待地形"
                            : progress.totalSegments() == 0 ? (surface != null ? "Tellus LOD粗寻" : "真实地形粗寻")
                            : "分段精寻 " + progress.completedSegments() + "/" + progress.totalSegments();
                    stage += " · step=" + planner.currentStepSize() + " · 节点=" + progress.expandedNodes();
                    bar.update(percent, HierarchicalWaterRoutePlanner.withBudget(stage, planner));
                    nextProgress = System.nanoTime() + 500_000_000L;
                    previousCompleted = progress.completedSegments();
                    previousStatus = status;
                }
                if (status == HierarchicalWaterRoutePlanner.Status.SUCCESS) {
                    List<BlockPos> path;
                    try {
                        path = finishGuidedRoute(map, planner.path(), planner.directEdges(), boatHalfWidth,
                                terrain == null ? null : terrain::awaitProgress, bar::update, true);
                    } catch (StrictWaterDetour.Failure failedRepair) {
                        LOGGER.warn("[WaterPath] 近港接驳修复失败 reason={} {}", failedRepair.reason(), failedRepair.getMessage());
                        return WaterRouteResult.failure(failedRepair.reason());
                    } catch (IllegalArgumentException unverified) {
                        LOGGER.warn("[WaterPath] 航线后处理输入不能验证: {}", unverified.getMessage());
                        return WaterRouteResult.failure(WaterRouteFailureReason.TERRAIN_NOT_READY);
                    }
                    metasOut.set(buildMetas(path, null, path, -1, path.size()));
                    map.logLayerStats("远航分段");
                    return WaterRouteResult.success(path);
                }
                if (status == HierarchicalWaterRoutePlanner.Status.FAILED) {
                    if (terrain != null) LOGGER.warn("[WaterPath] 地形异步层统计 {}", terrain.diagnostic());
                    map.logLayerStats(planner.isCoarseStage() ? "精寻水图(粗寻尚未完成)" : "分层精寻失败");
                    LOGGER.warn("[WaterPath] 分层规划停止 reason={} step={} 节点={} 已完成段={}/{}",
                            planner.failureReason(), planner.currentStepSize(), progress.expandedNodes(),
                            progress.completedSegments(), progress.totalSegments());
                    return WaterRouteResult.failure(planner.failureReason());
                }
                if (status == HierarchicalWaterRoutePlanner.Status.CANCELLED) return WaterRouteResult.failure(WaterRouteFailureReason.CANCELLED);
                if (terrain != null && System.nanoTime() >= nextDiagnostic) {
                    LOGGER.info("[WaterPath] 地形异步状态 stage={} step={} nodes={} pendingEdges={} {}",
                            planner.isCoarseStage() ? "LOD" : "DETAIL", planner.currentStepSize(),
                            progress.expandedNodes(), planner.pendingEdges(), terrain.diagnostic());
                    nextDiagnostic = System.nanoTime() + 10_000_000_000L;
                }
                if (status == HierarchicalWaterRoutePlanner.Status.WAITING) {
                    if (terrain != null) terrain.awaitProgress(10L);
                    else java.util.concurrent.locks.LockSupport.parkNanos(10_000_000L);
                }
            }
        } // cancel outstanding request-owned futures on success, failure or cancellation
    }

    /** Whole-route quality stage, after stitching (including Tellus rescue), before metadata/save.
     * The sampler is the same strict real-first map as fine search; never call on the tick thread.
     */
    static WaterRoutePostProcessor.Result finishWholeRoute(RealBlockWaterMap map, List<BlockPos> path,
            int halfWidth, WaterRoutePostProcessor.AwaitTerrain await,
            WaterRoutePostProcessor.Progress progress) {
        WaterRouteCancellation.check();
        var result = WaterRoutePostProcessor.optimize((x, z) -> map.sampleHullStrict(x, z, halfWidth),
                path, await, progress, postProcessOptions(path));
        LOGGER.info("[WaterPath] 全局离岸后处理 points={}->{} length={}->{} turnDegrees={}->{} hairpins={}->{} loops={} moved={} rounded={} samples={} unknown={} limited={}",
                result.before().points(), result.after().points(), Math.round(result.before().length()),
                Math.round(result.after().length()), Math.round(result.before().turnDegrees()),
                Math.round(result.after().turnDegrees()), result.before().hairpins(), result.after().hairpins(),
                result.loopsRemoved(), result.moved(), result.rounded(), result.samples(), result.unknown(), result.budgetLimited());
        return result;
    }

    static final int REUSE_JOINT_BLOCKS = 256;
    /** 直航段若有端点落在码头这么近的范围(生成器真实地形而非陆地遮罩)就再做一次严格船宽校验。 */
    static final int DIRECT_NEAR_PORT_BLOCKS = 2048;

    /** 去掉 {@link #splitLongEdges} 插入、后处理也没动过的切分点(它们仍在原直边上,偏离 ≤1.5 格)。 */
    static List<BlockPos> dropSplitPoints(List<BlockPos> path, java.util.Set<BlockPos> original) {
        List<BlockPos> out = new ArrayList<>(path);
        for (int i = out.size() - 2; i >= 1; i--) {
            BlockPos p = out.get(i);
            if (original.contains(p)) continue;
            BlockPos a = out.get(i - 1), c = out.get(i + 1);
            double dx = c.getX() - a.getX(), dz = c.getZ() - a.getZ(), len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-6 || a.getY() != p.getY() || p.getY() != c.getY()) continue;
            double deviation = Math.abs((p.getX() - a.getX()) * dz - (p.getZ() - a.getZ()) * dx) / len;
            double t = ((p.getX() - a.getX()) * dx + (p.getZ() - a.getZ()) * dz) / (len * len);
            if (deviation <= 1.5 && t > 0 && t < 1) out.remove(i);
        }
        return out;
    }

    /** 全部边保留,只有「复用段/新补段」交界两侧 window 格以内的边交给后处理。 */
    static List<Boolean> jointWindows(List<BlockPos> path, List<Boolean> reused, int window) {
        List<Boolean> keep = new ArrayList<>(java.util.Collections.nCopies(reused.size(), Boolean.TRUE));
        for (int joint = 1; joint < reused.size(); joint++) {
            if (reused.get(joint).equals(reused.get(joint - 1))) continue;
            double run = 0;
            for (int j = joint - 1; j >= 0 && run < window; j--) { keep.set(j, false); run += Math.sqrt(path.get(j).distSqr(path.get(j + 1))); }
            run = 0;
            for (int j = joint; j < reused.size() && run < window; j++) { keep.set(j, false); run += Math.sqrt(path.get(j).distSqr(path.get(j + 1))); }
        }
        return keep;
    }

    /** 把长边沿直线切成 ≤ maxEdge 的小段(点在已验证的直线水域上),让接口窗口只覆盖接口附近。 */
    static List<BlockPos> splitLongEdges(List<BlockPos> path, List<Boolean> keepIn, List<Boolean> keepOut, int maxEdge) {
        List<BlockPos> out = new ArrayList<>();
        out.add(path.getFirst());
        for (int i = 0; i < keepIn.size(); i++) {
            BlockPos a = path.get(i), b = path.get(i + 1);
            int pieces = Math.max(1, (int) Math.ceil(Math.sqrt(a.distSqr(b)) / maxEdge));
            for (int k = 1; k <= pieces; k++) {
                out.add(k == pieces ? b : new BlockPos((int) Math.round(a.getX() + (b.getX() - a.getX()) * (double) k / pieces), a.getY(),
                        (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * (double) k / pieces)));
                keepOut.add(keepIn.get(i));
            }
        }
        return out;
    }

    /**
     * 分段后处理:keep=true 的边(开阔直航段/已验证的复用段)原样保留,只对其余连续区段(港口两端、近岸精寻段、
     * 复用接口)跑 {@link #finishWholeRoute}。区段两端是固定接点,后处理不会移动。对整条远洋航线逐格做离岸优化
     * 既慢(实测 66s/1100 万取样)又几乎不改变直航段。{@code verifyNearPorts} 时,靠近码头的直航段先做严格船宽校验
     * (真实地形可能与陆地遮罩不同);失败段先严格局部重寻，不把坏输入交给几何优化器。
     */
    static List<BlockPos> finishGuidedRoute(RealBlockWaterMap map, List<BlockPos> path, List<Boolean> keep, int halfWidth,
                                            WaterRoutePostProcessor.AwaitTerrain await, WaterRoutePostProcessor.Progress progress,
                                            boolean verifyNearPorts) {
        // Re-plan actual blocked geometry before handing it to a validator/optimizer.
        if (verifyNearPorts) {
            var repaired = NearPortRouteRepair.repair(map, path, keep, halfWidth, await, progress, DIRECT_NEAR_PORT_BLOCKS);
            path = repaired.path();
            keep = repaired.keep();
        }
        if (keep == null || keep.size() != path.size() - 1 || !keep.contains(Boolean.TRUE)) {
            LOGGER.info("[WaterPath] 全程后处理(无可保留边) points={} keep={} kept={}", path.size(),
                    keep == null ? -1 : keep.size(), keep == null ? 0 : keep.stream().filter(Boolean::booleanValue).count());
            return finishWholeRoute(map, path, halfWidth, await, progress).path();
        }
        List<BlockPos> out = new ArrayList<>();
        int i = 0, sections = 0;
        while (i < keep.size()) {
            WaterRouteCancellation.check();
            if (keep.get(i)) {
                HierarchicalWaterRoutePlanner.appendCompacted(out, List.of(path.get(i), path.get(i + 1)));
                i++;
                continue;
            }
            int j = i;
            while (j < keep.size() && !keep.get(j)) j++;
            List<BlockPos> section = path.subList(i, j + 1);
            HierarchicalWaterRoutePlanner.appendCompacted(out, section.size() < 2 ? section
                    : finishWholeRoute(map, new ArrayList<>(section), halfWidth, await, progress).path());
            sections++;
            i = j;
        }
        LOGGER.info("[WaterPath] 分段后处理:保留直航/复用边={} 后处理区段={} 航点 {}->{}",
                keep.stream().filter(Boolean::booleanValue).count(), sections, path.size(), out.size());
        return out;
    }

    /** 严格船宽校验一条边,等待异步地形(最多 60s)。 */
    private static boolean strictSegmentClear(RealBlockWaterMap map, BlockPos a, BlockPos b, int halfWidth,
                                              WaterRoutePostProcessor.AwaitTerrain await) {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            WaterRouteCancellation.check();
            int before = map.terrainMisses();
            boolean clear = map.segmentHullClear(a, b, halfWidth);
            if (map.terrainMisses() == before) return clear;
            if (await != null) await.await(20); else java.util.concurrent.locks.LockSupport.parkNanos(20_000_000L);
        }
        return false;
    }

    /** 默认预算(30s / 200 万取样)只够数万格航线;远洋航线按长度放大,否则整条输入验证会超预算直接判失败。 */
    static WaterRoutePostProcessor.Options postProcessOptions(List<BlockPos> path) {
        WaterRoutePostProcessor.Options base = WaterRoutePostProcessor.Options.defaults();
        double length = 0;
        for (int i = 1; i < path.size(); i++) length += Math.sqrt(path.get(i - 1).distSqr(path.get(i)));
        long samples = Math.max(base.maxSamples(), Math.min(40_000_000L, Math.round(length * 40)));
        long millis = Math.max(base.maxMillis(), Math.min(240_000L, Math.round(length / 2)));
        // 离岸目标 8→12 格、最大平移 24→32 格:近岸精寻段尽量远离海岸(选项上限 16/64)。
        return new WaterRoutePostProcessor.Options(12, 32, millis, (int) samples);
    }

    static boolean useHierarchicalTerrain(boolean tellusPrediction, double distance) {
        return tellusPrediction || distance > 8192.0D;
    }

    static List<BlockPos> finishDetailedSegment(RealBlockWaterMap map, List<BlockPos> raw, int seaY, int halfWidth) {
        WaterRouteCancellation.check();
        int missing = map.terrainMisses();
        List<BlockPos> smooth = WaterPathSmoother.smooth(map, raw, seaY, 2.0D, halfWidth);
        // Smoothing is optional: unknown off-path terrain must not delay an already verified raw route.
        if (map.terrainMisses() == missing) {
            try { if (strictVerified(map, smooth, raw, halfWidth)) return smooth; }
            catch (HierarchicalWaterRoutePlanner.TerrainNotReadyException optionalSmoothing) { /* try raw below */ }
        }
        if (strictVerified(map, raw, raw, halfWidth)) return raw;
        return null;
    }

    private static boolean strictVerified(RealBlockWaterMap map, List<BlockPos> candidate,
                                          List<BlockPos> endpoints, int halfWidth) {
        if (candidate == null || candidate.size() < 2 || !candidate.getFirst().equals(endpoints.getFirst())
                || !candidate.getLast().equals(endpoints.getLast())) return false;
        int missing = map.terrainMisses();
        for (int i = 1; i < candidate.size(); i++) {
            WaterRouteCancellation.check();
            boolean clear = map.segmentHullClear(candidate.get(i - 1), candidate.get(i), halfWidth);
            if (map.terrainMisses() != missing) throw new HierarchicalWaterRoutePlanner.TerrainNotReadyException();
            if (!clear) return false;
        }
        return true;
    }

    /**
     * <b>后台线程</b>:Tellus 世界的完整自救——船位最近可航格 → 目的港泊位,用与建线相同的「NBT + 地形预测」单段规划。
     * (原自救的 ServerWaterRouteWorld 依赖原版噪声生成器,在 Tellus 世界全判不可航。)level 可为 null(测试)。
     */
    public static WaterRouteResult<WaterRescuePlanner.Splice> planLocalRescue(ServerLevel level, Vec3 boat,
            List<Vec3> route, int targetIndex,
            @Nullable com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor) {
        if (level != null && level.getServer().isSameThread())
            throw new IllegalStateException("Water rescue must run on a route worker");
        int seaY = level == null ? (int)Math.floor(boat.y) : level.getSeaLevel();
        RealBlockWaterMap map = new RealBlockWaterMap(level, seaY).enableOnDemand().retryUnknownPrediction()
                .withPredictor(predictor, com.monpai.sailboatmod.route.terrain.PredictionBudget.sixtySeconds());
        try (RouteTerrainSource terrain = level == null ? null : new RouteTerrainSource(level, predictor,
                () -> com.monpai.sailboatmod.route.terrain.TellusSurfaceSamplers.forLevel(level).orElse(null))) {
            if (terrain != null) map.withAsyncTerrain(terrain);
            try {
                LiveWaterSnapshots.seedNearby(level, map, boat, System.nanoTime()+3_000_000_000L);
                return WaterRescuePlanner.plan(map, boat, route, targetIndex, terrain == null ? null : terrain::awaitProgress);
            } catch (StrictWaterDetour.Failure failed) { return WaterRouteResult.failure(failed.reason()); }
        }
    }

    public static WaterRouteResult<List<BlockPos>> planTellusRescue(@Nullable ServerLevel level, int seaY, BlockPos boat,
                                                                    DockBerthResolver.DockZone destination,
                                                                    com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor,
                                                                    com.monpai.sailboatmod.route.terrain.PredictionBudget budget) {
        WaterRoutePolicy policy = WaterRoutePolicy.defaults();
        RealBlockWaterMap probe = new RealBlockWaterMap(level, seaY).enableOnDemand().retryUnknownPrediction().withPredictor(predictor, budget);
        WaterRouteResult<DockBerthResolver.DockBerth> berth = DockBerthResolver.resolve(
                RealBlockWaterWorld.streaming(probe, seaY, 1, null, null), destination, policy,
                WaterRouteFailureReason.NO_TARGET_BERTH);
        if (!berth.successful()) {
            return WaterRouteResult.failure(probe.terrainMisses() > 0
                    ? WaterRouteFailureReason.TERRAIN_NOT_READY : berth.reason());
        }
        BlockPos start = probe.nearestHullWater(boat.getX(), boat.getZ(), 1, 16);
        if (start == null) {
            return WaterRouteResult.failure(probe.terrainMisses() > 0
                    ? WaterRouteFailureReason.TERRAIN_NOT_READY : WaterRouteFailureReason.NO_SOURCE_BERTH);
        }
        BlockPos goal = blockPos(berth.value().pos());
        int boatHalfWidth = Math.max(1, policy.boatHalfWidth());
        return runSingleSegmentNbt(level, start, goal, seaY, boatHalfWidth, policy,
                new java.util.concurrent.atomic.AtomicReference<>(List.of()),
                new WaterRouteProgressBar(null, null, "", ""), predictor, budget);
    }

    /** Tellus 预取走廊:两码头直线两侧各此格数。走廊外的区域由 A* 按需预取。 */
    static final int TELLUS_PREFETCH_SIDE_BLOCKS = 512;

    /** Tellus 世界永远走单段 NBT(NBT + 地形预测);其余世界尊重玩家/指令选的模式。 */
    static WaterMidMode effectiveMode(WaterMidMode requested, boolean tellusLevel) {
        return tellusLevel ? WaterMidMode.NBT : requested;
    }

    /** 进度条「下载地形」阶段文字(与其它阶段同为硬编码简体)。 */
    static String terrainDownloadStage(int done, int total) {
        return "下载地形 " + done + "/" + total;
    }

    /**
     * <b>后台线程</b>:预取两码头间走廊的 Tellus 地形数据,逐 tile 等待并更新进度条(0~5%)。等待从共用预算扣;
     * 预算用完即停止等待(剩余区域在 A* 按需预测时再等/判陆)。单个 tile 失败不影响其它 tile。
     */
    private static void prefetchTellusCorridor(com.monpai.sailboatmod.route.terrain.TerrainPredictor predictor,
                                               com.monpai.sailboatmod.route.terrain.PredictionBudget budget,
                                               BlockPos sourceBerth, BlockPos targetBerth, WaterRouteProgressBar bar) {
        if (predictor.gate() == com.monpai.sailboatmod.route.terrain.PrefetchGate.ALWAYS_READY) return;
        // Warm the first local window only. Hierarchical search requests its actual frontier later.
        double dx = (double) targetBerth.getX() - sourceBerth.getX(), dz = (double) targetBerth.getZ() - sourceBerth.getZ();
        double distance = Math.hypot(dx, dz), ratio = distance > 1024 ? 1024 / distance : 1;
        BlockPos front = new BlockPos(sourceBerth.getX() + (int) Math.round(dx * ratio), sourceBerth.getY(),
                sourceBerth.getZ() + (int) Math.round(dz * ratio));
        var tiles = predictor.gate().corridor(sourceBerth, front, TELLUS_PREFETCH_SIDE_BLOCKS).iterator();
        java.util.ArrayDeque<java.util.concurrent.CompletableFuture<Void>> window = new java.util.ArrayDeque<>();
        int total = 16, submitted = 0;
        long start = System.currentTimeMillis();
        int done = 0;
        bar.update(0, terrainDownloadStage(0, total));
        while (budget.remainingMillis() > 0) {
            WaterRouteCancellation.check();
            while (window.size() < 4 && submitted < total && tiles.hasNext()) { window.addLast(tiles.next()); submitted++; }
            if (window.isEmpty()) break;
            java.util.concurrent.CompletableFuture<Void> tile = window.removeFirst();
            try {
                tile.get(budget.remainingMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                LOGGER.warn("[WaterPath] Tellus 地形预取失败(该区按需再试): {}", e.toString());
            }
            done++;
            bar.update(5 * done / total, terrainDownloadStage(done, total));
        }
        LOGGER.info("[WaterPath] Tellus 地形预取 {}/{} 区 耗时={}ms 剩余预算={}ms",
                done, total, System.currentTimeMillis() - start, budget.remainingMillis());
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
            WaterRouteCancellation.check();
            st = pf.step(policy.nodesPerTick(), policy.chunkLoadsPerTick());
        } while (st == WaterRoutePathfinder.Status.RUNNING && System.nanoTime() < deadline);
        return pf;
    }

    private static RouteDefinition applyCompletedRoute(ServerLevel level,
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
            return null;
        }
        // 2026-06:smooth+verify 已移到后台 Supplier(消除主线程卡服),result.value() 即最终航点。
        // 这里只剩 setRoutes + 发消息(轻,主线程安全)。
        List<BlockPos> verified = result.value();
        if (verified == null || verified.size() < 2) {
            // 穿陆且绕不开 → 不落地无效航线(船会卡死),报无水路。
            if (player != null) {
                player.sendSystemMessage(messageFor(WaterRouteFailureReason.NO_WATER_PATH));
            }
            return null;
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
        WaterRouteNetworkSavedData.get(level).register(sourceSnapshot.pos(), targetSnapshot.pos(), route);

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
        return route;
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

    public static boolean isGeneratedWaterRoute(RouteDefinition route) {
        return route != null && isGeneratedWaterRouteName(route.name());
    }

    /** 按名称判自动水路航线(载具重载后只剩航线名)。兼容旧前缀("Water Auto: ")与新前缀("Auto-")。 */
    public static boolean isGeneratedWaterRouteName(String name) {
        return name != null && (name.startsWith("Auto-") || name.startsWith("Water Auto: "));
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
        if (reason == WaterRouteFailureReason.TERRAIN_NOT_READY) {
            return Component.literal("地形数据尚未就绪或码头信息已变化，请稍后重新验证；这不表示码头无效。");
        }
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
