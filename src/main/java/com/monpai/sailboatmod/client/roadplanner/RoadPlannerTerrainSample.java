package com.monpai.sailboatmod.client.roadplanner;

public record RoadPlannerTerrainSample(int x,
                                       int z,
                                       int surfaceY,
                                       int waterDepth,
                                       Kind kind) {
    public RoadPlannerTerrainSample {
        waterDepth = Math.max(0, waterDepth);
        kind = kind == null ? Kind.UNKNOWN : kind;
        if (kind != Kind.WATER) {
            waterDepth = 0;
        }
    }

    public enum Kind {
        LAND,
        WATER,
        UNKNOWN
    }
}
