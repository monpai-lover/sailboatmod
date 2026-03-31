package com.monpai.sailboatmod.resident.army;

import com.monpai.sailboatmod.resident.data.ResidentSavedData;
import com.monpai.sailboatmod.resident.model.Profession;
import com.monpai.sailboatmod.resident.model.ResidentRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.*;

public class ArmySavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_armies";
    private final Map<String, ArmyRecord> armies = new LinkedHashMap<>();

    public static ArmySavedData get(Level level) {
        if (!(level instanceof ServerLevel sl) || sl.getServer() == null) return new ArmySavedData();
        return sl.getServer().overworld().getDataStorage().computeIfAbsent(ArmySavedData::load, ArmySavedData::new, DATA_NAME);
    }

    public static ArmySavedData load(CompoundTag tag) {
        ArmySavedData data = new ArmySavedData();
        ListTag list = tag.getList("Armies", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ArmyRecord r = ArmyRecord.load(list.getCompound(i));
            if (!r.armyId().isBlank()) data.armies.put(r.armyId(), r);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (ArmyRecord r : armies.values()) list.add(r.save());
        tag.put("Armies", list);
        return tag;
    }

    public void putArmy(ArmyRecord army) {
        if (army == null || army.armyId().isBlank()) return;
        armies.put(army.armyId(), army);
        setDirty();
    }

    public void removeArmy(String armyId) {
        if (armyId != null && armies.remove(armyId) != null) setDirty();
    }

    public ArmyRecord getArmy(String armyId) {
        return armyId == null ? null : armies.get(armyId);
    }

    public List<ArmyRecord> getArmiesForNation(String nationId) {
        if (nationId == null) return List.of();
        List<ArmyRecord> result = new ArrayList<>();
        for (ArmyRecord r : armies.values()) if (nationId.equals(r.nationId())) result.add(r);
        return result;
    }

    public ArmyRecord getArmyForCommander(UUID commanderUuid) {
        if (commanderUuid == null) return null;
        for (ArmyRecord r : armies.values()) if (commanderUuid.equals(r.commanderUuid())) return r;
        return null;
    }

    public Collection<ArmyRecord> getAllArmies() { return List.copyOf(armies.values()); }
}
