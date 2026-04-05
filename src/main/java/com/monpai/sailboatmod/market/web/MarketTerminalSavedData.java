package com.monpai.sailboatmod.market.web;

import net.minecraft.core.BlockPos;
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

public class MarketTerminalSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_market_terminals";

    private final Map<String, MarketTerminalEntry> entries = new LinkedHashMap<>();

    public static MarketTerminalSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new MarketTerminalSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(MarketTerminalSavedData::load, MarketTerminalSavedData::new, DATA_NAME);
    }

    public static MarketTerminalSavedData load(CompoundTag tag) {
        MarketTerminalSavedData data = new MarketTerminalSavedData();
        ListTag list = tag.getList("Entries", Tag.TAG_COMPOUND);
        for (Tag raw : list) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            MarketTerminalEntry entry = MarketTerminalEntry.load(compound);
            if (!entry.dimensionId().isBlank()) {
                data.entries.put(entry.key(), entry);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (MarketTerminalEntry entry : entries.values()) {
            list.add(entry.save());
        }
        tag.put("Entries", list);
        return tag;
    }

    public void putEntry(MarketTerminalEntry entry) {
        if (entry == null || entry.dimensionId().isBlank()) {
            return;
        }
        entries.put(entry.key(), entry);
        setDirty();
    }

    public void removeEntry(String dimensionId, BlockPos pos) {
        if (dimensionId == null || dimensionId.isBlank() || pos == null) {
            return;
        }
        if (entries.remove(MarketTerminalEntry.keyFor(dimensionId, pos)) != null) {
            setDirty();
        }
    }

    public List<MarketTerminalEntry> entries() {
        return new ArrayList<>(entries.values());
    }

    public record MarketTerminalEntry(
            String dimensionId,
            BlockPos marketPos,
            String marketName,
            String ownerUuid,
            String ownerName
    ) {
        public MarketTerminalEntry {
            dimensionId = dimensionId == null ? "" : dimensionId.trim();
            marketPos = marketPos == null ? BlockPos.ZERO : marketPos.immutable();
            marketName = marketName == null ? "" : marketName.trim();
            ownerUuid = ownerUuid == null ? "" : ownerUuid.trim();
            ownerName = ownerName == null ? "" : ownerName.trim();
        }

        public String key() {
            return keyFor(dimensionId, marketPos);
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("DimensionId", dimensionId);
            tag.putLong("MarketPos", marketPos.asLong());
            tag.putString("MarketName", marketName);
            tag.putString("OwnerUuid", ownerUuid);
            tag.putString("OwnerName", ownerName);
            return tag;
        }

        public static MarketTerminalEntry load(CompoundTag tag) {
            return new MarketTerminalEntry(
                    tag.getString("DimensionId"),
                    BlockPos.of(tag.getLong("MarketPos")),
                    tag.getString("MarketName"),
                    tag.getString("OwnerUuid"),
                    tag.getString("OwnerName")
            );
        }

        public static String keyFor(String dimensionId, BlockPos pos) {
            return (dimensionId == null ? "" : dimensionId.trim()) + "|" + (pos == null ? 0L : pos.asLong());
        }
    }
}
