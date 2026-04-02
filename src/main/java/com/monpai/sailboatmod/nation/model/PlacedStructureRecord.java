package com.monpai.sailboatmod.nation.model;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import java.util.Locale;

public record PlacedStructureRecord(
        String structureId,
        String nationId,
        String townId,
        String structureType,
        String dimensionId,
        long originPos,
        int sizeW,
        int sizeH,
        int sizeD,
        long placedAt,
        int buildingLevel,
        boolean isBuilt,
        int rotation
) {
    public PlacedStructureRecord {
        structureId = structureId == null ? "" : structureId.trim();
        nationId = nationId == null ? "" : nationId.trim().toLowerCase(Locale.ROOT);
        townId = townId == null ? "" : townId.trim();
        structureType = structureType == null ? "" : structureType.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        buildingLevel = Math.max(1, Math.min(3, buildingLevel));
        rotation = Math.floorMod(rotation, 4);
    }

    public int getMaxLevel() {
        return switch(structureType.toLowerCase()) {
            case "cottage" -> 3;
            case "tavern", "school" -> 2;
            default -> 1;
        };
    }

    public boolean canUpgrade() {
        return isBuilt && buildingLevel < getMaxLevel();
    }

    public BlockPos origin() { return BlockPos.of(originPos); }

    public BlockPos center() {
        BlockPos o = origin();
        return new BlockPos(o.getX() + sizeW / 2, o.getY(), o.getZ() + sizeD / 2);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", structureId);
        tag.putString("NationId", nationId);
        tag.putString("TownId", townId);
        tag.putString("Type", structureType);
        tag.putString("Dim", dimensionId);
        tag.putLong("Pos", originPos);
        tag.putInt("W", sizeW);
        tag.putInt("H", sizeH);
        tag.putInt("D", sizeD);
        tag.putLong("PlacedAt", placedAt);
        tag.putInt("Level", buildingLevel);
        tag.putBoolean("Built", isBuilt);
        tag.putInt("Rotation", rotation);
        return tag;
    }

    public static PlacedStructureRecord load(CompoundTag tag) {
        return new PlacedStructureRecord(
                tag.getString("Id"),
                tag.getString("NationId"),
                tag.getString("TownId"),
                tag.getString("Type"),
                tag.getString("Dim"),
                tag.getLong("Pos"),
                tag.getInt("W"),
                tag.getInt("H"),
                tag.getInt("D"),
                tag.getLong("PlacedAt"),
                tag.contains("Level") ? tag.getInt("Level") : 1,
                tag.contains("Built") ? tag.getBoolean("Built") : true,
                tag.contains("Rotation") ? tag.getInt("Rotation") : 0
        );
    }
}
