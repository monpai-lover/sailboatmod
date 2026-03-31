package com.monpai.sailboatmod.resident.data;

import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ResidentSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_residents";
    private final Map<String, ResidentRecord> residents = new LinkedHashMap<>();

    public static ResidentSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new ResidentSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(ResidentSavedData::load, ResidentSavedData::new, DATA_NAME);
    }

    public static ResidentSavedData load(CompoundTag tag) {
        ResidentSavedData data = new ResidentSavedData();
        ListTag list = tag.getList("Residents", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ResidentRecord r = ResidentRecord.load(list.getCompound(i));
            if (!r.residentId().isBlank()) {
                data.residents.put(r.residentId(), r);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ResidentRecord r : residents.values()) {
            list.add(r.save());
        }
        tag.put("Residents", list);
        return tag;
    }
    public void putResident(ResidentRecord resident) {
        if (resident == null || resident.residentId().isBlank()) return;
        residents.put(resident.residentId(), resident);
        setDirty();
    }

    public void removeResident(String residentId) {
        if (residentId != null && !residentId.isBlank() && residents.remove(residentId) != null) setDirty();
    }

    public ResidentRecord getResident(String residentId) {
        return residentId == null || residentId.isBlank() ? null : residents.get(residentId);
    }

    public Collection<ResidentRecord> getAllResidents() {
        return List.copyOf(residents.values());
    }

    public List<ResidentRecord> getResidentsForTown(String townId) {
        if (townId == null || townId.isBlank()) return List.of();
        List<ResidentRecord> result = new ArrayList<>();
        for (ResidentRecord r : residents.values()) {
            if (townId.equals(r.townId())) result.add(r);
        }
        return result;
    }

    public int countResidentsForTown(String townId) {
        if (townId == null || townId.isBlank()) return 0;
        int count = 0;
        for (ResidentRecord r : residents.values()) {
            if (townId.equals(r.townId())) count++;
        }
        return count;
    }
}
