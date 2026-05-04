package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpander;
import com.monpai.sailboatmod.roadplanner.structure.RoadStructureMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public final class RoadPlannerBuildStepCompiler {
    private RoadPlannerBuildStepCompiler() {
    }

    public static List<BuildStep> compile(List<BlockPos> nodes,
                                          List<RoadPlannerSegmentType> segmentTypes,
                                          RoadPlannerBuildSettings settings,
                                          ServerLevel level) {
        return RoadNodeStructureExpander.expand(
                nodes,
                segmentTypes,
                settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings,
                level,
                RoadStructureMode.BUILD
        ).buildSteps();
    }

    public static List<BuildStep> compileForTest(List<BlockPos> nodes,
                                                 List<RoadPlannerSegmentType> segmentTypes,
                                                 RoadPlannerBuildSettings settings) {
        return compile(nodes, segmentTypes, settings, null);
    }
}
