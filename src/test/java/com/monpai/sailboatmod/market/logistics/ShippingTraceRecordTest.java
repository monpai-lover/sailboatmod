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
                1, 0.5D, 100L, 200L);

        ShippingTraceRecord loaded = ShippingTraceRecord.load(record.save());

        assertEquals("ship-1", loaded.shippingOrderId());
        assertEquals("minecraft:overworld", loaded.dimensionId());
        assertEquals(2, loaded.waypoints().size());
        assertEquals(1, loaded.completedPointCount());
        assertEquals(0.5D, loaded.progressRatio(), 0.0001D);
    }
}
