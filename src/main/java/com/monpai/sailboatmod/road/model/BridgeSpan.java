package com.monpai.sailboatmod.road.model;

public record BridgeSpan(int startIndex, int endIndex, int waterSurfaceY, int oceanFloorY) {
    public int length() { return endIndex - startIndex; }
}
