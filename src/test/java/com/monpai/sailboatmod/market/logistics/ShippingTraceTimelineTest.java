package com.monpai.sailboatmod.market.logistics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShippingTraceTimelineTest {
    private static ShippingTraceRecord trace(String id, String status, double progress, long time) {
        return new ShippingTraceRecord(id, "00000000-0000-0000-0000-000000000001", "minecraft:overworld", "PORT", status,
                "crimea", "port", "Tema", "Lomé", List.of(new Vec3(0, 64, 0), new Vec3(100, 64, 0)),
                1, progress, 10L, time, 40.0D, 0.0D, 0.3D, List.of(), false);
    }

    private static List<String> kinds(List<ShippingTraceEvent> events) {
        return events.stream().map(ShippingTraceEvent::kind).toList();
    }

    @Test
    void recordsDepartureAndEveryStatusChangeButNotPlainProgress() {
        ShippingTraceSavedData data = new ShippingTraceSavedData();
        data.putTrace(trace("o1", "SAILING", 0.0D, 100L));
        data.putTrace(trace("o1", "SAILING", 0.2D, 140L));
        data.putTrace(trace("o1", "STUCK", 0.3D, 200L));
        data.putTrace(trace("o1", "SAILING", 0.3D, 260L));

        List<ShippingTraceEvent> events = data.getEvents("o1");
        assertEquals(List.of("DEPARTED", "STUCK", "SAILING"), kinds(events));
        assertEquals(100L, events.get(0).gameTime());
        assertEquals(200L, events.get(1).gameTime());
        assertEquals(40.0D, events.get(1).x(), 1.0E-9);
    }

    @Test
    void keepsTheDepartureAndTheLatestEventsWhenAShipmentFlapsALot() {
        ShippingTraceSavedData data = new ShippingTraceSavedData();
        data.putTrace(trace("o1", "SAILING", 0.0D, 0L));
        for (int i = 1; i <= 100; i++) {
            data.putTrace(trace("o1", i % 2 == 0 ? "SAILING" : "STUCK", 0.1D, i));
        }
        List<ShippingTraceEvent> events = data.getEvents("o1");
        assertEquals(ShippingTraceSavedData.MAX_EVENTS_PER_TRACE, events.size());
        assertEquals("DEPARTED", events.get(0).kind());
        assertEquals(100L, events.get(events.size() - 1).gameTime());
    }

    @Test
    void finishedShipmentsMoveToHistoryWithTheirEvents() {
        ShippingTraceSavedData data = new ShippingTraceSavedData();
        data.putTrace(trace("o1", "SAILING", 0.0D, 100L));
        data.putTrace(trace("o1", "DELIVERED", 1.0D, 900L));
        data.removeTrace("o1");

        assertTrue(data.getEvents("o1").isEmpty());
        List<ShippingTraceSavedData.ArchivedShipment> history = data.getHistory();
        assertEquals(1, history.size());
        assertEquals("o1", history.get(0).trace().shippingOrderId());
        assertEquals(900L, history.get(0).endedGameTime());
        assertEquals(List.of("DEPARTED", "DELIVERED"), kinds(history.get(0).events()));
    }

    @Test
    void tracesRemovedBeforeTheyWentAnywhereAreNotHistory() {
        ShippingTraceSavedData data = new ShippingTraceSavedData();
        data.putTrace(trace("o1", "SAILING", 0.0D, 100L));
        data.removeTrace("o1");
        assertTrue(data.getHistory().isEmpty());
    }

    @Test
    void historyKeepsTheNewestShipmentsFirst() {
        ShippingTraceSavedData data = new ShippingTraceSavedData();
        for (int i = 0; i < ShippingTraceSavedData.MAX_HISTORY + 20; i++) {
            data.putTrace(trace("o" + i, "DELIVERED", 1.0D, i));
            data.removeTrace("o" + i);
        }
        List<ShippingTraceSavedData.ArchivedShipment> history = data.getHistory();
        assertEquals(ShippingTraceSavedData.MAX_HISTORY, history.size());
        assertEquals("o" + (ShippingTraceSavedData.MAX_HISTORY + 19), history.get(0).trace().shippingOrderId());
    }

    @Test
    void eventsAndHistorySurviveASaveAndLoad() {
        ShippingTraceSavedData data = new ShippingTraceSavedData();
        data.putTrace(trace("live", "SAILING", 0.0D, 100L));
        data.putTrace(trace("live", "STUCK", 0.2D, 150L));
        data.putTrace(trace("done", "SAILING", 0.0D, 10L));
        data.putTrace(trace("done", "DELIVERED", 1.0D, 90L));
        data.removeTrace("done");

        ShippingTraceSavedData loaded = ShippingTraceSavedData.load(data.save(new CompoundTag()));

        assertEquals(List.of("DEPARTED", "STUCK"), kinds(loaded.getEvents("live")));
        assertEquals(1, loaded.getHistory().size());
        assertEquals(List.of("DEPARTED", "DELIVERED"), kinds(loaded.getHistory().get(0).events()));
        assertEquals(2, loaded.getHistory().get(0).trace().waypoints().size());
    }

    @Test
    void externalUpdatesKeepWhenTheShipmentStarted() {
        ShippingTraceService.ExternalShipment first = new ShippingTraceService.ExternalShipment("rail-1", "owner", "crimea",
                TransportModes.RAIL, "IN_TRANSIT", "Lagos", "Ibadan", "Express 7",
                List.of(new Vec3(0, 64, 0), new Vec3(100, 64, 0)), 1, 0.1D, 10.0D, 0.0D, 0.8D, List.of());
        ShippingTraceRecord created = ShippingTraceService.mergeExternal(null, first, 100L);
        ShippingTraceRecord later = ShippingTraceService.mergeExternal(created, first, 400L);

        assertEquals(100L, later.startedGameTime());
        assertEquals(400L, later.updatedGameTime());
        assertEquals(0.1D, later.progressRatio(), 1.0E-9);
        assertEquals(0.8D, later.currentSpeed(), 1.0E-9);
        assertEquals(1, later.completedPointCount());
        assertTrue(later.manual());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void externalUpdatesRevokeClearedOwnerAndNation(String cleared) {
        ShippingTraceRecord existing = ShippingTraceService.mergeExternal(null, external("old-owner", "old-nation"), 100L);

        ShippingTraceRecord updated = ShippingTraceService.mergeExternal(existing, external(cleared, cleared), 200L);

        assertEquals("", updated.shipperUuid(), "a missing owner must revoke the old owner's access");
        assertEquals("", updated.nationId(), "leaving a nation must revoke that nation's access");
        assertEquals(100L, updated.startedGameTime(), "revoking access must keep the shipment's start time");
    }

    @Test
    void externalOwnershipTransferDoesNotInheritPreviousOwnersNation() {
        ShippingTraceRecord existing = ShippingTraceService.mergeExternal(null, external("old-owner", "old-nation"), 100L);

        ShippingTraceRecord updated = ShippingTraceService.mergeExternal(existing, external("new-owner", ""), 200L);

        assertEquals("new-owner", updated.shipperUuid());
        assertEquals("", updated.nationId(), "a new owner without a nation must not expose their train to the previous nation");
    }

    private static ShippingTraceService.ExternalShipment external(String owner, String nation) {
        return new ShippingTraceService.ExternalShipment("external-rail-1", owner, nation,
                TransportModes.RAIL, "IN_TRANSIT", "Lagos", "Ibadan", "Express 7",
                List.of(new Vec3(0, 64, 0), new Vec3(100, 64, 0)), 1, 0.1D, 10.0D, 0.0D, 0.8D, List.of());
    }
}
