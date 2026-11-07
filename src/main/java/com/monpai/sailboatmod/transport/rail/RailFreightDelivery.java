package com.monpai.sailboatmod.transport.rail;

import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity.RailDeliveryResult;
import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.MarketSavedData;
import com.monpai.sailboatmod.market.ProcurementService;
import com.monpai.sailboatmod.market.PurchaseOrder;
import com.monpai.sailboatmod.market.ShipmentManifestEntry;
import com.monpai.sailboatmod.market.ShippingOrder;
import com.monpai.sailboatmod.market.commodity.CommodityKeyResolver;
import com.monpai.sailboatmod.market.logistics.ShippingTraceRecord;
import com.monpai.sailboatmod.market.logistics.ShippingTraceSavedData;
import com.monpai.sailboatmod.market.logistics.ShippingTraceService;
import com.monpai.sailboatmod.market.logistics.TransportModes;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.transport.rail.RailFreightSavedData.Job;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.jetbrains.annotations.Nullable;

/** Rail orders use their own exact custody proof and the existing market's escrow settlement. */
final class RailFreightDelivery {
    private static final int MAX_CARGO_STACKS = 4096;
    private static final int MAX_TRACE_POINTS = 128;

    private RailFreightDelivery() {}

    static boolean validWaitingOrders(ServerLevel level, Job job) {
        MarketSavedData market = MarketSavedData.get(level);
        Map<String, Long> reserved = new HashMap<>();
        for (ShipmentManifestEntry entry : job.manifest()) {
            PurchaseOrder order = market.getPurchaseOrder(entry.purchaseOrderId());
            MarketListing listing = market.getListing(entry.listingId());
            if (!matchesOrder(job, entry, order) || !"WAITING_SHIPMENT".equals(order.status())
                    || entry.quantity() > order.quantity() || listing == null || listing.itemStack().isEmpty()
                    || !listing.sourceDockPos().equals(order.sourceDockPos())
                    || !ItemStack.isSameItemSameComponents(listing.itemStack(), entry.itemStack())
                    || market.getShippingOrder(entry.shippingOrderId()) != null
                    || market.getShippingOrderForPurchaseOrder(entry.purchaseOrderId()) != null
                    || market.getEscrow(entry.purchaseOrderId()) == null
                    || !RailFreightService.sameTown(level, order.sourceDockPos(), job.route().source().stationPos())) return false;
            long total = reserved.merge(entry.listingId(), (long) entry.quantity(), Long::sum);
            if (total > listing.reservedCount()) return false;
        }
        return true;
    }

    static List<ItemStack> markedCargo(Job job) {
        List<ItemStack> result = new ArrayList<>();
        for (ShipmentManifestEntry entry : job.manifest()) {
            UUID custody = custody(job, entry);
            List<ItemStack> raw = expected(entry);
            if (raw.isEmpty() || raw.size() > MAX_CARGO_STACKS - result.size()) return List.of();
            for (ItemStack stack : raw) result.add(RailCargoCustody.mark(stack, custody));
        }
        return List.copyOf(result);
    }

    /** Returns a recoverable wait reason, or null after every order is delivered. */
    static @Nullable String deliver(ServerLevel level, RailFreightNative api, Job job,
                                    TownWarehouseBlockEntity warehouse, List<IItemHandlerModifiable> handlers) {
        MarketSavedData market = MarketSavedData.get(level);
        for (ShipmentManifestEntry entry : job.manifest()) {
            PurchaseOrder order = market.getPurchaseOrder(entry.purchaseOrderId());
            ShippingOrder shipment = market.getShippingOrder(entry.shippingOrderId());
            if (!matchesOrder(job, entry, order) || order.quantity() != entry.quantity() || !matchesShipment(job, entry, shipment)) {
                return "order_mismatch";
            }
            boolean claimed = "CLAIMED".equals(order.status());
            if (!claimed && !"IN_TRANSIT".equals(order.status())) return "order_mismatch";
            UUID buyer = uuid(order.buyerUuid());
            if (buyer == null) return "order_mismatch";
            boolean received = warehouse.hasRailDeliveryReceipt(entry.shippingOrderId(), buyer, entry.quantity());
            if (!received && (claimed || "DELIVERED".equals(shipment.status()) || market.getEscrow(order.orderId()) == null)) {
                return "order_mismatch";
            }

            UUID custody = custody(job, entry);
            List<Slot> physical = markedSlots(handlers, custody);
            if (!received || !physical.isEmpty()) {
                List<ItemStack> actual = physical.stream().map(Slot::before).toList();
                if (!RailCargoCustody.matchesExact(actual, expected(entry), custody)) return "cargo_mismatch";
                List<ItemStack> restored = actual.stream().map(stack -> RailCargoCustody.restore(stack, custody)).toList();
                if (!received && !warehouse.canInsertCargo(buyer, restored)) return "warehouse_full";
                if (!extract(physical)) return "cargo_mismatch";
                if (!received) {
                    RailDeliveryResult result;
                    try {
                        result = warehouse.insertRailCargoOnce(entry.shippingOrderId(), buyer, restored);
                    } catch (RuntimeException failure) {
                        if (!warehouse.hasRailDeliveryReceipt(entry.shippingOrderId(), buyer, entry.quantity())) restore(physical);
                        api.markDirty();
                        throw failure;
                    }
                    if (result == RailDeliveryResult.REJECTED) {
                        restore(physical);
                        api.markDirty();
                        return "warehouse_full";
                    }
                }
                api.markDirty();
            }
            if (!warehouse.hasRailDeliveryReceipt(entry.shippingOrderId(), buyer, entry.quantity())) return "warehouse_full";
            finalizeOrder(level, market, warehouse, order, shipment, entry);
        }
        return null;
    }

    static boolean empty(List<IItemHandlerModifiable> handlers) {
        for (IItemHandlerModifiable handler : handlers) {
            for (int slot = 0; slot < handler.getSlots(); slot++) if (!handler.getStackInSlot(slot).isEmpty()) return false;
        }
        return true;
    }

    /** Undo a staged load only when every physical stack is still present and exactly attributable. */
    static boolean rollbackPrepared(RailFreightNative api, Job job, List<IItemHandlerModifiable> handlers) {
        List<Slot> all = new ArrayList<>();
        for (ShipmentManifestEntry entry : job.manifest()) {
            UUID custody = custody(job, entry);
            List<Slot> actual = markedSlots(handlers, custody);
            if (!RailCargoCustody.matchesExact(actual.stream().map(Slot::before).toList(), expected(entry), custody)) return false;
            all.addAll(actual);
        }
        if (!extract(all)) return false;
        api.markDirty();
        return true;
    }

    /** Order trace uses actual train positions only, with native remaining distance during the expected leg. */
    static void recordPosition(ServerLevel level, Job job, RailFreightNative.TrainView train) {
        Vec3 position = train.position();
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) return;
        MarketSavedData market = MarketSavedData.get(level);
        ShippingTraceSavedData traces = ShippingTraceSavedData.get(level);
        long now = level.getGameTime();
        var town = TownService.getTownAt(level, job.route().source().stationPos());
        double progress = outboundProgress(job.route(), train);
        // Shipping traces carry blocks per tick, the native train speed unit.
        double speed = Double.isFinite(train.speed()) ? Math.abs(train.speed()) : 0;
        for (ShipmentManifestEntry entry : job.manifest()) {
            ShippingOrder shipment = market.getShippingOrder(entry.shippingOrderId());
            if (!matchesShipment(job, entry, shipment) || "DELIVERED".equals(shipment.status())) continue;
            ShippingTraceRecord existing = traces.getTrace(entry.shippingOrderId());
            List<Vec3> points = new ArrayList<>(existing == null ? List.of() : existing.waypoints());
            if (points.size() < MAX_TRACE_POINTS && (points.isEmpty() || points.getLast().distanceToSqr(position) >= 1)) points.add(position);
            if (existing != null && existing.currentX() == position.x && existing.currentZ() == position.z
                    && existing.progressRatio() == progress && existing.currentSpeed() == speed) continue;
            traces.putTrace(new ShippingTraceRecord(entry.shippingOrderId(), shipment.shipperUuid(), job.route().source().dimensionId(),
                    TransportModes.RAIL, shipment.status(), town == null ? "" : town.nationId(), town == null ? "" : town.townId(),
                    shipment.sourceTerminalName(), shipment.targetTerminalName(), points, points.size(), progress,
                    existing == null ? now : existing.startedGameTime(), now, position.x, position.z, speed, List.of(), false));
        }
    }

    private static double outboundProgress(RailRoute route, RailFreightNative.TrainView train) {
        if (train.parkedAt(route.target())) return 1;
        if (route.stopovers().isEmpty()) {
            return route.target().equals(train.destination()) && Double.isFinite(train.remaining())
                    ? Math.clamp(1 - train.remaining() / route.distanceMeters(), 0, 1) : 0;
        }
        var visits = route.outboundStations();
        if (train.destination() != null) {
            int leg = visits.indexOf(train.destination());
            if (leg < 0) return 0;
            double progress = Double.isFinite(train.legDistance()) && train.legDistance() > 0 && Double.isFinite(train.remaining())
                    ? Math.clamp(1 - train.remaining() / train.legDistance(), 0, 1) : 0;
            return (leg + progress) / visits.size();
        }
        if (train.station() == null) return 0;
        int parked = visits.indexOf(train.station());
        return parked >= 0 && train.parkedAt(visits.get(parked)) ? (parked + 1.0) / visits.size() : 0;
    }

    private static boolean matchesOrder(Job job, ShipmentManifestEntry entry, @Nullable PurchaseOrder order) {
        return order != null && entry.isMarketOrder() && entry.shippingOrderId().matches("[0-9a-f]{32}")
                && entry.listingId().equals(order.listingId()) && entry.recipientUuid().equals(order.buyerUuid())
                && uuid(order.buyerUuid()) != null && job.route().buyerWarehouse().equals(order.targetWarehousePos());
    }

    private static boolean matchesShipment(Job job, ShipmentManifestEntry entry, @Nullable ShippingOrder shipment) {
        return shipment != null && entry.purchaseOrderId().equals(shipment.purchaseOrderId())
                && job.trainId().toString().equals(shipment.boatUuid()) && TransportModes.RAIL.equals(shipment.transportMode())
                && job.route().sourceTerminal().equals(shipment.sourceDockPos()) && job.route().targetTerminal().equals(shipment.targetDockPos())
                && ("IN_TRANSIT".equals(shipment.status()) || "SAILING".equals(shipment.status()) || "DELIVERED".equals(shipment.status()));
    }

    private static void finalizeOrder(ServerLevel level, MarketSavedData market, TownWarehouseBlockEntity warehouse,
                                      PurchaseOrder order, ShippingOrder shipment, ShipmentManifestEntry entry) {
        if (!"DELIVERED".equals(shipment.status())) {
            market.putShippingOrder(new ShippingOrder(shipment.shippingOrderId(), shipment.purchaseOrderId(), shipment.shipperUuid(),
                    shipment.shipperName(), shipment.boatUuid(), shipment.boatName(), shipment.boatMode(), shipment.transportMode(),
                    shipment.routeName(), shipment.sourceDockPos(), shipment.sourceDockName(), shipment.targetDockPos(), shipment.targetDockName(),
                    shipment.sourceTerminalName(), shipment.targetTerminalName(), shipment.distanceMeters(), shipment.etaSeconds(),
                    shipment.rentalFee(), "DELIVERED"));
            ShippingTraceService.updateStatus(level, shipment.shippingOrderId(), "DELIVERED");
        }
        if (!"CLAIMED".equals(order.status())) {
            // putPurchaseOrder settles the already-held payment using the same rules as other transport modes.
            market.putPurchaseOrder(new PurchaseOrder(order.orderId(), order.listingId(), order.buyerUuid(), order.buyerName(),
                    order.quantity(), order.totalPrice(), order.sourceDockPos(), order.sourceDockName(), order.targetDockPos(),
                    order.targetDockName(), "CLAIMED", order.fulfillment(), order.targetWarehousePos()));
            ProcurementService.markDeliveredByOrder(level, order.orderId(), shipment.shippingOrderId(), warehouse.getTownId(),
                    CommodityKeyResolver.resolve(entry.itemStack()), entry.quantity());
        }
    }

    private static List<ItemStack> expected(ShipmentManifestEntry entry) {
        ItemStack template = entry.itemStack();
        if (template.isEmpty() || RailCargoCustody.hasMarker(template) || entry.quantity() <= 0) return List.of();
        int limit = Math.max(1, template.getMaxStackSize());
        if ((long) entry.quantity() > (long) limit * MAX_CARGO_STACKS) return List.of();
        List<ItemStack> result = new ArrayList<>();
        for (int remaining = entry.quantity(); remaining > 0; remaining -= limit) {
            result.add(template.copyWithCount(Math.min(limit, remaining)));
        }
        return result;
    }

    private static UUID custody(Job job, ShipmentManifestEntry entry) {
        return UUID.nameUUIDFromBytes((job.jobId() + "/" + entry.shippingOrderId()).getBytes(StandardCharsets.UTF_8));
    }

    private record Slot(IItemHandlerModifiable handler, int index, ItemStack before) {}

    private static List<Slot> markedSlots(List<IItemHandlerModifiable> handlers, UUID id) {
        List<Slot> slots = new ArrayList<>();
        for (IItemHandlerModifiable handler : handlers) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (!stack.isEmpty() && id.equals(RailCargoCustody.custodyId(stack))) slots.add(new Slot(handler, slot, stack.copy()));
            }
        }
        return slots;
    }

    private static boolean extract(List<Slot> slots) {
        List<Slot> touched = new ArrayList<>();
        try {
            for (Slot slot : slots) {
                touched.add(slot);
                ItemStack taken = slot.handler().extractItem(slot.index(), slot.before().getCount(), false);
                if (!ItemStack.matches(taken, slot.before()) || !slot.handler().getStackInSlot(slot.index()).isEmpty()) {
                    restore(touched);
                    return false;
                }
            }
            return true;
        } catch (RuntimeException failure) {
            restore(touched);
            throw failure;
        }
    }

    private static void restore(List<Slot> slots) {
        for (Slot slot : slots) slot.handler().setStackInSlot(slot.index(), slot.before().copy());
    }

    private static @Nullable UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException failure) { return null; }
    }
}
