package com.monpai.sailboatmod.roadplanner.model;

public enum RoadPlannerMergeScope {
    OWN_NATION,
    ALLIED_OR_TRADE,
    DISABLED;

    public boolean allowsExternalRoads() {
        return this == ALLIED_OR_TRADE;
    }

    public boolean enabled() {
        return this != DISABLED;
    }
}
