package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.pathfinding.cache.RoadSurfaceHeuristics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;

@FunctionalInterface
public interface RoadPlannerHeightSampler {
    int heightAt(int x, int z);

    static RoadPlannerHeightSampler clientLoadedTerrain() {
        return (x, z) -> {
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) {
                return 64;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            while (y > level.getMinBuildHeight()
                    && RoadSurfaceHeuristics.isIgnoredSurfaceNoise(level.getBlockState(new BlockPos(x, y, z)))) {
                y--;
            }
            return y + 1;
        };
    }

    default BlockPos blockPosAt(int x, int z) {
        return new BlockPos(x, heightAt(x, z), z);
    }
}
