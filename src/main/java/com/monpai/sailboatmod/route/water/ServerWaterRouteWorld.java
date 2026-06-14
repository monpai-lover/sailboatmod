package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;

import java.util.HashSet;
import java.util.Set;

public final class ServerWaterRouteWorld implements WaterRouteWorld, DockBerthResolver.BerthWorld {
    private final ServerLevel level;
    private final Set<Long> loadedChunks = new HashSet<>();
    private int consumedChunkLoads;

    public ServerWaterRouteWorld(ServerLevel level) {
        this.level = level;
    }

    @Override
    public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
        if (level == null || !ensureChunk(x, z)) {
            return WaterColumn.blocked();
        }
        int y = findWaterSurfaceY(x, z, policy);
        if (y == Integer.MIN_VALUE) {
            return WaterColumn.blocked();
        }
        return WaterColumn.passable(new BlockPos(x, y, z), 0.0D);
    }

    @Override
    public boolean canLoadMoreChunks(int requested) {
        return requested > 0;
    }

    @Override
    public int consumedChunkLoads() {
        return consumedChunkLoads;
    }

    @Override
    public boolean isBerthWater(int x, int z, WaterRoutePolicy policy) {
        return sample(x, z, policy).passable();
    }

    @Override
    public int waterSurfaceY(int x, int z) {
        int y = findWaterSurfaceY(x, z, WaterRoutePolicy.defaults());
        return y == Integer.MIN_VALUE ? level.getSeaLevel() : y;
    }

    private boolean ensureChunk(int x, int z) {
        long key = chunkKey(x >> 4, z >> 4);
        if (loadedChunks.contains(key)) {
            return true;
        }
        ChunkAccess chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
        if (chunk == null) {
            return false;
        }
        loadedChunks.add(key);
        consumedChunkLoads++;
        return true;
    }

    private int findWaterSurfaceY(int x, int z, WaterRoutePolicy policy) {
        WaterRoutePolicy effective = policy == null ? WaterRoutePolicy.defaults() : policy;
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 1;
        int top = Math.min(maxY, level.getSeaLevel() + 24);
        int bottom = Math.max(minY, level.getSeaLevel() - 32);
        for (int y = top; y >= bottom; y--) {
            if (isWaterFootprint(x, y, z, effective) && hasClearance(x, y, z, effective)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private boolean isWaterFootprint(int x, int y, int z, WaterRoutePolicy policy) {
        int halfWidth = Math.max(0, policy.boatHalfWidth());
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                if (!level.getFluidState(new BlockPos(x + dx, y, z + dz)).is(FluidTags.WATER)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasClearance(int x, int y, int z, WaterRoutePolicy policy) {
        int halfWidth = Math.max(0, policy.boatHalfWidth());
        int clearance = Math.max(1, policy.clearanceHeight());
        for (int dy = 1; dy <= clearance; dy++) {
            for (int dx = -halfWidth; dx <= halfWidth; dx++) {
                for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                    BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
                    if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
    }
}
