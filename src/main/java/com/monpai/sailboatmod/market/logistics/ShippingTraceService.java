package com.monpai.sailboatmod.market.logistics;

import com.monpai.sailboatmod.dock.PostStationRegistry;
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
import com.monpai.sailboatmod.nation.service.DockTownResolver;
import com.monpai.sailboatmod.route.RouteDefinition;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShippingTraceService {
    /** 手动轨迹尾坐标查不到 town 时，向附近这么多格内找最近驿站方块反查其绑定 town。 */
    private static final int STATION_SEARCH_RADIUS = 48;

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
                java.util.List.of(),
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
            out.add(toDto(trace, nationData, marketData, overworld));
        }
        return out;
    }

    public static MarketWebMapDtos.ShipmentTrace toDto(ShippingTraceRecord trace,
                                                       NationSavedData nationData,
                                                       MarketSavedData marketData,
                                                       net.minecraft.world.level.Level level) {
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
            sourceTown = resolveTownNameForPos(nationData, level, order.sourceDockPos());
            targetTown = resolveTownNameForPos(nationData, level, order.targetDockPos());
            etaSeconds = order.etaSeconds();
            cargo = resolveCargo(marketData, order);
        } else if (trace.manual() && trace.waypoints().size() >= 2) {
            // 手动轨迹无订单：用 waypoints 首尾坐标反查 town→town 命名；ETA 用当前速度 + 剩余路程现算。
            // 货物由 manual 轨迹自带的快照提供（手动发车时从载具容器抓取）。
            List<Vec3> waypoints = trace.waypoints();
            sourceTown = resolveTownNameNearXZ(nationData, level, waypoints.get(0).x, waypoints.get(0).z);
            targetTown = resolveTownNameNearXZ(nationData, level,
                    waypoints.get(waypoints.size() - 1).x, waypoints.get(waypoints.size() - 1).z);
            etaSeconds = estimateManualEtaSeconds(trace);
            cargo = trace.cargo();
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

    /**
     * 由 townId 取 town 名；空/查无返回空串。
     */
    private static String townNameById(NationSavedData nationData, String townId) {
        if (nationData == null || townId == null || townId.isBlank()) {
            return "";
        }
        TownRecord town = nationData.getTown(townId);
        return town == null || town.name() == null ? "" : town.name();
    }

    /**
     * dock/驿站坐标 → 所属 town 名（robust）。优先用 {@link DockTownResolver} 读驿站方块实体上绑定的
     * townId（即使驿站坐落在无主地也存着），再退回按 claim 查；都查不到才返回空串（前端 fallback 驿站名）。
     * 这修复了「目的地驿站在无主地 / claim 查不到 → 显示驿站名而非 town 名」的回退显示。
     */
    private static String resolveTownNameForPos(NationSavedData nationData,
                                                net.minecraft.world.level.Level level,
                                                net.minecraft.core.BlockPos dockPos) {
        if (nationData == null || dockPos == null) {
            return "";
        }
        // 1) 驿站方块实体绑定的 townId（权威，无主地也有）。
        if (level != null) {
            String boundTownId = DockTownResolver.resolveTownForArrival(level, dockPos);
            String name = townNameById(nationData, boundTownId);
            if (!name.isBlank()) {
                return name;
            }
        }
        // 2) 退回按所在 chunk 的 claim 查。
        NationClaimRecord claim = nationData.getClaim(MarketWebMapConstants.OVERWORLD,
                dockPos.getX() >> 4, dockPos.getZ() >> 4);
        if (claim != null && claim.townId() != null && !claim.townId().isBlank()) {
            return townNameById(nationData, claim.townId());
        }
        return "";
    }

    /**
     * 世界 X/Z → 所属 town 名（手动轨迹用 waypoints 首尾反查，robust）。先按 XZ 直接查 claim；查不到再到
     * {@link PostStationRegistry} 里找该坐标附近（{@value #STATION_SEARCH_RADIUS} 格内）最近的驿站方块，
     * 用驿站坐标走 {@link #resolveTownNameForPos} 解析其绑定 town —— 修「尾坐标落在 town 边界外几格 /
     * 驿站在无主地 → 显示驿站名」。
     */
    private static String resolveTownNameNearXZ(NationSavedData nationData,
                                                net.minecraft.world.level.Level level,
                                                double worldX, double worldZ) {
        if (nationData == null) {
            return "";
        }
        int chunkX = net.minecraft.util.Mth.floor(worldX) >> 4;
        int chunkZ = net.minecraft.util.Mth.floor(worldZ) >> 4;
        // 1) 快路径：直接按 claim 查。
        NationClaimRecord claim = nationData.getClaim(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ);
        if (claim != null && claim.townId() != null && !claim.townId().isBlank()) {
            String name = townNameById(nationData, claim.townId());
            if (!name.isBlank()) {
                return name;
            }
        }
        // 2) 找附近最近的驿站方块，用其绑定 town 名（覆盖尾坐标偏离 / 无主地驿站）。
        if (level != null) {
            net.minecraft.core.BlockPos nearest = nearestPostStation(level, worldX, worldZ);
            if (nearest != null) {
                String name = resolveTownNameForPos(nationData, level, nearest);
                if (!name.isBlank()) {
                    return name;
                }
            }
        }
        return "";
    }

    /** 半径内（格）找最近驿站方块；超出范围或无驿站返回 null。 */
    private static net.minecraft.core.BlockPos nearestPostStation(net.minecraft.world.level.Level level,
                                                                  double worldX, double worldZ) {
        net.minecraft.core.BlockPos best = null;
        double bestSqr = (double) STATION_SEARCH_RADIUS * STATION_SEARCH_RADIUS;
        for (net.minecraft.core.BlockPos station : PostStationRegistry.get(level)) {
            double dx = station.getX() + 0.5D - worldX;
            double dz = station.getZ() + 0.5D - worldZ;
            double sqr = dx * dx + dz * dz;
            if (sqr <= bestSqr) {
                bestSqr = sqr;
                best = station;
            }
        }
        return best;
    }

    /**
     * 手动轨迹 ETA（秒）：当前速度(blocks/tick) × 20 = blocks/s，剩余路程 / 速度。
     * 速度为 0 或无剩余路程时返回 0（前端值>0 才显示）。
     */
    private static int estimateManualEtaSeconds(ShippingTraceRecord trace) {
        double speedPerTick = trace.currentSpeed();
        if (speedPerTick <= 1.0E-4D) {
            return 0;
        }
        List<Vec3> waypoints = trace.waypoints();
        if (waypoints.size() < 2) {
            return 0;
        }
        int completed = Math.max(0, Math.min(trace.completedPointCount(), waypoints.size() - 1));
        // 从当前实时位置到下一个未完成路点，再沿剩余路点累加到终点（仅 XZ 平面）。
        double remaining = 0.0D;
        Vec3 cursor = new Vec3(trace.currentX(), 0.0D, trace.currentZ());
        for (int i = completed; i < waypoints.size(); i++) {
            Vec3 wp = new Vec3(waypoints.get(i).x, 0.0D, waypoints.get(i).z);
            remaining += cursor.distanceTo(wp);
            cursor = wp;
        }
        if (remaining <= 0.0D) {
            return 0;
        }
        double speedPerSecond = speedPerTick * 20.0D;
        return (int) Math.ceil(remaining / speedPerSecond);
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

    /**
     * 把载具容器内的物品聚合成货物清单（同种物品累加数量），供手动轨迹携带显示。
     * 收货人留空（手动发车无市场买家）。最多 64 项。
     */
    public static List<MarketWebMapDtos.CargoItem> cargoFromItems(Iterable<net.minecraft.world.item.ItemStack> items) {
        if (items == null) {
            return List.of();
        }
        java.util.LinkedHashMap<String, int[]> byName = new java.util.LinkedHashMap<>();
        for (net.minecraft.world.item.ItemStack stack : items) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            String name = stack.getHoverName().getString();
            byName.computeIfAbsent(name, ignored -> new int[1])[0] += stack.getCount();
        }
        if (byName.isEmpty()) {
            return List.of();
        }
        List<MarketWebMapDtos.CargoItem> out = new ArrayList<>(byName.size());
        for (Map.Entry<String, int[]> entry : byName.entrySet()) {
            out.add(new MarketWebMapDtos.CargoItem(entry.getKey(), entry.getValue()[0], ""));
        }
        return out;
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
     * 清理无效/孤儿轨迹。核心判据：轨迹对应的载具实体是否仍存活（跨所有维度查）。
     * 载具不存在 = 船/车已被打掉/卸载/崩溃丢失 → 轨迹必然是僵尸，删之。这比看订单状态可靠
     * （船被打掉时订单状态常仍停在 SAILING，旧的"看状态"判据会漏掉这种残留轨迹）。
     * @param force true=手动指令立即清理（实体不存在即删，无时间宽限）；
     *              false=定期兜底（实体不存在 + 距上次更新超阈值才删，给未加载实体留缓冲）。
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
            // 解析轨迹对应的载具 uuid：订单轨迹用订单的 boatUuid；手动轨迹 id 形如 "manual-<uuid>"。
            java.util.UUID vehicleUuid = resolveTraceVehicleUuid(marketData, trace, id);
            boolean vehicleAlive = vehicleUuid != null && isEntityAliveAnywhere(server, vehicleUuid);
            if (vehicleAlive) {
                continue; // 载具还在跑，保留轨迹
            }
            // 载具不存在 = 僵尸轨迹。force 立即删；定期兜底要求距上次更新已超阈值（避免误删刚卸载/未加载的实体）。
            long idleTicks = Math.max(0L, now - trace.updatedGameTime());
            long staleThreshold = (trace.manual() || id.startsWith("manual-"))
                    ? STALE_MANUAL_TRACE_TICKS : STALE_ORDER_TRACE_TICKS;
            if (force || idleTicks > staleThreshold) {
                toRemove.add(id);
            }
        }
        for (String id : toRemove) {
            traceData.removeTrace(id);
        }
        return toRemove.size();
    }

    /** 解析轨迹对应的载具 uuid（订单 boatUuid / 手动 id 后缀），无法解析返回 null。 */
    private static java.util.UUID resolveTraceVehicleUuid(MarketSavedData marketData, ShippingTraceRecord trace, String id) {
        if (id.startsWith("manual-")) {
            return parseUuid(id.substring("manual-".length()));
        }
        if (marketData != null) {
            ShippingOrder order = marketData.getShippingOrder(id);
            if (order != null && !order.boatUuid().isBlank()) {
                return parseUuid(order.boatUuid());
            }
        }
        // 回退：用 shipperUuid 无意义（那是玩家），无 boat uuid 时返回 null → 走时间阈值兜底。
        return null;
    }

    private static java.util.UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return java.util.UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 跨所有维度查实体是否存活。 */
    private static boolean isEntityAliveAnywhere(net.minecraft.server.MinecraftServer server, java.util.UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(uuid) != null) {
                return true;
            }
        }
        return false;
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
                                                 double currentX, double currentZ,
                                                 List<MarketWebMapDtos.CargoItem> cargo) {
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
                waypoints, 0, 0.0D, gameTime, gameTime, currentX, currentZ, 0.0D,
                cargo == null ? List.of() : cargo, true);
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

    /** 写入载具实时坐标 + 进度 + 速度（一次提交，speed 单位 blocks/tick）。 */
    public static void updateLivePositionProgressAndSpeed(Level level, String traceId,
                                                         double x, double z,
                                                         int completedPointCount, double progressRatio,
                                                         double speed) {
        if (level == null || level.isClientSide() || traceId == null || traceId.isBlank()) {
            return;
        }
        ShippingTraceSavedData data = ShippingTraceSavedData.get(level);
        ShippingTraceRecord trace = data.getTrace(traceId);
        if (trace != null) {
            long gameTime = level.getGameTime();
            // withProgress 保留 currentX/Z，故先 withLivePosition 再 withProgress 再 withSpeed。
            data.putTrace(trace.withLivePosition(x, z, gameTime)
                    .withProgress(completedPointCount, progressRatio, gameTime)
                    .withSpeed(Math.max(0.0D, speed), gameTime));
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
