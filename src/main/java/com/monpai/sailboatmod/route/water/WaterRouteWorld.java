package com.monpai.sailboatmod.route.water;

public interface WaterRouteWorld {
    WaterColumn sample(int x, int z, WaterRoutePolicy policy);

    boolean canLoadMoreChunks(int requested);

    int consumedChunkLoads();
}
