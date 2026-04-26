package com.monpai.sailboatmod.roadplanner.map;

@FunctionalInterface
public interface RoadMapColumnSampler {
    RoadMapColumnSample sample(int worldX, int worldZ);
}
