package com.monpai.sailboatmod.market.logistics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShippingTraceRecordTest {
    @Test
    void traceRecordRoundTripsWaypointsAndProgress() {
        ShippingTraceRecord record = new ShippingTraceRecord(
                "ship-1", "shipper-1", "minecraft:overworld", "PORT", "SAILING",
                "crimea", "port", "source", "target",
                List.of(new Vec3(0.5, 64.0, 0.5), new Vec3(16.5, 64.0, 16.5)),
                1, 0.5D, 100L, 200L, 0.0D, 0.0D, 0.0D, List.of(), false);

        ShippingTraceRecord loaded = ShippingTraceRecord.load(record.save());

        assertEquals("ship-1", loaded.shippingOrderId());
        assertEquals("minecraft:overworld", loaded.dimensionId());
        assertEquals(2, loaded.waypoints().size());
        assertEquals(1, loaded.completedPointCount());
        assertEquals(0.5D, loaded.progressRatio(), 0.0001D);
    }
    @Test
    void legacyTracesHaveNoInventedFlightHeightAndAllUpdatesRetainNewTelemetry() {
        var record = new ShippingTraceRecord("air", "owner", "minecraft:overworld", "HELICOPTER", "IN_TRANSIT",
                "", "", "A", "B", List.of(new Vec3(1, 80, 2), new Vec3(10, 100, 20)),
                0, 0, 100, 100, 1, 2, 0, List.of(), false);
        org.junit.jupiter.api.Assertions.assertNull(ShippingTraceRecord.load(record.save()).flight());
        var withFlight = record.withFlightTelemetry(0.0, "landing", "physical", 120)
                .withStatus("ARRIVED", 121).withProgress(1, 1, 122).withLivePosition(10, 20, 123)
                .withSpeed(0, 124).withCargo(List.of(), 125);
        var loaded = ShippingTraceRecord.load(withFlight.save());
        assertEquals(0.0, loaded.flight().worldY());
        assertEquals("LANDING", loaded.flight().flightState());
        assertEquals("PHYSICAL", loaded.flight().executionMode());
        var data = new ShippingTraceSavedData();
        data.putTrace(loaded);
        data.removeTrace("air");
        var history = ShippingTraceSavedData.load(data.save(new net.minecraft.nbt.CompoundTag())).getHistory();
        assertEquals(loaded.flight(), history.get(0).trace().flight());
    }


    @Test void longLiveItineraryIsNotTruncatedAt2048AndRerouteKeepsOwnership() {
        var points=new java.util.ArrayList<Vec3>();for(int i=0;i<5000;i++)points.add(new Vec3(i,65,i*.1));
        var original=new ShippingTraceRecord("order","owner","minecraft:overworld","PORT","SAILING",
                "N","T","A","B",points,3000,.6,1,2,10,20,2,List.of(),false);
        assertEquals(5000,ShippingTraceRecord.load(original.save()).waypoints().size());
        var replacement=original.withRouteGeometry(List.of(points.get(3000),new Vec3(3005,65,320),points.getLast()),1,.2,3);
        assertEquals("owner",replacement.shipperUuid());assertEquals("order",replacement.shippingOrderId());
        assertEquals(3,replacement.waypoints().size());assertEquals(points.getLast(),replacement.waypoints().getLast());
    }
}
