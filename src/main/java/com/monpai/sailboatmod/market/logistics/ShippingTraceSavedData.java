package com.monpai.sailboatmod.market.logistics;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ShippingTraceSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_shipping_traces";

    private final Map<String, ShippingTraceRecord> traces = new LinkedHashMap<>();

    public static ShippingTraceSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new ShippingTraceSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(ShippingTraceSavedData::load, ShippingTraceSavedData::new, DATA_NAME);
    }

    public static ShippingTraceSavedData load(CompoundTag tag) {
        ShippingTraceSavedData data = new ShippingTraceSavedData();
        ListTag list = tag.getList("Traces", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (raw instanceof CompoundTag compound) {
                ShippingTraceRecord trace = ShippingTraceRecord.load(compound);
                if (!trace.shippingOrderId().isBlank()) {
                    data.traces.put(trace.shippingOrderId(), trace);
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ShippingTraceRecord trace : traces.values()) {
            list.add(trace.save());
        }
        tag.put("Traces", list);
        return tag;
    }

    public void putTrace(ShippingTraceRecord trace) {
        if (trace == null || trace.shippingOrderId().isBlank()) {
            return;
        }
        traces.put(trace.shippingOrderId(), trace);
        setDirty();
    }

    public ShippingTraceRecord getTrace(String shippingOrderId) {
        return shippingOrderId == null || shippingOrderId.isBlank() ? null : traces.get(shippingOrderId);
    }

    public List<ShippingTraceRecord> getTraces() {
        return new ArrayList<>(traces.values());
    }

    public void removeTrace(String shippingOrderId) {
        if (shippingOrderId != null && traces.remove(shippingOrderId) != null) {
            setDirty();
        }
    }
}
