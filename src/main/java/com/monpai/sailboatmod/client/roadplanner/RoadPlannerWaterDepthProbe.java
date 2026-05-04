package com.monpai.sailboatmod.client.roadplanner;

@FunctionalInterface
public interface RoadPlannerWaterDepthProbe {
    int waterDepthAt(int x, int z);

    static RoadPlannerWaterDepthProbe shallowFromLandProbe(RoadPlannerBridgeRuleService.LandProbe landProbe) {
        RoadPlannerBridgeRuleService.LandProbe safeLandProbe = landProbe == null ? (x, z) -> true : landProbe;
        return (x, z) -> safeLandProbe.isLand(x, z) ? 0 : 1;
    }
}
