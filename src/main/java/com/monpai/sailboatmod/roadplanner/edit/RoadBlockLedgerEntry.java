package com.monpai.sailboatmod.roadplanner.edit;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.Set;

public record RoadBlockLedgerEntry(BlockPos pos,
                                   BlockState originalState,
                                   BlockState roadState,
                                   Set<String> ownerSegmentIds,
                                   String lastKnownRoadId,
                                   long updatedAt) {
    public RoadBlockLedgerEntry {
        pos = pos == null ? BlockPos.ZERO : pos.immutable();
        originalState = originalState == null ? Blocks.AIR.defaultBlockState() : originalState;
        roadState = roadState == null ? Blocks.AIR.defaultBlockState() : roadState;
        ownerSegmentIds = normalizeOwners(ownerSegmentIds);
        lastKnownRoadId = lastKnownRoadId == null ? "" : lastKnownRoadId.trim().toLowerCase(java.util.Locale.ROOT);
        updatedAt = Math.max(0L, updatedAt);
    }

    public int refCount() {
        return ownerSegmentIds.size();
    }

    public RoadBlockLedgerEntry withOwner(String segmentId, long timestamp) {
        LinkedHashSet<String> owners = new LinkedHashSet<>(ownerSegmentIds);
        String normalized = normalizeOwner(segmentId);
        if (!normalized.isBlank()) {
            owners.add(normalized);
        }
        return new RoadBlockLedgerEntry(pos, originalState, roadState, owners, lastKnownRoadId, timestamp);
    }

    public RoadBlockLedgerEntry withoutOwner(String segmentId, long timestamp) {
        LinkedHashSet<String> owners = new LinkedHashSet<>(ownerSegmentIds);
        owners.remove(normalizeOwner(segmentId));
        return new RoadBlockLedgerEntry(pos, originalState, roadState, owners, lastKnownRoadId, timestamp);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Pos", pos.asLong());
        tag.put("OriginalState", NbtUtils.writeBlockState(originalState));
        tag.put("RoadState", NbtUtils.writeBlockState(roadState));
        ListTag owners = new ListTag();
        for (String owner : ownerSegmentIds) {
            owners.add(StringTag.valueOf(owner));
        }
        tag.put("OwnerSegmentIds", owners);
        tag.putString("LastKnownRoadId", lastKnownRoadId);
        tag.putLong("UpdatedAt", updatedAt);
        return tag;
    }

    public static RoadBlockLedgerEntry load(CompoundTag tag) {
        return new RoadBlockLedgerEntry(
                BlockPos.of(tag.getLong("Pos")),
                readState(tag.getCompound("OriginalState")),
                readState(tag.getCompound("RoadState")),
                readOwners(tag.getList("OwnerSegmentIds", Tag.TAG_STRING)),
                tag.getString("LastKnownRoadId"),
                tag.getLong("UpdatedAt"));
    }

    private static BlockState readState(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return Blocks.AIR.defaultBlockState();
        }
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), tag);
    }

    private static Set<String> readOwners(ListTag tag) {
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        for (Tag raw : tag) {
            if (raw instanceof StringTag stringTag) {
                String owner = normalizeOwner(stringTag.getAsString());
                if (!owner.isBlank()) {
                    owners.add(owner);
                }
            }
        }
        return owners;
    }

    private static Set<String> normalizeOwners(Set<String> ownerSegmentIds) {
        if (ownerSegmentIds == null || ownerSegmentIds.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        for (String owner : ownerSegmentIds) {
            String normalized = normalizeOwner(owner);
            if (!normalized.isBlank()) {
                owners.add(normalized);
            }
        }
        return Set.copyOf(owners);
    }

    private static String normalizeOwner(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
