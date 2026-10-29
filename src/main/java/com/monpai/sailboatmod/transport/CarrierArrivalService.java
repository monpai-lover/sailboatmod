package com.monpai.sailboatmod.transport;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Uses the same dock delivery/warehouse/escrow authority for registered physical carriers. */
public final class CarrierArrivalService {
    private CarrierArrivalService() {}

    public record DeliveryResult(List<ShipmentManifestEntry> remainingManifest, boolean delivered) {
        public DeliveryResult {
            remainingManifest = List.copyOf(remainingManifest);
        }
    }

    public static DeliveryResult deliverAtDock(
            ServerLevel level,
            DockBlockEntity dock,
            LogisticsCarrier carrier,
            String routeName,
            String shipperName,
            String startName,
            String endName,
            long departureMillis,
            double distance,
            List<ShipmentManifestEntry> manifest) {
        if (level == null || dock == null || carrier == null) {
            return new DeliveryResult(manifest == null ? List.of() : manifest, false);
        }
        List<ShipmentManifestEntry> safeManifest =
                manifest == null ? List.of() : List.copyOf(manifest);
        DockBlockEntity.ManifestSplit split =
                DockBlockEntity.splitManifestByDestination(level, dock.getBlockPos(), safeManifest);
        List<ItemStack> actualCargo = carrier.unloadAllCargo();
        List<ItemStack> pool = new ArrayList<>(actualCargo);
        List<ItemStack> delivering =
                DockBlockEntity.resolveDeliverCargo(
                        pool, split.deliverHere(), !split.keepOnboard().isEmpty());
        if (!pool.isEmpty() && !carrier.loadCargo(pool)) {
            // A provider must be able to restore cargo into the same selected hold it just emptied.
            throw new IllegalStateException("Unable to restore retained carrier cargo");
        }
        if (delivering.isEmpty()) return new DeliveryResult(safeManifest, false);
        long departure = departureMillis > 0 ? departureMillis : System.currentTimeMillis();
        dock.receiveShipment(
                carrier,
                routeName,
                shipperName,
                startName,
                endName,
                departure,
                Math.max(0L, System.currentTimeMillis() - departure),
                distance,
                delivering,
                split.deliverHere());
        return new DeliveryResult(split.keepOnboard(), true);
    }
}
