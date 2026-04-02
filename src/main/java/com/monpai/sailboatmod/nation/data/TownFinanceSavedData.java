package com.monpai.sailboatmod.nation.data;

import com.monpai.sailboatmod.nation.model.TownFinanceEntryRecord;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;

public class TownFinanceSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_town_finance_ledger";

    private final Map<String, TownFinanceEntryRecord> entries = new LinkedHashMap<>();

    public static TownFinanceSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new TownFinanceSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(TownFinanceSavedData::load, TownFinanceSavedData::new, DATA_NAME);
    }

    public static TownFinanceSavedData load(CompoundTag tag) {
        TownFinanceSavedData data = new TownFinanceSavedData();
        ListTag entriesTag = tag.getList("TownFinanceEntries", Tag.TAG_COMPOUND);
        for (Tag raw : entriesTag) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            TownFinanceEntryRecord record = TownFinanceEntryRecord.load(compound);
            if (!record.entryId().isBlank()) {
                data.entries.put(record.entryId(), record);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag entriesTag = new ListTag();
        for (TownFinanceEntryRecord record : entries.values()) {
            entriesTag.add(record.save());
        }
        tag.put("TownFinanceEntries", entriesTag);
        return tag;
    }

    public Map<String, TownFinanceEntryRecord> getEntries() {
        return entries;
    }

    public void putEntry(TownFinanceEntryRecord record) {
        if (record == null || record.entryId().isBlank()) {
            return;
        }
        entries.put(record.entryId(), record);
        setDirty();
    }
}
