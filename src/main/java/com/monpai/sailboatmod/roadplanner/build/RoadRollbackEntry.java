package com.monpai.sailboatmod.roadplanner.build;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.UUID;

public record RoadRollbackEntry(UUID routeId,
                                UUID edgeId,
                                UUID jobId,
                                UUID actorId,
                                long timestamp,
                                BlockPos pos,
                                BlockState originalState,
                                CompoundTag originalBlockEntityNbt) {
    public RoadRollbackEntry {
        if (routeId == null || edgeId == null || jobId == null || actorId == null) {
            throw new IllegalArgumentException("ids cannot be null");
        }
        if (pos == null) {
            throw new IllegalArgumentException("pos cannot be null");
        }
        pos = pos.immutable();
        if (originalState == null) {
            throw new IllegalArgumentException("originalState cannot be null");
        }
        originalBlockEntityNbt = originalBlockEntityNbt == null ? null : originalBlockEntityNbt.copy();
    }

    public Optional<CompoundTag> blockEntityNbt() {
        return Optional.ofNullable(originalBlockEntityNbt).map(CompoundTag::copy);
    }
}
