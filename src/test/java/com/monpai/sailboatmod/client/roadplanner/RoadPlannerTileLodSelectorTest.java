package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerTileLodSelectorTest {
    @Test
    void scaleThresholdsPreferHigherPrecisionWhenZoomedIn() {
        assertEquals(MapLod.LOD_1, RoadPlannerTileLodSelector.select(3.0D));
        assertEquals(MapLod.LOD_2, RoadPlannerTileLodSelector.select(1.5D));
        assertEquals(MapLod.LOD_4, RoadPlannerTileLodSelector.select(0.8D));
        assertEquals(MapLod.LOD_8, RoadPlannerTileLodSelector.select(0.3D));
    }
}
