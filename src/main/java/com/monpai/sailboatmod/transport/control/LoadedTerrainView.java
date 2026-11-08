package com.monpai.sailboatmod.transport.control;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;

/** Reads collision shapes without loading a chunk, including when a shape asks for its neighbors. */
public record LoadedTerrainView(ServerLevel level) implements BlockGetter {
    public boolean available(BlockPos at) {
        return !level.isOutsideBuildHeight(at) && chunk(at) != null;
    }

    private LevelChunk chunk(BlockPos at) {
        return level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
    }

    private LevelChunk requireChunk(BlockPos at) {
        LevelChunk chunk = chunk(at);
        if (chunk == null || level.isOutsideBuildHeight(at))
            throw new IllegalStateException("Collision shape requested unavailable terrain");
        return chunk;
    }

    @Override public BlockState getBlockState(BlockPos at) { return requireChunk(at).getBlockState(at); }
    @Override public FluidState getFluidState(BlockPos at) { return getBlockState(at).getFluidState(); }
    @Override public BlockEntity getBlockEntity(BlockPos at) {
        return requireChunk(at).getBlockEntity(at, LevelChunk.EntityCreationType.CHECK);
    }
    @Override public int getHeight() { return level.getHeight(); }
    @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
}
