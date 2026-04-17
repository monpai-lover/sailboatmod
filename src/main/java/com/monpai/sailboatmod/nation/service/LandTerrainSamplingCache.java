package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

public final class LandTerrainSamplingCache {
    private final Level level;
    private final RoadPlanningPassContext context;
    private final Map<Long, BlockPos> surfaceCache = new HashMap<>();
    private final Map<Long, Integer> stabilityCache = new HashMap<>();
    private final Map<Long, Integer> nearWaterCache = new HashMap<>();

    public LandTerrainSamplingCache(Level level, RoadPlanningPassContext context) {
        this.level = level;
        this.context = context;
    }

    public BlockPos surface(int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        return surfaceCache.computeIfAbsent(key, ignored -> {
            if (context != null) {
                return context.resolveRoadSurface(x, z, (sampleX, sampleZ) -> RoadPathfinder.findSurfaceForPlanning(level, sampleX, sampleZ));
            }
            return RoadPathfinder.findSurfaceForPlanning(level, x, z);
        });
    }

    public int stability(BlockPos pos) {
        if (pos == null) {
            return 8;
        }
        long key = BlockPos.asLong(pos.getX(), 0, pos.getZ());
        return stabilityCache.computeIfAbsent(key, ignored -> {
            int maxDelta = 0;
            for (BlockPos neighbor : new BlockPos[]{pos.north(), pos.south(), pos.east(), pos.west()}) {
                BlockPos neighborSurface = surface(neighbor.getX(), neighbor.getZ());
                if (neighborSurface == null) {
                    maxDelta = Math.max(maxDelta, 6);
                    continue;
                }
                maxDelta = Math.max(maxDelta, Math.abs(neighborSurface.getY() - pos.getY()));
            }
            return maxDelta;
        });
    }

    public int nearWater(BlockPos pos) {
        if (pos == null) {
            return 4;
        }
        long key = BlockPos.asLong(pos.getX(), 0, pos.getZ());
        return nearWaterCache.computeIfAbsent(key, ignored -> {
            int count = 0;
            for (BlockPos neighbor : new BlockPos[]{pos, pos.north(), pos.south(), pos.east(), pos.west()}) {
                BlockPos surface = surface(neighbor.getX(), neighbor.getZ());
                if (surface == null) {
                    continue;
                }
                if (level.getBlockState(surface).liquid() || !level.getBlockState(surface).getFluidState().isEmpty()) {
                    count++;
                }
            }
            return count;
        });
    }
}
