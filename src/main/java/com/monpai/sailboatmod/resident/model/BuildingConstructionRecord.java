package com.monpai.sailboatmod.resident.model;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.ArrayList;
import java.util.List;

public record BuildingConstructionRecord(
    String buildingId,
    String structureType,
    BlockPos position,
    int currentLayer,
    int totalLayers,
    List<String> assignedBuilders,
    long startTime,
    List<BlockPos> scaffoldPositions
) {
    public boolean isComplete() {
        return currentLayer >= totalLayers;
    }

    public float progress() {
        return totalLayers > 0 ? (float) currentLayer / totalLayers : 0;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("BuildingId", buildingId);
        tag.putString("StructureType", structureType);
        tag.putInt("X", position.getX());
        tag.putInt("Y", position.getY());
        tag.putInt("Z", position.getZ());
        tag.putInt("CurrentLayer", currentLayer);
        tag.putInt("TotalLayers", totalLayers);
        ListTag builders = new ListTag();
        for (String builder : assignedBuilders) {
            builders.add(StringTag.valueOf(builder));
        }
        tag.put("Builders", builders);
        tag.putLong("StartTime", startTime);

        ListTag scaffolds = new ListTag();
        for (BlockPos pos : scaffoldPositions) {
            CompoundTag posTag = new CompoundTag();
            posTag.putInt("X", pos.getX());
            posTag.putInt("Y", pos.getY());
            posTag.putInt("Z", pos.getZ());
            scaffolds.add(posTag);
        }
        tag.put("Scaffolds", scaffolds);
        return tag;
    }

    public static BuildingConstructionRecord load(CompoundTag tag) {
        List<String> builders = new ArrayList<>();
        ListTag builderList = tag.getList("Builders", 8);
        for (int i = 0; i < builderList.size(); i++) {
            builders.add(builderList.getString(i));
        }

        List<BlockPos> scaffolds = new ArrayList<>();
        if (tag.contains("Scaffolds")) {
            ListTag scaffoldList = tag.getList("Scaffolds", 10);
            for (int i = 0; i < scaffoldList.size(); i++) {
                CompoundTag posTag = scaffoldList.getCompound(i);
                scaffolds.add(new BlockPos(posTag.getInt("X"), posTag.getInt("Y"), posTag.getInt("Z")));
            }
        }

        return new BuildingConstructionRecord(
            tag.getString("BuildingId"),
            tag.getString("StructureType"),
            new BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")),
            tag.getInt("CurrentLayer"),
            tag.getInt("TotalLayers"),
            builders,
            tag.getLong("StartTime"),
            scaffolds
        );
    }
}
