package com.monpai.sailboatmod.market;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

public class ProcurementSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_procurements";

    private final Map<String, ProcurementRecord> procurements = new LinkedHashMap<>();

    public static ProcurementSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new ProcurementSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(ProcurementSavedData::load, ProcurementSavedData::new, DATA_NAME);
    }

    public static ProcurementSavedData load(CompoundTag tag) {
        ProcurementSavedData data = new ProcurementSavedData();
        ListTag procurementTag = tag.getList("Procurements", Tag.TAG_COMPOUND);
        for (Tag raw : procurementTag) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            ProcurementRecord record = ProcurementRecord.load(compound);
            if (!record.procurementId().isBlank()) {
                data.procurements.put(record.procurementId(), record);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag procurementTag = new ListTag();
        for (ProcurementRecord record : procurements.values()) {
            procurementTag.add(record.save());
        }
        tag.put("Procurements", procurementTag);
        return tag;
    }

    public ProcurementRecord getProcurement(String procurementId) {
        if (procurementId == null || procurementId.isBlank()) {
            return null;
        }
        return procurements.get(procurementId.trim());
    }

    public Map<String, ProcurementRecord> getProcurements() {
        return procurements;
    }

    public void putProcurement(ProcurementRecord record) {
        if (record == null || record.procurementId().isBlank()) {
            return;
        }
        procurements.put(record.procurementId(), record);
        setDirty();
    }
}
