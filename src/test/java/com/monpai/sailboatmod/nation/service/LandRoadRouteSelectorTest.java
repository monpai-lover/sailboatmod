package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LandRoadRouteSelectorTest {
    @Test
    void selectorKeepsLegacyPathWhenQualityIsAcceptable() {
        LandRoadRouteSelector.Selection selection = LandRoadRouteSelector.selectForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                RoadPlanningFailureReason.NONE,
                0,
                0,
                0
        );

        assertEquals(LandRoadRouteSelector.BackEnd.LEGACY, selection.backEnd());
    }

    @Test
    void selectorSwitchesToHybridWhenLegacyFailsContinuousGround() {
        LandRoadRouteSelector.Selection selection = LandRoadRouteSelector.selectForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(40, 90, 0),
                List.of(),
                RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE,
                12,
                8,
                20
        );

        assertEquals(LandRoadRouteSelector.BackEnd.HYBRID, selection.backEnd());
    }

    @Test
    void selectorDoesNotKeepFailedLegacyBackendWhenNoGroundPathWasProduced() {
        LandRoadRouteSelector.Selection selection = LandRoadRouteSelector.selectForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(12, 67, 0),
                List.of(),
                RoadPlanningFailureReason.SEARCH_EXHAUSTED,
                0,
                0,
                0
        );

        assertEquals(LandRoadRouteSelector.BackEnd.HYBRID, selection.backEnd());
    }

    @Test
    void selectorSwitchesToHybridWhenSearchExhaustedDespiteLegacyPath() {
        LandRoadRouteSelector.Selection selection = LandRoadRouteSelector.selectForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(12, 67, 0),
                List.of(new BlockPos(0, 64, 0), new BlockPos(12, 67, 0)),
                RoadPlanningFailureReason.SEARCH_EXHAUSTED,
                0,
                0,
                0
        );

        assertEquals(LandRoadRouteSelector.BackEnd.HYBRID, selection.backEnd());
    }
}
