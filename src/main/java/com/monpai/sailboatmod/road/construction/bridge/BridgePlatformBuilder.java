package com.monpai.sailboatmod.road.construction.bridge;

import com.monpai.sailboatmod.road.config.BridgeConfig;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.road.model.RoadMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class BridgePlatformBuilder {
    private final BridgeConfig config;

    public BridgePlatformBuilder(BridgeConfig config) {
        this.config = config;
    }

    public List<BuildStep> buildPlatform(BlockPos anchorCenter, Direction roadDir,
                                          int width, RoadMaterial material, int startOrder) {
        List<BuildStep> steps = new ArrayList<>();
        int halfWidth = width / 2;
        int length = config.getPlatformLength();
        int order = startOrder;

        Direction perpDir = roadDir.getClockWise();

        for (int l = 0; l < length; l++) {
            for (int w = -halfWidth; w <= halfWidth; w++) {
                BlockPos pos = anchorCenter
                    .relative(roadDir, l)
                    .relative(perpDir, w);
                steps.add(new BuildStep(order++, pos,
                    material.surface().defaultBlockState(), BuildPhase.SURFACE));
            }
        }
        return steps;
    }
}
