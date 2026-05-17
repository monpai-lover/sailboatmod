package com.monpai.sailboatmod.roadplanner.structure;

public enum RoadPlannerBridgeProfile {
    LOW_ARCH(16, 3, 4, false),
    LOW_BRIDGE(32, 3, 5, false),
    PIER_BRIDGE(Integer.MAX_VALUE, 5, 8, true);

    private final int maxSpanBlocks;
    private final int waterClearance;
    private final int maxRiseFromLowerShore;
    private final boolean piers;

    RoadPlannerBridgeProfile(int maxSpanBlocks, int waterClearance, int maxRiseFromLowerShore, boolean piers) {
        this.maxSpanBlocks = maxSpanBlocks;
        this.waterClearance = waterClearance;
        this.maxRiseFromLowerShore = maxRiseFromLowerShore;
        this.piers = piers;
    }

    public int waterClearance() {
        return waterClearance;
    }

    public int maxSpanBlocks() {
        return maxSpanBlocks;
    }

    public int maxRiseFromLowerShore() {
        return maxRiseFromLowerShore;
    }

    public boolean usesPiers() {
        return piers;
    }

    public static RoadPlannerBridgeProfile classify(int spanBlocks) {
        int safeSpan = Math.max(1, spanBlocks);
        for (RoadPlannerBridgeProfile profile : values()) {
            if (safeSpan <= profile.maxSpanBlocks) {
                return profile;
            }
        }
        return PIER_BRIDGE;
    }
}
