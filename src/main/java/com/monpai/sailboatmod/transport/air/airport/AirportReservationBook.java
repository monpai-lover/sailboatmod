package com.monpai.sailboatmod.transport.air.airport;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import java.util.*;

/** Reservations use server game time, which is persisted across world restarts. */
public final class AirportReservationBook {
    public static final long MAX_TTL_TICKS = 20 * 60 * 5;
    public record Reservation(String facilityId, UUID aircraftId, long expiresAtTick) {}
    private final Map<String, Reservation> entries = new LinkedHashMap<>();
    public boolean reserve(String facilityId, UUID aircraftId, long now, long ttlTicks) {
        if (facilityId == null || facilityId.isBlank() || aircraftId == null || now < 0 || ttlTicks <= 0 || ttlTicks > MAX_TTL_TICKS || now > Long.MAX_VALUE - ttlTicks) return false;
        expire(now);
        Reservation existing = entries.get(facilityId);
        if (existing != null && !existing.aircraftId().equals(aircraftId)) return false;
        entries.put(facilityId, new Reservation(facilityId, aircraftId, now + ttlTicks));
        return true;
    }
    public boolean available(String facilityId, UUID aircraftId, long now) {
        expire(now);
        Reservation value = entries.get(facilityId);
        return value == null || value.aircraftId().equals(aircraftId);
    }
    public List<Reservation> active(long now) { expire(now); return List.copyOf(entries.values()); }
    public void release(UUID aircraftId) { entries.values().removeIf(value -> value.aircraftId().equals(aircraftId)); }
    public void removeFacility(String facilityId) { entries.remove(facilityId); }
    private void expire(long now) { entries.values().removeIf(value -> value.expiresAtTick() <= now); }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); ListTag list = new ListTag();
        for (Reservation value : entries.values()) { CompoundTag item = new CompoundTag(); item.putString("Facility", value.facilityId()); item.putUUID("Aircraft", value.aircraftId()); item.putLong("Expires", value.expiresAtTick()); list.add(item); }
        tag.put("Reservations", list); return tag;
    }
    public static AirportReservationBook load(CompoundTag tag) {
        AirportReservationBook book = new AirportReservationBook();
        for (Tag raw : tag.getList("Reservations", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) raw;
            if (book.entries.size() >= 64) break;
            String id = item.getString("Facility");
            if (!id.isBlank() && item.hasUUID("Aircraft") && item.getLong("Expires") > 0)
                book.entries.put(id, new Reservation(id, item.getUUID("Aircraft"), item.getLong("Expires")));
        }
        return book;
    }
}
