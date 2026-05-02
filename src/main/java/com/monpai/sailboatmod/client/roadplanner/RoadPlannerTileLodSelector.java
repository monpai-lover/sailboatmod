package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;

public final class RoadPlannerTileLodSelector {
    private RoadPlannerTileLodSelector() {
    }

    public static MapLod select(double scale) {
        if (scale >= 2.0D) {
            return MapLod.LOD_1;
        }
        if (scale >= 1.0D) {
            return MapLod.LOD_2;
        }
        if (scale >= 0.5D) {
            return MapLod.LOD_4;
        }
        return MapLod.LOD_8;
    }
}
