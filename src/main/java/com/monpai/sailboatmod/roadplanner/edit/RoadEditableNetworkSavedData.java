package com.monpai.sailboatmod.roadplanner.edit;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RoadEditableNetworkSavedData extends SavedData {
    public static final String DATA_NAME = "sailboatmod_editable_roads";

    private final Map<String, RoadEditableRecord> roads = new LinkedHashMap<>();
    private final Map<String, java.util.LinkedHashSet<String>> roadIdsByTownConnection = new LinkedHashMap<>();
    private final Map<Long, RoadBlockLedgerEntry> ledgerByPos = new LinkedHashMap<>();

    public static RoadEditableNetworkSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new RoadEditableNetworkSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(
                RoadEditableNetworkSavedData::load,
                RoadEditableNetworkSavedData::new,
                DATA_NAME);
    }

    public static RoadEditableNetworkSavedData load(CompoundTag tag) {
        RoadEditableNetworkSavedData data = new RoadEditableNetworkSavedData();
        ListTag roadTag = tag.getList("Roads", Tag.TAG_COMPOUND);
        for (int index = 0; index < roadTag.size(); index++) {
            RoadEditableRecord road = RoadEditableRecord.load(roadTag.getCompound(index));
            data.putRoadInternal(road);
        }
        ListTag ledgerTag = tag.getList("Ledger", Tag.TAG_COMPOUND);
        for (int index = 0; index < ledgerTag.size(); index++) {
            RoadBlockLedgerEntry entry = RoadBlockLedgerEntry.load(ledgerTag.getCompound(index));
            data.ledgerByPos.put(entry.pos().asLong(), entry);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag roadTag = new ListTag();
        for (RoadEditableRecord road : roads.values()) {
            roadTag.add(road.save());
        }
        tag.put("Roads", roadTag);

        ListTag ledgerTag = new ListTag();
        for (RoadBlockLedgerEntry entry : ledgerByPos.values()) {
            ledgerTag.add(entry.save());
        }
        tag.put("Ledger", ledgerTag);
        return tag;
    }

    public Collection<RoadEditableRecord> roads() {
        return List.copyOf(roads.values());
    }

    public Optional<RoadEditableRecord> getRoad(String roadId) {
        return Optional.ofNullable(roads.get(normalizeId(roadId)));
    }

    public List<RoadEditableRecord> roadsForTownConnection(String leftTownId, String rightTownId) {
        String key = RoadNetworkRecord.townConnectionKey(leftTownId, rightTownId);
        if (key.isBlank()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> roadIds = roadIdsByTownConnection.get(key);
        if (roadIds == null || roadIds.isEmpty()) {
            return List.of();
        }
        return roadIds.stream()
                .map(roads::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    public void putRoad(RoadEditableRecord road) {
        if (putRoadInternal(road)) {
            setDirty();
        }
    }

    public Optional<RoadBlockLedgerEntry> ledgerAt(BlockPos pos) {
        if (pos == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(ledgerByPos.get(pos.asLong()));
    }

    public void putLedgerEntry(RoadBlockLedgerEntry entry) {
        if (entry == null) {
            return;
        }
        ledgerByPos.put(entry.pos().asLong(), entry);
        setDirty();
    }

    public boolean removeLedgerAt(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        boolean removed = ledgerByPos.remove(pos.asLong()) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    private boolean putRoadInternal(RoadEditableRecord road) {
        if (road == null || road.roadId().isBlank()) {
            return false;
        }
        RoadEditableRecord previous = roads.put(road.roadId(), road);
        if (previous != null) {
            deindex(previous);
        }
        index(road);
        return true;
    }

    private void index(RoadEditableRecord road) {
        String key = road == null ? "" : road.townConnectionKey();
        if (key.isBlank()) {
            return;
        }
        roadIdsByTownConnection.computeIfAbsent(key, ignored -> new java.util.LinkedHashSet<>()).add(road.roadId());
    }

    private void deindex(RoadEditableRecord road) {
        String key = road == null ? "" : road.townConnectionKey();
        if (key.isBlank()) {
            return;
        }
        java.util.LinkedHashSet<String> roadIds = roadIdsByTownConnection.get(key);
        if (roadIds == null) {
            return;
        }
        roadIds.remove(road.roadId());
        if (roadIds.isEmpty()) {
            roadIdsByTownConnection.remove(key);
        }
    }

    private static String normalizeId(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
