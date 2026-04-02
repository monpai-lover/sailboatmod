package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.ConstructionMaterialRequestRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

public class ConstructionMaterialRequestSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_construction_material_requests";

    private final Map<String, ConstructionMaterialRequestRecord> requests = new LinkedHashMap<>();

    public static ConstructionMaterialRequestSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new ConstructionMaterialRequestSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(
                ConstructionMaterialRequestSavedData::load,
                ConstructionMaterialRequestSavedData::new,
                DATA_NAME
        );
    }

    public static ConstructionMaterialRequestSavedData load(CompoundTag tag) {
        ConstructionMaterialRequestSavedData data = new ConstructionMaterialRequestSavedData();
        ListTag requestTag = tag.getList("MaterialRequests", Tag.TAG_COMPOUND);
        for (Tag raw : requestTag) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            ConstructionMaterialRequestRecord record = ConstructionMaterialRequestRecord.load(compound);
            if (!record.projectId().isBlank() && !record.commodityKey().isBlank()) {
                data.requests.put(key(record.projectId(), record.commodityKey()), record);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag requestTag = new ListTag();
        for (ConstructionMaterialRequestRecord record : requests.values()) {
            requestTag.add(record.save());
        }
        tag.put("MaterialRequests", requestTag);
        return tag;
    }

    public ConstructionMaterialRequestRecord getRequest(String projectId, String commodityKey) {
        if (projectId == null || projectId.isBlank() || commodityKey == null || commodityKey.isBlank()) {
            return null;
        }
        return requests.get(key(projectId, commodityKey));
    }

    public Map<String, ConstructionMaterialRequestRecord> getRequests() {
        return requests;
    }

    public void putRequest(ConstructionMaterialRequestRecord record) {
        if (record == null || record.projectId().isBlank() || record.commodityKey().isBlank()) {
            return;
        }
        requests.put(key(record.projectId(), record.commodityKey()), record);
        setDirty();
    }

    private static String key(String projectId, String commodityKey) {
        return projectId.trim() + "|" + commodityKey.trim().toLowerCase();
    }
}
