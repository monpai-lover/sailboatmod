package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.TownDemandRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

public class TownDemandSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_town_demand_ledger";

    private final Map<String, TownDemandRecord> demands = new LinkedHashMap<>();

    public static TownDemandSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new TownDemandSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(TownDemandSavedData::load, TownDemandSavedData::new, DATA_NAME);
    }

    public static TownDemandSavedData load(CompoundTag tag) {
        TownDemandSavedData data = new TownDemandSavedData();
        ListTag demandTag = tag.getList("TownDemands", Tag.TAG_COMPOUND);
        for (Tag raw : demandTag) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            TownDemandRecord record = TownDemandRecord.load(compound);
            if (!record.demandId().isBlank()) {
                data.demands.put(record.demandId(), record);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag demandTag = new ListTag();
        for (TownDemandRecord record : demands.values()) {
            demandTag.add(record.save());
        }
        tag.put("TownDemands", demandTag);
        return tag;
    }

    public TownDemandRecord getDemand(String demandId) {
        if (demandId == null || demandId.isBlank()) {
            return null;
        }
        return demands.get(demandId.trim());
    }

    public Map<String, TownDemandRecord> getDemands() {
        return demands;
    }

    public void putDemand(TownDemandRecord record) {
        if (record == null || record.demandId().isBlank()) {
            return;
        }
        demands.put(record.demandId(), record);
        setDirty();
    }
}
