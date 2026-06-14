package com.monpai.sailboatmod.market.logistics;

import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.ShippingOrder;
import com.monpai.sailboatmod.market.web.MarketPlayerIdentity;
import com.monpai.sailboatmod.market.web.map.MarketWebMapConstants;
import com.monpai.sailboatmod.market.web.map.MarketWebMapDtos;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
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
                gameTime
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

    public static List<MarketWebMapDtos.ShipmentTrace> toDtos(List<ShippingTraceRecord> traces) {
        List<MarketWebMapDtos.ShipmentTrace> out = new ArrayList<>();
        for (ShippingTraceRecord trace : traces == null ? List.<ShippingTraceRecord>of() : traces) {
            out.add(toDto(trace));
        }
        return out;
    }

    public static MarketWebMapDtos.ShipmentTrace toDto(ShippingTraceRecord trace) {
        List<MarketWebMapDtos.Point> points = new ArrayList<>();
        for (Vec3 waypoint : trace.waypoints()) {
            points.add(new MarketWebMapDtos.Point(waypoint.x, waypoint.z));
        }
        String label = (trace.sourceName().isBlank() ? "Source" : trace.sourceName())
                + " -> "
                + (trace.targetName().isBlank() ? "Target" : trace.targetName());
        return new MarketWebMapDtos.ShipmentTrace(
                trace.shippingOrderId(),
                label,
                trace.transportMode(),
                trace.status(),
                trace.sourceName(),
                trace.targetName(),
                points,
                trace.completedPointCount(),
                trace.progressRatio()
        );
    }

    public static boolean isMapVisibleStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return "SAILING".equals(normalized) || "IN_TRANSIT".equals(normalized) || "ARRIVED".equals(normalized);
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
