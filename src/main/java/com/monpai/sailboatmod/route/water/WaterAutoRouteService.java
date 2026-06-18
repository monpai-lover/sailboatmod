package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationDiplomacyRecord;
import com.monpai.sailboatmod.route.PathSmoother;
import com.monpai.sailboatmod.route.RouteDefinition;
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
        // 连通性溯源:泊位必须通向开阔水域(非封闭小水坑)。只在候选列表这关跑(低频手动)。
        // berthWorld 实际实例是 ServerWaterRouteWorld(同时实现 WaterRouteWorld),转型后用其 sample 做 flood-fill。
        if (berthWorld instanceof WaterRouteWorld routeWorld) {
            if (!WaterConnectivityProbe.reachesOpenWater(routeWorld, blockPos(sourceBerth.value().pos()), policy)) {
                return WaterRouteResult.failure(WaterRouteFailureReason.SOURCE_NOT_OPEN_WATER);
            }
            if (!WaterConnectivityProbe.reachesOpenWater(routeWorld, blockPos(targetBerth.value().pos()), policy)) {
                return WaterRouteResult.failure(WaterRouteFailureReason.TARGET_NOT_OPEN_WATER);
            }
        }
        return WaterRouteResult.success(new BerthPair(sourceBerth.value(), targetBerth.value()));
    }

    public static WaterRouteResult<Void> submitAutoRoute(ServerLevel level,
                                                         DockBlockEntity source,
                                                         DockBlockEntity target,
                                                         @Nullable ServerPlayer player) {
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
        ServerWaterRouteWorld routeWorld = new ServerWaterRouteWorld(level);
        WaterRoutePathfinder pathfinder = new WaterRoutePathfinder(routeWorld, sourceBerth, targetBerth, policy);
        WaterRouteTask task = new WaterRouteTask(
                level.dimension().location().toString(),
                source.getBlockPos(),
                target.getBlockPos(),
                sourceBerth,
                targetBerth,
                playerName(player),
                policy,
                pathfinder,
                result -> applyCompletedRoute(level, source, target, sourceSnapshot, targetSnapshot,
                        sourceTownName, targetTownName, result, player));
        return WaterRouteTaskService.global().submit(task).successful()
                ? WaterRouteResult.success(null)
                : WaterRouteResult.failure(WaterRouteFailureReason.ALREADY_PENDING);
    }

    private static void applyCompletedRoute(ServerLevel level,
                                            DockBlockEntity source,
                                            DockBlockEntity target,
                                            DockSnapshot sourceSnapshot,
                                            DockSnapshot targetSnapshot,
                                            String sourceTownName,
                                            String targetTownName,
                                            WaterRouteResult<List<BlockPos>> result,
                                            @Nullable ServerPlayer player) {
        if (!result.successful()) {
            if (player != null) {
                player.sendSystemMessage(messageFor(result.reason()));
            }
            return;
        }
        // 先平滑(Catmull-Rom 样条+弧长重采样,消锯齿/直线/稀疏),后真实区块校验(堵穿陆漏洞)。
        // 顺序不可反:样条会把航点推离折线可能推上陆地,校验必须是最后一道闸门。两步都在主线程(回调里),
        // 真实校验只对这一条最终航线跑一次 + hasChunkAt 预检,不卡服。
        int seaY = level.getSeaLevel();
        List<BlockPos> smoothed = PathSmoother.smooth2D(result.value(), seaY, 2.0D);
        List<BlockPos> verified = RealWaterVerifier.verifyAndRepair(level, smoothed);
        if (verified.size() < 2) {
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
                playerName(player),
                player == null ? "" : player.getUUID().toString(),
                System.currentTimeMillis());
        List<RouteDefinition> routes = upsertAutoRoute(source.getRoutesForMap(), route);
        int selectedIndex = Math.max(0, routes.indexOf(route));
        source.setRoutes(routes, selectedIndex);
        if (player != null) {
            player.sendSystemMessage(Component.translatable(
                    "message.sailboatmod.auto_route.water.created",
                    target.getDockName(),
                    Integer.toString((int) Math.round(route.routeLengthMeters()))));
        }
    }

    static RouteDefinition routeDefinitionFromPath(DockSnapshot source,
                                                   DockSnapshot target,
                                                   String sourceTownName,
                                                   String targetTownName,
                                                   List<BlockPos> path,
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
        return new RouteDefinition(
                "Auto-" + srcTown + "-" + dstTown,
                waypoints,
                authorName,
                authorUuid,
                createdAtEpochMillis,
                routeLength,
                source == null ? "" : source.name(),
                targetName);
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
