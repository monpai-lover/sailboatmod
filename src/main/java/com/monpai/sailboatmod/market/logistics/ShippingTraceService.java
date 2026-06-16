package com.monpai.sailboatmod.market.logistics;

import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.PurchaseOrder;
import com.monpai.sailboatmod.market.ShippingOrder;
import com.monpai.sailboatmod.market.web.MarketPlayerIdentity;
import com.monpai.sailboatmod.market.web.map.MarketWebMapConstants;
import com.monpai.sailboatmod.market.web.map.MarketWebMapDtos;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ShippingTraceService {
    public static void createOrUpdateTrace(Level level, ShippingOrder order, RouteDefinition route) {
        if (!(level instanceof ServerLevel serverLevel) || order == null || route == null || route.waypoints().size() < 2) {
            return;
        }
        NationScope scope = scopeFor(serverLevel, order.sourceDockPos().getX() >> 4, order.sourceDockPos().getZ() >> 4);
        long gameTime = serverLevel.getGameTime();
        ShippingTraceSavedData.get(serverLevel).putTrace(new ShippingTraceRecord(
                order.shippingOrderId(),
                order.shipperUuid(),
                MarketWebMapConstants.OVERWORLD,
                order.transportMode(),
                order.status(),
                scope.nationId(),
                scope.townId(),
                order.sourceTerminalName().isBlank() ? order.sourceDockName() : order.sourceTerminalName(),
                order.targetTerminalName().isBlank() ? order.targetDockName() : order.targetTerminalName(),
                route.waypoints(),
                0,
                0.0D,
                gameTime,
                gameTime,
                0.0D,
                0.0D,
                0.0D,
                false
        ));
    }

    public static void updateStatus(Level level, String shippingOrderId, String status) {
        if (!(level instanceof ServerLevel serverLevel) || shippingOrderId == null || shippingOrderId.isBlank()) {
            return;
        }
        ShippingTraceSavedData data = ShippingTraceSavedData.get(serverLevel);
        ShippingTraceRecord trace = data.getTrace(shippingOrderId);
        if (trace != null) {
            data.putTrace(trace.withStatus(status, serverLevel.getGameTime()));
        }
    }

    public static void updateProgress(Level level, String shippingOrderId, int completedPointCount, double progressRatio) {
        if (!(level instanceof ServerLevel serverLevel) || shippingOrderId == null || shippingOrderId.isBlank()) {
            return;
        }
        ShippingTraceSavedData data = ShippingTraceSavedData.get(serverLevel);
        ShippingTraceRecord trace = data.getTrace(shippingOrderId);
        if (trace != null) {
            data.putTrace(trace.withProgress(completedPointCount, progressRatio, serverLevel.getGameTime()));
        }
    }

    public static List<ShippingTraceRecord> visibleFor(MinecraftServer server, MarketPlayerIdentity identity) {
        if (server == null || identity == null) {
            return List.of();
        }
        ServerLevel overworld = server.overworld();
        NationSavedData nationData = NationSavedData.get(overworld);
        NationMemberRecord member = nationData.getMember(identity.playerUuid());
        String viewerNationId = member == null ? "" : member.nationId();
        String viewerUuid = identity.playerUuidString();
        MarketSavedData marketData = MarketSavedData.get(overworld);
        List<ShippingTraceRecord> out = new ArrayList<>();
        for (ShippingTraceRecord trace : ShippingTraceSavedData.get(overworld).getTraces()) {
            if (!isMapVisibleStatus(trace.status()) || trace.waypoints().size() < 2) {
                continue;
            }
            boolean ownShipment = !viewerUuid.isBlank() && viewerUuid.equals(trace.shipperUuid());
            if (!ownShipment) {
                ShippingOrder order = marketData.getShippingOrder(trace.shippingOrderId());
                ownShipment = order != null && viewerUuid.equals(order.shipperUuid());
            }
            boolean sameNation = !viewerNationId.isBlank() && viewerNationId.equals(trace.nationId());
            if (ownShipment || sameNation) {
                out.add(trace);
            }
        }
        out.sort(Comparator.comparing(ShippingTraceRecord::updatedGameTime).reversed());
        return out;
    }

    public static List<MarketWebMapDtos.ShipmentTrace> toDtos(MinecraftServer server, List<ShippingTraceRecord> traces) {
        List<MarketWebMapDtos.ShipmentTrace> out = new ArrayList<>();
        ServerLevel overworld = server == null ? null : server.overworld();
        NationSavedData nationData = overworld == null ? null : NationSavedData.get(overworld);
        MarketSavedData marketData = overworld == null ? null : MarketSavedData.get(overworld);
        for (ShippingTraceRecord trace : traces == null ? List.<ShippingTraceRecord>of() : traces) {
            out.add(toDto(trace, nationData, marketData));
        }
        return out;
    }

    public static MarketWebMapDtos.ShipmentTrace toDto(ShippingTraceRecord trace,
                                                       NationSavedData nationData,
                                                       MarketSavedData marketData) {
        List<MarketWebMapDtos.Point> points = new ArrayList<>();
        for (Vec3 waypoint : trace.waypoints()) {
            points.add(new MarketWebMapDtos.Point(waypoint.x, waypoint.z));
        }

        // 订单轨迹可解析出真实的 town→town 命名、ETA、货物清单；手动轨迹无订单时全部回退。
        ShippingOrder order = (marketData == null || trace.shippingOrderId().isBlank())
                ? null : marketData.getShippingOrder(trace.shippingOrderId());

        String sourceTown = "";
        String targetTown = "";
        int etaSeconds = 0;
        List<MarketWebMapDtos.CargoItem> cargo = List.of();
        if (order != null) {
            sourceTown = resolveTownName(nationData, order.sourceDockPos());
            targetTown = resolveTownName(nationData, order.targetDockPos());
            etaSeconds = order.etaSeconds();
            cargo = resolveCargo(marketData, order);
        }

        // town→town 主命名；某端不在城镇内则回退该端的驿站名。
        String sourceLabel = sourceTown.isBlank() ? (trace.sourceName().isBlank() ? "?" : trace.sourceName()) : sourceTown;
        String targetLabel = targetTown.isBlank() ? (trace.targetName().isBlank() ? "?" : trace.targetName()) : targetTown;
        String label = sourceLabel + " -> " + targetLabel;

        return new MarketWebMapDtos.ShipmentTrace(
                trace.shippingOrderId(),
                label,
                trace.transportMode(),
                trace.status(),
                trace.sourceName(),
                trace.targetName(),
                sourceTown,
                targetTown,
                etaSeconds,
                trace.currentSpeed(),
                cargo,
                points,
                trace.completedPointCount(),
                trace.progressRatio(),
                new MarketWebMapDtos.Point(trace.currentX(), trace.currentZ()),
                trace.manual()
        );
    }

    /** dock 坐标 → 所在 claim 的 town 名；无主地/无 town 返回空串。 */
    private static String resolveTownName(NationSavedData nationData, net.minecraft.core.BlockPos dockPos) {
        if (nationData == null || dockPos == null) {
            return "";
        }
        NationClaimRecord claim = nationData.getClaim(MarketWebMapConstants.OVERWORLD, dockPos.getX() >> 4, dockPos.getZ() >> 4);
        if (claim == null || claim.townId() == null || claim.townId().isBlank()) {
            return "";
        }
        TownRecord town = nationData.getTown(claim.townId());
        return town == null || town.name() == null ? "" : town.name();
    }

    /** 订单货物摘要：purchaseOrder 数量 + listing 物品名 + 收货人。 */
    private static List<MarketWebMapDtos.CargoItem> resolveCargo(MarketSavedData marketData, ShippingOrder order) {
        if (marketData == null || order.purchaseOrderId().isBlank()) {
            return List.of();
        }
        PurchaseOrder purchase = marketData.getPurchaseOrder(order.purchaseOrderId());
        if (purchase == null || purchase.quantity() <= 0) {
            return List.of();
        }
        String itemName = "?";
        MarketListing listing = marketData.getListing(purchase.listingId());
        if (listing != null && !listing.itemStack().isEmpty()) {
            itemName = listing.itemStack().getHoverName().getString();
        }
        return List.of(new MarketWebMapDtos.CargoItem(itemName, purchase.quantity(), purchase.buyerName()));
    }

    public static boolean isMapVisibleStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return "SAILING".equals(normalized) || "IN_TRANSIT".equals(normalized)
                || "ARRIVED".equals(normalized) || "STUCK".equals(normalized);
    }

    /** 手动发车的稳定 trace id，与订单 id 不冲突。 */
    public static String manualTraceId(java.util.UUID vehicleUuid) {
        return "manual-" + (vehicleUuid == null ? "unknown" : vehicleUuid.toString());
    }

    // 定期兜底清理阈值（gametick）。订单轨迹终态/查不到后过 STALE_ORDER 删；
    // 手动轨迹无订单可查，仅靠长时间不更新（载具丢失/崩溃/强杀残留）判定，阈值更长。
    private static final long STALE_ORDER_TRACE_TICKS = 6000L;    // 5 分钟
    private static final long STALE_MANUAL_TRACE_TICKS = 72000L;  // 1 小时

    /**
     * 定期扫描清理无效/孤儿轨迹（崩溃/强杀/订单完成等未走正常删除路径残留的）。
     * 由 ServerEvents 周期调用。只删确定无效的：
     *  - 订单轨迹：订单查不到 或 状态非可见终态（DELIVERED/FAILED/CANCELLED 等），且距上次更新超阈值。
     *  - 手动轨迹：距上次更新超过更长阈值（载具早该 live-update 位置，长期不动即视为已失效）。
     */
    public static void cleanupOrphanTraces(net.minecraft.server.MinecraftServer server) {
        cleanupOrphanTraces(server, false);
    }

    /**
     * 清理无效/孤儿轨迹。
     * @param force true=手动指令立即清理（忽略时间阈值，只看订单终态/手动 id），false=定期兜底（带时间阈值）。
     * @return 删除的轨迹条数。
     */
    public static int cleanupOrphanTraces(net.minecraft.server.MinecraftServer server, boolean force) {
        if (server == null) {
            return 0;
        }
        ServerLevel overworld = server.overworld();
        ShippingTraceSavedData traceData = ShippingTraceSavedData.get(overworld);
        MarketSavedData marketData = MarketSavedData.get(overworld);
        long now = overworld.getGameTime();
        List<String> toRemove = new ArrayList<>();
        for (ShippingTraceRecord trace : traceData.getTraces()) {
            String id = trace.shippingOrderId();
            if (id.isBlank()) {
                continue;
            }
            long idleTicks = Math.max(0L, now - trace.updatedGameTime());
            boolean manual = trace.manual() || id.startsWith("manual-");
            if (manual) {
                // 手动轨迹无订单可查：定期模式靠长闲置判定；force 模式不动手动轨迹（无法确认载具是否还在跑）。
                if (!force && idleTicks > STALE_MANUAL_TRACE_TICKS) {
                    toRemove.add(id);
                }
                continue;
            }
            ShippingOrder order = marketData.getShippingOrder(id);
            boolean terminal = order == null || !isMapVisibleStatus(order.status());
            if (terminal && (force || idleTicks > STALE_ORDER_TRACE_TICKS)) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            traceData.removeTrace(id);
        }
        return toRemove.size();
    }

    /**
     * 为手动发车的载具建/更新一条 manual Trace（无市场订单）。
     * @param waypoints 载具 autopilotRoute 路点；少于 2 点不建
     * @param transportMode "PORT"(水) / "LAND"(陆)；status 可见状态 "SAILING"/"IN_TRANSIT"
     * @param sourceName 出发地名（空则回退"手动"）
     * @param targetName 目的地名（空则回退"手动"）
     */
    public static void createOrUpdateManualTrace(Level level, java.util.UUID vehicleUuid,
                                                 List<Vec3> waypoints, String shipperUuid, String nationId,
                                                 String transportMode, String status,
                                                 String sourceName, String targetName,
                                                 double currentX, double currentZ) {
        if (level == null || level.isClientSide() || vehicleUuid == null
                || waypoints == null || waypoints.size() < 2) {
            return;
        }
        long gameTime = level.getGameTime();
        String src = sourceName == null || sourceName.isBlank() ? "手动" : sourceName.trim();
        String dst = targetName == null || targetName.isBlank() ? "手动" : targetName.trim();
        ShippingTraceRecord rec = new ShippingTraceRecord(
                manualTraceId(vehicleUuid), shipperUuid == null ? "" : shipperUuid,
                MarketWebMapConstants.OVERWORLD, transportMode, status,
                nationId == null ? "" : nationId, "", src, dst,
                waypoints, 0, 0.0D, gameTime, gameTime, currentX, currentZ, 0.0D, true);
        ShippingTraceSavedData.get(level).putTrace(rec);
    }

    /** 写入载具实时坐标（调度车/手动车通用）。 */
    public static void updateLivePosition(Level level, String traceId, double x, double z) {
        if (level == null || level.isClientSide() || traceId == null || traceId.isBlank()) {
            return;
        }
        ShippingTraceSavedData data = ShippingTraceSavedData.get(level);
        ShippingTraceRecord trace = data.getTrace(traceId);
        if (trace != null) {
            data.putTrace(trace.withLivePosition(x, z, level.getGameTime()));
        }
    }

    /** 写入载具实时坐标 + 进度（已通过路点数 / 进度比），使网页地图已走段渲染实线。 */
    public static void updateLivePositionAndProgress(Level level, String traceId,
                                                     double x, double z,
                                                     int completedPointCount, double progressRatio) {
        if (level == null || level.isClientSide() || traceId == null || traceId.isBlank()) {
            return;
        }
        ShippingTraceSavedData data = ShippingTraceSavedData.get(level);
        ShippingTraceRecord trace = data.getTrace(traceId);
        if (trace != null) {
            long gameTime = level.getGameTime();
            // withProgress 保留 currentX/Z，故先 withLivePosition 再 withProgress。
            data.putTrace(trace.withLivePosition(x, z, gameTime)
                    .withProgress(completedPointCount, progressRatio, gameTime));
        }
    }

    /** 移除指定 trace（autopilot 结束清理用）。 */
    public static void removeTrace(Level level, String traceId) {
        if (level == null || level.isClientSide() || traceId == null || traceId.isBlank()) {
            return;
        }
        ShippingTraceSavedData.get(level).removeTrace(traceId);
    }

    private static NationScope scopeFor(ServerLevel level, int chunkX, int chunkZ) {
        NationClaimRecord claim = NationSavedData.get(level).getClaim(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ);
        return claim == null ? new NationScope("", "") : new NationScope(claim.nationId(), claim.townId());
    }

    private record NationScope(String nationId, String townId) {
    }

    private ShippingTraceService() {
    }
}
