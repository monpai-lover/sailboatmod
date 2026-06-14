package com.monpai.sailboatmod.roadplanner.map;

public class RoadMapColorizer {
    public int color(RoadMapColumnSample sample) {
        if (RoadMapServerColumnSampler.isUnavailableSample(sample)) {
            return 0x00000000;
        }
        if (sample.water()) {
            return RoadMapRenderStyle.styleWater(sample.waterDepth());
        }
        int delta = sample.surfaceY() - sample.reliefBaseY();
        return RoadMapRenderStyle.styleTerrainByDelta(sample.baseArgb(), delta);
    }
}
