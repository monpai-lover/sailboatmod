package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerEndpointRulesTest {
    @Test
    void requiresFirstNodeInStartClaimAndLastNodeInDestinationClaim() {
        RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(
                new RoadPlannerClaimOverlay(0, 0, "start", "Start", "", "", RoadPlannerClaimOverlay.Role.START, 0, 0),
                new RoadPlannerClaimOverlay(4, 0, "dest", "Dest", "", "", RoadPlannerClaimOverlay.Role.DESTINATION, 0, 0)
        ));

        assertTrue(RoadPlannerEndpointRules.validate(List.of(new BlockPos(1, 64, 1), new BlockPos(70, 64, 1)), renderer).valid());
        assertFalse(RoadPlannerEndpointRules.validate(List.of(new BlockPos(32, 64, 1), new BlockPos(70, 64, 1)), renderer).valid());
        assertFalse(RoadPlannerEndpointRules.validate(List.of(new BlockPos(1, 64, 1), new BlockPos(32, 64, 1)), renderer).valid());
    }
}
