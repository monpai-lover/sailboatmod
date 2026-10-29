package com.monpai.sailboatmod.market.logistics.rail;

import com.monpai.sailboatmod.market.logistics.ShippingTraceService;
import com.monpai.sailboatmod.market.logistics.TransportModes;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailTraceTrackerTest {
    private static final UUID TRAIN = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000b002");

    private static RailTrainSnapshot moving(double x, String destination, double remaining, double total) {
        return new RailTrainSnapshot(TRAIN, OWNER, "Express 7", x, 0.0D, 0.8D, false, null, true,
                destination, 1000.0D, 0.0D, remaining, total, 0);
    }

    private static RailTrainSnapshot atStation(double x, String station) {
        return new RailTrainSnapshot(TRAIN, OWNER, "Express 7", x, 0.0D, 0.0D, false, station, true,
                null, Double.NaN, Double.NaN, 0.0D, 0.0D, 0);
    }

    @Test
    void aScheduledTrainBecomesARailShipmentHeadingToItsStation() {
        RailTraceTracker tracker = new RailTraceTracker();
        tracker.update(List.of(atStation(0.0D, "Lagos Yard")));
        RailTraceTracker.Result result = tracker.update(List.of(moving(100.0D, "Ibadan Yard", 900.0D, 1000.0D)));

        assertEquals(1, result.shipments().size());
        ShippingTraceService.ExternalShipment shipment = result.shipments().get(0);
        assertEquals(RailTraceTracker.traceId(TRAIN), shipment.traceId());
        assertEquals(TransportModes.RAIL, shipment.transportMode());
        assertEquals("IN_TRANSIT", shipment.status());
        assertEquals("Lagos Yard", shipment.sourceName());
        assertEquals("Ibadan Yard", shipment.targetName());
        assertEquals("Express 7", shipment.vehicleName());
        assertEquals(OWNER.toString(), shipment.shipperUuid());
        assertEquals(0.1D, shipment.progressRatio(), 1.0E-9);
        assertEquals(1000.0D, shipment.waypoints().get(shipment.waypoints().size() - 1).x, 1.0E-9, "ends at the destination");
        assertEquals(shipment.waypoints().size() - 1, shipment.targetPointIndex());
        assertEquals(0.8D, shipment.currentSpeed(), 1.0E-9);
    }

    @Test
    void theRouteRemembersWhereTheTrainWentOnThisLeg() {
        RailTraceTracker tracker = new RailTraceTracker();
        tracker.update(List.of(atStation(0.0D, "Lagos Yard")));
        for (double x = 10; x <= 500; x += 10) {
            tracker.update(List.of(moving(x, "Ibadan Yard", 1000.0D - x, 1000.0D)));
        }
        ShippingTraceService.ExternalShipment shipment = tracker.update(List.of(moving(510.0D, "Ibadan Yard", 490.0D, 1000.0D)))
                .shipments().get(0);
        assertEquals(0.0D, shipment.waypoints().get(0).x, 1.0E-9, "the leg starts at the station it left");
        assertTrue(shipment.waypoints().size() > 10, "breadcrumbs every few dozen blocks: " + shipment.waypoints().size());
        assertTrue(shipment.waypoints().size() <= RailTraceTracker.MAX_POINTS + 2);
    }

    @Test
    void stationsDerailmentsAndLongSignalWaitsShowAsStatuses() {
        RailTraceTracker tracker = new RailTraceTracker();
        assertEquals("ARRIVED", tracker.update(List.of(atStation(0.0D, "Lagos Yard"))).shipments().get(0).status());
        RailTrainSnapshot derailed = new RailTrainSnapshot(TRAIN, OWNER, "Express 7", 50.0D, 0.0D, 0.0D, true, null, true,
                "Ibadan Yard", 1000.0D, 0.0D, 950.0D, 1000.0D, 0);
        assertEquals("STUCK", tracker.update(List.of(derailed)).shipments().get(0).status());
        RailTrainSnapshot blocked = new RailTrainSnapshot(TRAIN, OWNER, "Express 7", 50.0D, 0.0D, 0.0D, false, null, true,
                "Ibadan Yard", 1000.0D, 0.0D, 950.0D, 1000.0D, RailTraceTracker.STUCK_SIGNAL_TICKS + 1);
        assertEquals("STUCK", tracker.update(List.of(blocked)).shipments().get(0).status());
    }

    @Test
    void trainsThatLeaveTheMapAreRemoved() {
        RailTraceTracker tracker = new RailTraceTracker();
        tracker.update(List.of(moving(100.0D, "Ibadan Yard", 900.0D, 1000.0D)));
        RailTrainSnapshot unscheduled = new RailTrainSnapshot(TRAIN, OWNER, "Express 7", 100.0D, 0.0D, 0.0D, false, null, false,
                null, Double.NaN, Double.NaN, 0.0D, 0.0D, 0);
        RailTraceTracker.Result parked = tracker.update(List.of(unscheduled));
        assertTrue(parked.shipments().isEmpty(), "a parked train without a schedule is not logistics");
        assertEquals(List.of(RailTraceTracker.traceId(TRAIN)), parked.removedTraceIds());

        tracker.update(List.of(moving(100.0D, "Ibadan Yard", 900.0D, 1000.0D)));
        assertEquals(List.of(RailTraceTracker.traceId(TRAIN)), tracker.update(List.of()).removedTraceIds(), "a disassembled train");
    }

    @Test
    void railTracesAreLeftToTheRailServiceByTheOrphanCleanup() {
        assertTrue(ShippingTraceService.isManagedExternally(RailTraceTracker.traceId(TRAIN)));
        assertTrue(!ShippingTraceService.isManagedExternally("manual-" + TRAIN));
    }

    @Test
    void createIsTouchedOnlyBehindTheInstalledCheck() throws Exception {
        String events = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/monpai/sailboatmod/ServerEvents.java"));
        String logistics = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/monpai/sailboatmod/compat/create/CreateRailLogistics.java"));
        assertTrue(events.contains("CreateRailLogistics.tick(server)") && events.contains("CreateRailLogistics.clear()"));
        assertTrue(logistics.indexOf("!available()") < logistics.indexOf("CreateTrainBridge.overworldTrains()"),
                "the bridge (the only class using Create types) runs only after the installed check");
        assertTrue(logistics.contains("catch (LinkageError"), "an incompatible Create version turns the feature off instead of crashing");
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            List<String> users = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> { try { return java.nio.file.Files.readString(p).contains("com.simibubi.create"); } catch (java.io.IOException e) { return false; } })
                    .map(p -> p.getFileName().toString()).toList();
            assertEquals(List.of("CreateTrainBridge.java"), users);
        }
    }
}
