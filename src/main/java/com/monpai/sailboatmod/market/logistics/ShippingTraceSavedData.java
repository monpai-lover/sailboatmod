package com.monpai.sailboatmod.market.logistics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ShippingTraceSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_shipping_traces";

    /** Timeline entries kept per live shipment: the departure plus the most recent changes. */
    static final int MAX_EVENTS_PER_TRACE = 24;
    /** Finished shipments kept for the web map's history, newest first. */
    static final int MAX_HISTORY = 100;
    private static final Set<String> FINISHED_STATUSES = Set.of(
            "DELIVERED", "ARRIVED", "CLAIMED", "FAILED", "FAILED_ROLLBACK", "CANCELLED");

    private final Map<String, ShippingTraceRecord> traces = new LinkedHashMap<>();
    private final Map<String, List<ShippingTraceEvent>> events = new LinkedHashMap<>();
    private final Deque<ArchivedShipment> history = new ArrayDeque<>();

    /** A shipment that has ended: its last trace, its timeline, and when it ended. */
    public record ArchivedShipment(ShippingTraceRecord trace, List<ShippingTraceEvent> events, long endedGameTime) {
        public ArchivedShipment {
            events = events == null ? List.of() : List.copyOf(events);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.put("Trace", trace.save());
            tag.put("Events", saveEvents(events));
            tag.putLong("Ended", endedGameTime);
            return tag;
        }

        static ArchivedShipment load(CompoundTag tag) {
            return new ArchivedShipment(ShippingTraceRecord.load(tag.getCompound("Trace")),
                    loadEvents(tag.getList("Events", Tag.TAG_COMPOUND)), tag.getLong("Ended"));
        }
    }

    public static ShippingTraceSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new ShippingTraceSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(new SavedData.Factory<>(ShippingTraceSavedData::new, (tag, provider) -> com.monpai.sailboatmod.compat.ItemStackNbt.withProvider(provider, () -> ShippingTraceSavedData.load(tag))), DATA_NAME);
    }

    public static ShippingTraceSavedData load(CompoundTag tag) {
        ShippingTraceSavedData data = new ShippingTraceSavedData();
        ListTag list = tag.getList("Traces", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (raw instanceof CompoundTag compound) {
                ShippingTraceRecord trace = ShippingTraceRecord.load(compound);
                if (!trace.shippingOrderId().isBlank()) {
                    data.traces.put(trace.shippingOrderId(), trace);
                    List<ShippingTraceEvent> timeline = loadEvents(compound.getList("TimelineEvents", Tag.TAG_COMPOUND));
                    if (!timeline.isEmpty()) {
                        data.events.put(trace.shippingOrderId(), new ArrayList<>(timeline));
                    }
                }
            }
        }
        for (Tag raw : tag.getList("History", Tag.TAG_COMPOUND)) {
            if (raw instanceof CompoundTag compound) {
                ArchivedShipment archived = ArchivedShipment.load(compound);
                if (!archived.trace().shippingOrderId().isBlank() && data.history.size() < MAX_HISTORY) {
                    data.history.addLast(archived);
                }
            }
        }
        return data;
    }

    private static ListTag saveEvents(List<ShippingTraceEvent> events) {
        ListTag list = new ListTag();
        for (ShippingTraceEvent event : events) {
            list.add(event.save());
        }
        return list;
    }

    private static List<ShippingTraceEvent> loadEvents(ListTag list) {
        List<ShippingTraceEvent> out = new ArrayList<>();
        for (Tag raw : list) {
            if (raw instanceof CompoundTag compound) {
                out.add(ShippingTraceEvent.load(compound));
            }
        }
        return out;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
        return com.monpai.sailboatmod.compat.ItemStackNbt.withProvider(provider, () -> save(tag));
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ShippingTraceRecord trace : traces.values()) {
            CompoundTag saved = trace.save();
            List<ShippingTraceEvent> timeline = events.get(trace.shippingOrderId());
            if (timeline != null && !timeline.isEmpty()) {
                saved.put("TimelineEvents", saveEvents(timeline));
            }
            list.add(saved);
        }
        tag.put("Traces", list);
        ListTag archived = new ListTag();
        for (ArchivedShipment shipment : history) {
            archived.add(shipment.save());
        }
        tag.put("History", archived);
        return tag;
    }

    public void putTrace(ShippingTraceRecord trace) {
        if (trace == null || trace.shippingOrderId().isBlank()) {
            return;
        }
        ShippingTraceRecord previous = traces.put(trace.shippingOrderId(), trace);
        if (previous == null) {
            addEvent(trace, ShippingTraceEvent.DEPARTED);
        } else if (!previous.status().equalsIgnoreCase(trace.status())) {
            addEvent(trace, trace.status());
        }
        setDirty();
    }

    private void addEvent(ShippingTraceRecord trace, String kind) {
        List<ShippingTraceEvent> timeline = events.computeIfAbsent(trace.shippingOrderId(), id -> new ArrayList<>());
        timeline.add(new ShippingTraceEvent(kind, trace.updatedGameTime(), trace.currentX(), trace.currentZ()));
        if (timeline.size() > MAX_EVENTS_PER_TRACE) {
            timeline.remove(1); // keep the departure, drop the oldest change after it
        }
    }

    /** The shipment's timeline, oldest first; empty for unknown ids. */
    public List<ShippingTraceEvent> getEvents(String shippingOrderId) {
        List<ShippingTraceEvent> timeline = shippingOrderId == null ? null : events.get(shippingOrderId);
        return timeline == null ? List.of() : List.copyOf(timeline);
    }

    /** Finished shipments, newest first. */
    public List<ArchivedShipment> getHistory() {
        return List.copyOf(history);
    }

    public ShippingTraceRecord getTrace(String shippingOrderId) {
        return shippingOrderId == null || shippingOrderId.isBlank() ? null : traces.get(shippingOrderId);
    }

    public List<ShippingTraceRecord> getTraces() {
        return new ArrayList<>(traces.values());
    }

    /**
     * Removes a live trace. One that went somewhere or reached a final status is kept in the history with its
     * timeline; one removed before it moved (a cancelled or replaced dispatch) is simply dropped.
     */
    public void removeTrace(String shippingOrderId) {
        if (shippingOrderId == null) {
            return;
        }
        ShippingTraceRecord removed = traces.remove(shippingOrderId);
        List<ShippingTraceEvent> timeline = events.remove(shippingOrderId);
        if (removed == null) {
            return;
        }
        if (removed.progressRatio() > 0.0D || FINISHED_STATUSES.contains(removed.status().toUpperCase(java.util.Locale.ROOT))) {
            history.addFirst(new ArchivedShipment(removed, timeline, removed.updatedGameTime()));
            while (history.size() > MAX_HISTORY) {
                history.removeLast();
            }
        }
        setDirty();
    }
}
