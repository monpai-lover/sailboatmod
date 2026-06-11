package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerMapPreloadBudgetTest {
    @Test
    void forceRenderGetsOneTilePerTick() {
        assertEquals(1, RoadPlannerMapPreloadBudget.tilesForPurpose(RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER));
    }

    @Test
    void globalLevelBudgetCapsTotalTileWork() {
        RoadPlannerMapPreloadBudget budget = new RoadPlannerMapPreloadBudget(3);

        assertEquals(1, budget.claim(RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER));
        assertEquals(2, budget.claim(RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD));
        assertEquals(0, budget.claim(RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD));
    }
}
