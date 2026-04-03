package com.monpai.sailboatmod.nation.model;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record RoadNetworkRecord(
        String roadId,
        String nationId,
        String townId,
        String dimensionId,
        String structureAId,
        String structureBId,
        List<BlockPos> path,
        long updatedAt
) {
    public RoadNetworkRecord {
        roadId = roadId == null ? "" : roadId.trim().toLowerCase(Locale.ROOT);
        nationId = nationId == null ? "" : nationId.trim().toLowerCase(Locale.ROOT);
        townId = townId == null ? "" : townId.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        structureAId = structureAId == null ? "" : structureAId.trim();
        structureBId = structureBId == null ? "" : structureBId.trim();
        path = path == null ? List.of() : List.copyOf(path);
    }

    public static String edgeKey(String leftStructureId, String rightStructureId) {
        String left = leftStructureId == null ? "" : leftStructureId.trim().toLowerCase(Locale.ROOT);
        String right = rightStructureId == null ? "" : rightStructureId.trim().toLowerCase(Locale.ROOT);
        if (left.isBlank() || right.isBlank() || left.equals(right)) {
            return "";
        }
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }

    public boolean connects(String structureId) {
        if (structureId == null || structureId.isBlank()) {
            return false;
        }
        return structureAId.equalsIgnoreCase(structureId) || structureBId.equalsIgnoreCase(structureId);
    }

    public boolean sameScope(String scopeTownId, String scopeNationId, String scopeDimensionId) {
        if (scopeDimensionId == null || !dimensionId.equals(scopeDimensionId)) {
            return false;
        }
        if (!townId.isBlank() && scopeTownId != null && !scopeTownId.isBlank()) {
            return townId.equals(scopeTownId);
        }
        return scopeNationId != null && !scopeNationId.isBlank() && nationId.equalsIgnoreCase(scopeNationId);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", roadId);
        tag.putString("NationId", nationId);
        tag.putString("TownId", townId);
        tag.putString("Dim", dimensionId);
        tag.putString("A", structureAId);
        tag.putString("B", structureBId);
        tag.putLong("UpdatedAt", updatedAt);
        ListTag pathTag = new ListTag();
        for (BlockPos pos : path) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", pos.asLong());
            pathTag.add(entry);
        }
        tag.put("Path", pathTag);
        return tag;
    }

    public static RoadNetworkRecord load(CompoundTag tag) {
        List<BlockPos> path = new ArrayList<>();
        ListTag pathTag = tag.getList("Path", Tag.TAG_COMPOUND);
        for (int i = 0; i < pathTag.size(); i++) {
            path.add(BlockPos.of(pathTag.getCompound(i).getLong("Pos")));
        }
        String structureA = tag.getString("A");
        String structureB = tag.getString("B");
        String roadId = tag.contains("Id") ? tag.getString("Id") : edgeKey(structureA, structureB);
        return new RoadNetworkRecord(
                roadId,
                tag.getString("NationId"),
                tag.getString("TownId"),
                tag.getString("Dim"),
                structureA,
                structureB,
                path,
                tag.getLong("UpdatedAt")
        );
    }
}
