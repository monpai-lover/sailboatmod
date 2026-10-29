package com.monpai.sailboatmod.market.logistics;

import net.minecraft.nbt.CompoundTag;

/**
 * One entry in a shipment's timeline: DEPARTED when the trace first appears, then the new status each time it
 * changes (STUCK, SAILING, DELIVERED, ...), with where the vehicle was at that moment.
 */
public record ShippingTraceEvent(String kind, long gameTime, double x, double z) {
    public static final String DEPARTED = "DEPARTED";

    public ShippingTraceEvent {
        kind = kind == null || kind.isBlank() ? "UNKNOWN" : kind.trim();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Kind", kind);
        tag.putLong("Time", gameTime);
        tag.putDouble("X", x);
        tag.putDouble("Z", z);
        return tag;
    }

    public static ShippingTraceEvent load(CompoundTag tag) {
        return new ShippingTraceEvent(tag.getString("Kind"), tag.getLong("Time"), tag.getDouble("X"), tag.getDouble("Z"));
    }
}
