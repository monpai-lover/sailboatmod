package com.monpai.sailboatmod.roadplanner.weaver.model;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public record WeaverRoadData(
        int width,
        int roadType,
        List<BlockState> materials,
        List<BlockState> slabMaterials,
        List<WeaverRoadSegmentPlacement> roadSegmentList,
        List<WeaverRoadSpan> spans,
        List<Integer> targetY,
        long ownerA2dKey,
        long ownerB2dKey
) {
    public static final long NO_OWNER_2D = Long.MIN_VALUE;

    public WeaverRoadData {
        materials = List.copyOf(materials);
        slabMaterials = List.copyOf(slabMaterials);
        roadSegmentList = List.copyOf(roadSegmentList);
        spans = List.copyOf(spans);
        targetY = List.copyOf(targetY);
    }

    public boolean hasOwnerPair() {
        return ownerA2dKey != NO_OWNER_2D && ownerB2dKey != NO_OWNER_2D;
    }

    public boolean containsOwner(long owner2dKey) {
        return hasOwnerPair() && (ownerA2dKey == owner2dKey || ownerB2dKey == owner2dKey);
    }

    public long sharedOwnerWith(WeaverRoadData other) {
        if (other == null || !hasOwnerPair() || !other.hasOwnerPair()) {
            return NO_OWNER_2D;
        }
        if (other.containsOwner(ownerA2dKey)) {
            return ownerA2dKey;
        }
        if (other.containsOwner(ownerB2dKey)) {
            return ownerB2dKey;
        }
        return NO_OWNER_2D;
    }
}
