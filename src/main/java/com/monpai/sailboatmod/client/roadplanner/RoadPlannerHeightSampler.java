package com.monpai.sailboatmod.client.roadplanner;

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
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        };
    }

    default BlockPos blockPosAt(int x, int z) {
        return new BlockPos(x, heightAt(x, z), z);
    }
}
