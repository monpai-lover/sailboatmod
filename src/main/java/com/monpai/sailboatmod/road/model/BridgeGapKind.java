package com.monpai.sailboatmod.road.model;

public enum BridgeGapKind {
    WATER_GAP,
    LAND_RAVINE_GAP,
    MIXED_GAP;

    public boolean includesWater() {
        return this == WATER_GAP || this == MIXED_GAP;
    }

    public boolean includesLandRavine() {
        return this == LAND_RAVINE_GAP || this == MIXED_GAP;
    }
}