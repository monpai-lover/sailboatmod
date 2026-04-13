package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlanningDebugLoggerTest {
    @Test
    void failureReasonExposesStableReasonCodeAndTranslationKey() {
        assertEquals("NO_CONTINUOUS_GROUND_ROUTE", RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE.reasonCode());
        assertEquals("message.sailboatmod.road_planner.failure.no_continuous_ground_route",
                RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE.translationKey());
    }

    @Test
    void debugLoggerIncludesRequestIdStageAndReason() {
        RoadPlanningRequestContext context = RoadPlanningRequestContext.create(
                "manual-road",
                "GoatDie Town 2",
                "GoatDie Town",
                new BlockPos(-847, 62, 215),
                new BlockPos(-623, 66, 219)
        );

        String message = RoadPlanningDebugLogger.failure(
                "GroundRouteAttempt",
                context,
                RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE,
                "visited=96001 blockedColumns=0"
        );

        assertTrue(message.contains("requestId=" + context.requestId()));
        assertTrue(message.contains("stage=GroundRouteAttempt"));
        assertTrue(message.contains("reason=NO_CONTINUOUS_GROUND_ROUTE"));
    }
}
