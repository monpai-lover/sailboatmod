package com.monpai.sailboatmod.market.logistics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShippingTraceLivePositionTest {
    private static ShippingTraceRecord sample() {
        return new ShippingTraceRecord(
                "manual-abc", "uuid-1", "minecraft:overworld", "PORT", "SAILING",
                "nation-1", "town-1", "Src", "Dst",
                List.of(new Vec3(0, 64, 0), new Vec3(10, 64, 10)),
                0, 0.0D, 100L, 100L,
                12.5D, 34.5D, true);
    }

    @Test
    void livePositionAndManualSurviveNbtRoundTrip() {
        ShippingTraceRecord rec = sample();
        CompoundTag tag = rec.save();
        ShippingTraceRecord loaded = ShippingTraceRecord.load(tag);

        assertEquals(12.5D, loaded.currentX(), 1.0E-9);
        assertEquals(34.5D, loaded.currentZ(), 1.0E-9);
        assertTrue(loaded.manual());
    }

    @Test
    void withLivePositionUpdatesCoordsAndTimeKeepsOtherFields() {
        ShippingTraceRecord rec = sample();
        ShippingTraceRecord moved = rec.withLivePosition(99.0D, 88.0D, 200L);

        assertEquals(99.0D, moved.currentX(), 1.0E-9);
        assertEquals(88.0D, moved.currentZ(), 1.0E-9);
        assertEquals(200L, moved.updatedGameTime());
        assertEquals("manual-abc", moved.shippingOrderId());
        assertTrue(moved.manual());
    }

    @Test
    void legacyNbtWithoutLiveFieldsDefaultsToFirstWaypointAndNotManual() {
        ShippingTraceRecord rec = new ShippingTraceRecord(
                "ord-1", "uuid-1", "minecraft:overworld", "PORT", "SAILING",
                "", "", "Src", "Dst",
                List.of(new Vec3(5, 64, 7), new Vec3(10, 64, 10)),
                0, 0.0D, 100L, 100L,
                0.0D, 0.0D, false);
        CompoundTag tag = rec.save();
        tag.remove("CurrentX");
        tag.remove("CurrentZ");
        tag.remove("Manual");

        ShippingTraceRecord loaded = ShippingTraceRecord.load(tag);
        assertEquals(5.0D, loaded.currentX(), 1.0E-9, "legacy current falls back to first waypoint x");
        assertEquals(7.0D, loaded.currentZ(), 1.0E-9, "legacy current falls back to first waypoint z");
        assertFalse(loaded.manual());
    }
}
