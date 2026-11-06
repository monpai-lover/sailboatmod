package com.monpai.sailboatmod.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.market.*;
import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.transport.rail.RailDispatchReceipt;
import com.monpai.sailboatmod.transport.rail.RailRoute;
import com.monpai.sailboatmod.transport.rail.RailStationRef;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

import java.lang.reflect.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class PhysicalMarketDispatchTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void failedPhysicalStartRollsBackOnlyItsReceiptAndKeepsOrderWaiting() throws Exception {
        Fixture f = fixture(false);
        assertFalse(dispatch(f));
        assertEquals(1, f.carrier.rolledBack);
        assertEquals(0, f.carrier.committed);
        assertEquals(7, f.carrier.personalCargo);
        assertEquals(0, f.carrier.loaded);
        assertTrue(f.carrier.manifest.isEmpty());
        assertEquals("WAITING_SHIPMENT", f.market.getPurchaseOrder("order").status());
        assertEquals(4, f.market.getListing("listing").reservedCount());
        assertTrue(f.market.getShippingOrders().isEmpty());
    }

    @Test
    void physicalDispatchUsesOriginalOrderAndStableRegistrationExactlyOnce() throws Exception {
        Fixture f = fixture(true);
        assertTrue(dispatch(f));
        assertEquals("IN_TRANSIT", f.market.getPurchaseOrder("order").status());
        assertEquals(0, f.market.getListing("listing").reservedCount());
        assertEquals(1, f.market.getShippingOrders().size());
        ShippingOrder shipment = f.market.getShippingOrders().getFirst();
        assertEquals("order", shipment.purchaseOrderId());
        assertEquals(f.carrier.uuid.toString(), shipment.boatUuid());
        assertEquals(shipment.shippingOrderId(), f.carrier.manifest.getFirst().shippingOrderId());
        assertEquals(1, f.carrier.committed);
        f.carrier.loaded =
                0; // Even an empty carrier cannot replay an already committed stale dispatch plan.
        assertFalse(dispatch(f));
        assertEquals(1, f.market.getShippingOrders().size());
        assertEquals(1, f.carrier.committed);
    }

    @Test
    void backgroundDockAssignmentRejectsFailedAutopilotWithoutOnlineOperator() throws Exception {
        Fixture f = fixture(false);
        Field assignments = DockBlockEntity.class.getDeclaredField("assignments");
        assignments.setAccessible(true);
        assignments.set(f.dock, new java.util.HashMap<>());
        Field pos =
                net.minecraft.world.level.block.entity.BlockEntity.class.getDeclaredField(
                        "worldPosition");
        pos.setAccessible(true);
        pos.set(f.dock, new BlockPos(0, 64, 0));
        for (String name : List.of("zoneMinX", "zoneMinZ", "zoneMaxX", "zoneMaxZ")) {
            Field zone = DockBlockEntity.class.getDeclaredField(name);
            zone.setAccessible(true);
            zone.setInt(f.dock, name.contains("Min") ? -8 : 8);
        }
        Method assign =
                DockBlockEntity.class.getDeclaredMethod(
                        "assignBoatToRouteIndex",
                        LogisticsCarrier.class,
                        int.class,
                        boolean.class,
                        Player.class,
                        boolean.class);
        assign.setAccessible(true);

        assertEquals(false, assign.invoke(f.dock, f.carrier.proxy, 0, true, null, true));
        assertTrue(((java.util.Map<?, ?>) assignments.get(f.dock)).isEmpty());
    }

    @Test
    void airDispatchKeepsSubtypeAndUsesTransitStatusWithoutAConstructedLandRoute() throws Exception {
        for (String mode : List.of("AIRSHIP", "HELICOPTER", "AIRPLANE")) {
            Fixture f = fixture(true);
            f.carrier.kind = LogisticsCarrier.Kind.AIR;
            f.carrier.mode = mode;
            assertTrue(dispatch(f));
            ShippingOrder order = f.market.getShippingOrders().getFirst();
            assertEquals(mode, order.transportMode());
            assertEquals("IN_TRANSIT", order.status());
            assertEquals(1, f.carrier.committed);
            assertEquals(0, f.carrier.rolledBack);
        }
    }

    @Test
    void failedAirStartKeepsCargoAndPurchaseEscrowUncommitted() throws Exception {
        Fixture f = fixture(false);
        f.carrier.kind = LogisticsCarrier.Kind.AIR;
        f.carrier.mode = "AIRPLANE";
        assertFalse(dispatch(f));
        assertEquals(1, f.carrier.rolledBack);
        assertEquals(0, f.carrier.committed);
        assertTrue(f.market.getShippingOrders().isEmpty());
        assertEquals("WAITING_SHIPMENT", f.market.getPurchaseOrder("order").status());
        assertEquals(4, f.market.getListing("listing").reservedCount());
    }

    @Test
    void rejectedRailStartRollsBackCargoAndLeavesSellerOrderQueued() throws Exception {
        Fixture f = fixture(false, true);
        assertFalse(dispatch(f));
        assertEquals(1, f.carrier.rolledBack);
        assertEquals(0, f.carrier.committed);
        assertEquals(0, f.carrier.loaded);
        assertEquals("WAITING_SHIPMENT", f.market.getPurchaseOrder("order").status());
        assertEquals(4, f.market.getListing("listing").reservedCount());
        assertTrue(f.market.getShippingOrders().isEmpty());
    }

    @Test
    void successfulNativeRailStartCommitsExactTerminalShipmentOnce() throws Exception {
        Fixture f = fixture(true, true);
        assertTrue(dispatch(f));
        assertEquals("IN_TRANSIT", f.market.getPurchaseOrder("order").status());
        assertEquals(0, f.market.getListing("listing").reservedCount());
        ShippingOrder shipment = f.market.getShippingOrders().getFirst();
        assertEquals("RAIL", shipment.transportMode());
        assertEquals("IN_TRANSIT", shipment.status());
        assertEquals(48, shipment.distanceMeters());
        assertEquals(0, shipment.etaSeconds());
        assertEquals(new BlockPos(48, 64, 0), shipment.targetDockPos());
        assertEquals(new BlockPos(100, 64, 0), f.market.getPurchaseOrder("order").targetWarehousePos());
        assertEquals(shipment.shippingOrderId(), f.carrier.manifest.getFirst().shippingOrderId());
        assertEquals(1, f.carrier.committed);
        assertEquals(0, f.carrier.rolledBack);
        assertFalse(dispatch(f));
        assertEquals(1, f.market.getShippingOrders().size());
        assertEquals(1, f.carrier.committed);
    }

    private static boolean dispatch(Fixture f) throws Exception {
        Method method =
                MarketBlockEntity.class.getDeclaredMethod(
                        "dispatchShipmentPlan",
                        String.class,
                        String.class,
                        Player.class,
                        LogisticsCarrier.class,
                        DockBlockEntity.class,
                        MarketSavedData.class,
                        f.plan.getClass(),
                        TransportTerminalKind.class);
        method.setAccessible(true);
        return (boolean)
                method.invoke(
                        f.block,
                        "owner",
                        "Owner",
                        null,
                        f.carrier.proxy,
                        f.dock,
                        f.market,
                        f.plan,
                        f.carrier.rail ? TransportTerminalKind.fromName("RAIL")
                                : f.carrier.kind == LogisticsCarrier.Kind.AIR ? TransportTerminalKind.AIRPORT : TransportTerminalKind.PORT);
    }

    private static Fixture fixture(boolean starts) throws Exception {
        return fixture(starts, false);
    }

    private static Fixture fixture(boolean starts, boolean rail) throws Exception {
        MarketSavedData market = new MarketSavedData();
        MarketListing listing =
                new MarketListing(
                        "listing",
                        "seller",
                        "Seller",
                        new ItemStack(Items.IRON_INGOT),
                        10,
                        8,
                        4,
                        BlockPos.ZERO,
                        "Source",
                        "",
                        "",
                        0,
                        "");
        PurchaseOrder order =
                new PurchaseOrder(
                        "order",
                        "listing",
                        "buyer",
                        "Buyer",
                        4,
                        40,
                        BlockPos.ZERO,
                        "Source",
                        new BlockPos(100, 64, 0),
                        "Target",
                        "WAITING_SHIPMENT",
                        "SELLER_SHIP",
                        BlockPos.ZERO);
        market.putListing(listing);
        market.putPurchaseOrder(order);
        Class<?> selectionType = nested("ShipmentOrderSelection");
        var selectionCtor = selectionType.getDeclaredConstructors()[0];
        selectionCtor.setAccessible(true);
        Object selection = selectionCtor.newInstance(order, null, listing);
        Class<?> planType = nested("ShipmentPlan");
        var planCtor = planType.getDeclaredConstructors()[0];
        planCtor.setAccessible(true);
        RailRoute route = rail ? new RailRoute(
                new RailStationRef("minecraft:overworld", new BlockPos(0, 64, 4), UUID.randomUUID()),
                new RailStationRef("minecraft:overworld", new BlockPos(48, 64, 4), UUID.randomUUID()),
                BlockPos.ZERO, new BlockPos(48, 64, 0), order.targetWarehousePos(), 48, "A → B") : null;
        Object plan = planCtor.newInstance(rail ? -1 : 0, null, null,
                rail ? route.targetTerminal() : order.targetDockPos(), "Target",
                List.of(new ItemStack(Items.IRON_INGOT, 4)), List.of(selection), route);
        TestDock dock = allocate(TestDock.class);
        dock.starts = starts;
        Field pos =
                net.minecraft.world.level.block.entity.BlockEntity.class.getDeclaredField(
                        "worldPosition");
        pos.setAccessible(true);
        pos.set(dock, BlockPos.ZERO);
        Carrier carrier = new Carrier();
        carrier.rail = rail;
        if (!rail) return new Fixture(allocate(MarketBlockEntity.class), dock, market, plan, carrier);
        carrier.kind = LogisticsCarrier.Kind.LAND;
        TestRailMarket block = allocate(TestRailMarket.class);
        block.starts = starts;
        block.carrier = carrier;
        block.market = market;
        return new Fixture(block, dock, market, plan, carrier);
    }

    private static Class<?> nested(String name) {
        for (Class<?> type : MarketBlockEntity.class.getDeclaredClasses())
            if (type.getSimpleName().equals(name)) return type;
        throw new AssertionError(name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (T) ((Unsafe) field.get(null)).allocateInstance(type);
    }

    private record Fixture(
            MarketBlockEntity block,
            TestDock dock,
            MarketSavedData market,
            Object plan,
            Carrier carrier) {}

    private static class TestRailMarket extends MarketBlockEntity {
        boolean starts;
        Carrier carrier;
        MarketSavedData market;

        private TestRailMarket() {
            super(BlockPos.ZERO, null);
        }

        @Override
        protected RailDispatchReceipt prepareRailDispatch(LogisticsCarrier actualCarrier, RailRoute route,
                                                           List<ShipmentManifestEntry> manifest) {
            assertSame(carrier.proxy, actualCarrier);
            assertEquals("order", manifest.getFirst().purchaseOrderId());
            assertEquals(4, manifest.getFirst().quantity());
            assertEquals("WAITING_SHIPMENT", market.getPurchaseOrder("order").status());
            assertEquals(4, market.getListing("listing").reservedCount());
            assertTrue(market.getShippingOrders().isEmpty());
            carrier.manifest = new ArrayList<>(manifest);
            carrier.loaded = 4;
            return new RailDispatchReceipt() {
                @Override public boolean start() {
                    assertEquals("WAITING_SHIPMENT", market.getPurchaseOrder("order").status());
                    assertTrue(market.getShippingOrders().isEmpty());
                    return starts;
                }

                @Override public void commit() {
                    assertEquals("IN_TRANSIT", market.getPurchaseOrder("order").status());
                    assertEquals(0, market.getListing("listing").reservedCount());
                    assertEquals(1, market.getShippingOrders().size());
                    carrier.committed++;
                }

                @Override public void rollback() {
                    carrier.rolledBack++;
                    carrier.loaded = 0;
                    carrier.manifest.clear();
                }
            };
        }
    }

    private static class TestDock extends DockBlockEntity {
        boolean starts;

        private TestDock() {
            super(BlockPos.ZERO, null);
        }

        @Override
        public String getDockName() {
            return "Source";
        }

        @Override
        public List<RouteDefinition> getRoutesForMap() {
            return List.of(new RouteDefinition("Route", List.of(Vec3.ZERO, new Vec3(100, 64, 0))));
        }

        @Override
        protected List<RouteDefinition> availableRoutes() {
            return getRoutesForMap();
        }

        public boolean assignLoadedBoatToRouteIndex(
                LogisticsCarrier carrier, int routeIndex, boolean autoStart, Player operator) {
            if ("create-train".equals(carrier.providerId())) throw new AssertionError("Rail must use native dispatch");
            return starts;
        }
    }

    private static final class Carrier implements InvocationHandler {
        final UUID uuid = UUID.randomUUID();
        final LogisticsCarrier proxy =
                (LogisticsCarrier)
                        Proxy.newProxyInstance(
                                LogisticsCarrier.class.getClassLoader(),
                                new Class<?>[] {LogisticsCarrier.class},
                                this);
        final int personalCargo = 7;
        LogisticsCarrier.Kind kind = LogisticsCarrier.Kind.WATER;
        boolean rail;
        String mode = "PORT";
        List<ShipmentManifestEntry> manifest = new ArrayList<>();
        int committed, rolledBack, loaded;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "transportKind" -> kind;
                case "providerId" -> rail ? "create-train" : "entity";
                case "transportMode" -> mode;
                case "getTransportUuid" -> uuid;
                case "getTransportId" -> -70;
                case "getTransportName" -> Component.literal("Actual physical vessel");
                case "getOwnerUuid" -> "owner";
                case "getOwnerName" -> "Owner";
                case "transportPosition" -> new Vec3(0.5D, 64.0D, 0.5D);
                case "isTransportAlive" -> true;
                case "isAutopilotActive", "startAutopilotFromRouteStart" -> false;
                case "getRentalPrice" -> 0;
                case "hasCargo" -> loaded != 0;
                case "canLoadCargo" -> true;
                case "loadCargoForDispatch" -> {
                    if (rail) throw new AssertionError("Rail must stage cargo with its native receipt");
                    loaded = 4;
                    yield new CargoLoadReceipt() {
                        boolean done;

                        public void commit() {
                            if (!done) {
                                done = true;
                                committed++;
                            }
                        }

                        public void rollback() {
                            if (!done) {
                                done = true;
                                rolledBack++;
                                loaded = 0;
                            }
                        }
                    };
                }
                case "unloadAllCargo" ->
                        throw new AssertionError("dispatch failure must use its receipt");
                case "setPendingShipmentManifest" -> {
                    manifest = new ArrayList<>((List<ShipmentManifestEntry>) args[0]);
                    yield null;
                }
                case "clearPendingMarketDelivery" -> {
                    manifest.clear();
                    yield null;
                }
                default ->
                        method.isDefault()
                                ? InvocationHandler.invokeDefault(proxy, method, args)
                                : null;
            };
        }
    }
}
