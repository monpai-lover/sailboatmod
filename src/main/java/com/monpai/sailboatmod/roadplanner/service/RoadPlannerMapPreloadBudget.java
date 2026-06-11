package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;

public final class RoadPlannerMapPreloadBudget {
    public static final int DEFAULT_GLOBAL_TILE_BUDGET_PER_LEVEL_TICK = 6;

    private int remaining;

    public RoadPlannerMapPreloadBudget(int globalBudget) {
        this.remaining = Math.max(0, globalBudget);
    }

    public int claim(RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        int allowed = Math.min(remaining, tilesForPurpose(purpose));
        remaining -= allowed;
        return allowed;
    }

    public int remaining() {
        return remaining;
    }

    public static int tilesForPurpose(RoadPlannerMapPreloadRequestPacket.Purpose purpose) {
        if (purpose == RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER) {
            return 1;
        }
        if (purpose == RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH) {
            return 1;
        }
        return 2;
    }
}
