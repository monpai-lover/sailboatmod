package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.TownStockpileRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

public class TownStockpileSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_town_stockpiles";

    private final Map<String, TownStockpileRecord> stockpiles = new LinkedHashMap<>();

    public static TownStockpileSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new TownStockpileSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(TownStockpileSavedData::load, TownStockpileSavedData::new, DATA_NAME);
    }

    public static TownStockpileSavedData load(CompoundTag tag) {
        TownStockpileSavedData data = new TownStockpileSavedData();
        ListTag stockpileTag = tag.getList("TownStockpiles", Tag.TAG_COMPOUND);
        for (Tag raw : stockpileTag) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            TownStockpileRecord record = TownStockpileRecord.load(compound);
            if (!record.townId().isBlank()) {
                data.stockpiles.put(record.townId(), record);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag stockpileTag = new ListTag();
        for (TownStockpileRecord record : stockpiles.values()) {
            stockpileTag.add(record.save());
        }
        tag.put("TownStockpiles", stockpileTag);
        return tag;
    }

    public TownStockpileRecord getStockpile(String townId) {
        if (townId == null || townId.isBlank()) {
            return TownStockpileRecord.empty("");
        }
        return stockpiles.getOrDefault(townId.trim(), TownStockpileRecord.empty(townId));
    }

    public void putStockpile(TownStockpileRecord record) {
        if (record == null || record.townId().isBlank()) {
            return;
        }
        if (record.commodityAmounts().isEmpty()) {
            stockpiles.remove(record.townId());
        } else {
            stockpiles.put(record.townId(), record);
        }
        setDirty();
    }
}
