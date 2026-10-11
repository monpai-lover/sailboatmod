package com.monpai.sailboatmod.resident.service;

import com.monpai.sailboatmod.resident.model.BuildingConstructionRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

public class BuildingConstructionSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_constructions";

    private final Map<String, BuildingConstructionRecord> constructions = new LinkedHashMap<>();

    public static BuildingConstructionSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new BuildingConstructionSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(new SavedData.Factory<>(BuildingConstructionSavedData::new, (tag, provider) -> com.monpai.sailboatmod.compat.ItemStackNbt.withProvider(provider, () -> BuildingConstructionSavedData.load(tag))), DATA_NAME);
    }

    public static BuildingConstructionSavedData load(CompoundTag tag) {
        BuildingConstructionSavedData data = new BuildingConstructionSavedData();
        ListTag list = tag.getList("Constructions", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (raw instanceof CompoundTag compound) {
                BuildingConstructionRecord record = BuildingConstructionRecord.load(compound);
                if (!record.buildingId().isBlank()) {
                    data.constructions.put(record.buildingId(), record);
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider provider) {
        return com.monpai.sailboatmod.compat.ItemStackNbt.withProvider(provider, () -> save(tag));
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (BuildingConstructionRecord record : constructions.values()) {
            list.add(record.save());
        }
        tag.put("Constructions", list);
        return tag;
    }

    public Map<String, BuildingConstructionRecord> getConstructions() {
        return constructions;
    }
}
