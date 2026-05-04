package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.road.model.BuildStep;

import java.util.List;

@FunctionalInterface
public interface BridgeTemplateProvider {
    List<BuildStep> buildFromTemplate(List<RoadCenterlinePoint> bridgeCenterline,
                                      RoadPlannerBuildSettings settings,
                                      int startOrder);

    static BridgeTemplateProvider empty() {
        return (bridgeCenterline, settings, startOrder) -> List.of();
    }
}
