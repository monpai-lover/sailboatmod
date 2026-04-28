package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerClaimOverlayRendererTest {
    @Test
    void findsClaimByWorldPosition() {
        RoadPlannerClaimOverlay overlay = new RoadPlannerClaimOverlay(
                2, -3, "town", "Town", "nation", "Nation", RoadPlannerClaimOverlay.Role.START, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(overlay));

        assertEquals(overlay, renderer.claimAtWorld(35, -40).orElseThrow());
    }

    @Test
    void destinationClaimsUseRedOverlayColor() {
        RoadPlannerClaimOverlay overlay = new RoadPlannerClaimOverlay(
                1, 1, "dest", "Dest", "", "", RoadPlannerClaimOverlay.Role.DESTINATION, 0xFF3333, 0xAA0000
        );

        assertTrue((RoadPlannerClaimOverlayRenderer.fillColor(overlay) & 0x00FF0000) != 0);
    }
}
