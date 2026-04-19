package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

public class FastHeightSampler {
    private final ServerLevel level;

    public FastHeightSampler(ServerLevel level) {
        this.level = level;
    }

    public int surfaceHeight(int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
    }

    public int motionBlockingHeight(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }
}
