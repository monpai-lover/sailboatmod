package com.monpai.sailboatmod.road.model;

public record BridgeSpan(int startIndex, int endIndex, int waterSurfaceY, int oceanFloorY,
                         BridgeSpanKind kind, int deckY, BridgeGapKind gapKind) {
    public BridgeSpan(int startIndex, int endIndex, int waterSurfaceY, int oceanFloorY) {
        this(startIndex, endIndex, waterSurfaceY, oceanFloorY,
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);
    }

    public BridgeSpan(int startIndex, int endIndex, int waterSurfaceY, int oceanFloorY,
                      BridgeSpanKind kind, int deckY) {
        this(startIndex, endIndex, waterSurfaceY, oceanFloorY, kind, deckY, BridgeGapKind.WATER_GAP);
    }

    public BridgeSpan {
        kind = kind == null ? BridgeSpanKind.REGULAR_BRIDGE : kind;
        gapKind = gapKind == null ? BridgeGapKind.WATER_GAP : gapKind;
    }

    public int length() {
        return endIndex - startIndex;
    }

    public boolean hasDeckY() {
        return deckY != Integer.MIN_VALUE;
    }

    public boolean waterGap() {
        return gapKind.includesWater();
    }

    public boolean landRavineGap() {
        return gapKind.includesLandRavine();
    }
}