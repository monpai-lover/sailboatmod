package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;
import java.util.Objects;

/**
 * Adapted from RoadWeaver's RoadSegmentPlacement model: center point plus footprint positions.
 */
public record RoadGraphSegmentPlacement(BlockPos middlePos, List<BlockPos> positions) {
    public RoadGraphSegmentPlacement {
        middlePos = middlePos == null ? BlockPos.ZERO : middlePos.immutable();
        positions = positions == null ? List.of() : positions.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Middle", middlePos.asLong());
        ListTag positionsTag = new ListTag();
        for (BlockPos position : positions) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", position.asLong());
            positionsTag.add(entry);
        }
        tag.put("Positions", positionsTag);
        return tag;
    }

    public static RoadGraphSegmentPlacement load(CompoundTag tag) {
        BlockPos middle = BlockPos.of(tag.getLong("Middle"));
        java.util.ArrayList<BlockPos> positions = new java.util.ArrayList<>();
        ListTag positionsTag = tag.getList("Positions", Tag.TAG_COMPOUND);
        for (int index = 0; index < positionsTag.size(); index++) {
            positions.add(BlockPos.of(positionsTag.getCompound(index).getLong("Pos")));
        }
        return new RoadGraphSegmentPlacement(middle, positions);
    }
}
