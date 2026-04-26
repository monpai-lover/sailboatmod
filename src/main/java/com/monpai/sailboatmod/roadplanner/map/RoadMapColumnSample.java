package com.monpai.sailboatmod.roadplanner.map;

public record RoadMapColumnSample(
        int worldX,
        int surfaceY,
        int worldZ,
        int baseArgb,
        boolean water,
        int waterDepth,
        int reliefBaseY
) {
}
