package com.monpai.sailboatmod.compat.create;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.market.logistics.ShippingTraceSavedData;
import com.monpai.sailboatmod.market.logistics.ShippingTraceService;
import com.monpai.sailboatmod.market.logistics.rail.RailTraceTracker;
import com.monpai.sailboatmod.market.logistics.rail.RailTrainSnapshot;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Shows Create trains on the web map as rail shipments: every train that runs a schedule or is navigating, with its
 * route on the current leg, its status (in transit, at a station, stuck), timeline and history like boats and carriages.
 * Updated once a second from the server tick. Does nothing when Create is not installed.
 */
public final class CreateRailLogistics {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String MOD_ID = "create";
    private static final int INTERVAL_TICKS = 20;

    private static RailTraceTracker tracker = new RailTraceTracker();
    private static Boolean available;
    private static boolean primed;

    private CreateRailLogistics() {
    }

    public static void tick(MinecraftServer server) {
        if (server == null || server.getTickCount() % INTERVAL_TICKS != 0 || !available()) {
            return;
        }
        ServerLevel overworld = server.overworld();
        List<RailTrainSnapshot> trains;
        try {
            trains = CreateTrainBridge.overworldTrains();
        } catch (LinkageError | RuntimeException incompatible) {
            available = false;
            LOGGER.warn("Create train logistics turned off: this Create version is not supported", incompatible);
            return;
        }
        RailTraceTracker.Result result = tracker.update(trains);
        NationSavedData nations = NationSavedData.get(overworld);
        Set<String> shown = new HashSet<>();
        for (ShippingTraceService.ExternalShipment shipment : result.shipments()) {
            shown.add(shipment.traceId());
            ShippingTraceService.upsertExternalTrace(overworld, withNation(shipment, nations));
        }
        for (String traceId : result.removedTraceIds()) {
            ShippingTraceService.removeExternalTrace(overworld, traceId);
        }
        if (!primed) {
            // Rail traces saved before the restart whose train is gone or no longer scheduled.
            primed = true;
            for (var trace : ShippingTraceSavedData.get(overworld).getTraces()) {
                String id = trace.shippingOrderId();
                if (RailTraceTracker.isRailTrace(id) && !shown.contains(id)) {
                    ShippingTraceService.removeExternalTrace(overworld, id);
                }
            }
        }
    }

    /** Server stopped: forget the legs so another world starts clean. */
    public static void clear() {
        tracker = new RailTraceTracker();
        primed = false;
    }

    private static boolean available() {
        if (available == null) {
            available = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
        }
        return available;
    }

    /** Members of a nation see the trains of everyone in it, as with boats and carriages. */
    private static ShippingTraceService.ExternalShipment withNation(ShippingTraceService.ExternalShipment s, NationSavedData nations) {
        NationMemberRecord member = null;
        try {
            member = s.shipperUuid().isBlank() ? null : nations.getMember(UUID.fromString(s.shipperUuid()));
        } catch (IllegalArgumentException ignored) {
        }
        String nationId = member == null ? "" : member.nationId();
        return new ShippingTraceService.ExternalShipment(s.traceId(), s.shipperUuid(), nationId, s.transportMode(), s.status(),
                s.sourceName(), s.targetName(), s.vehicleName(), s.waypoints(), s.targetPointIndex(), s.progressRatio(),
                s.currentX(), s.currentZ(), s.currentSpeed(), s.cargo());
    }
}
