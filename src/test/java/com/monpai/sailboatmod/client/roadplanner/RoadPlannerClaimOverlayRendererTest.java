package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void adjacentSameOwnerChunksSuppressInternalBorder() {
        RoadPlannerClaimOverlay left = new RoadPlannerClaimOverlay(
                0, 0, "town-a", "Town A", "nation", "Nation", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlay right = new RoadPlannerClaimOverlay(
                1, 0, "town-a", "Town A", "nation", "Nation", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(left, right));

        assertFalse(renderer.visibleBorderForTest(left, RoadPlannerClaimOverlayRenderer.BorderSide.EAST));
        assertFalse(renderer.visibleBorderForTest(right, RoadPlannerClaimOverlayRenderer.BorderSide.WEST));
        assertTrue(renderer.visibleBorderForTest(left, RoadPlannerClaimOverlayRenderer.BorderSide.NORTH));
    }

    @Test
    void differentRoleClaimsKeepBoundaryEvenWhenTownMatches() {
        RoadPlannerClaimOverlay start = new RoadPlannerClaimOverlay(
                0, 0, "town-a", "Town A", "nation", "Nation", RoadPlannerClaimOverlay.Role.START, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlay other = new RoadPlannerClaimOverlay(
                1, 0, "town-a", "Town A", "nation", "Nation", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(start, other));

        assertTrue(renderer.visibleBorderForTest(start, RoadPlannerClaimOverlayRenderer.BorderSide.EAST));
    }

    @Test
    void townAndNationIdsUseSeparateOwnerNamespaces() {
        RoadPlannerClaimOverlay town = new RoadPlannerClaimOverlay(
                0, 0, "alpha", "Town Alpha", "", "", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlay nation = new RoadPlannerClaimOverlay(
                1, 0, "", "", "alpha", "Nation Alpha", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(town, nation));

        assertTrue(renderer.visibleBorderForTest(town, RoadPlannerClaimOverlayRenderer.BorderSide.EAST));
        assertTrue(renderer.visibleBorderForTest(nation, RoadPlannerClaimOverlayRenderer.BorderSide.WEST));
    }

    @Test
    void blankOwnerClaimsKeepBoundary() {
        RoadPlannerClaimOverlay left = new RoadPlannerClaimOverlay(
                0, 0, "", "", "", "", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlay right = new RoadPlannerClaimOverlay(
                1, 0, "", "", "", "", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(left, right));

        assertTrue(renderer.visibleBorderForTest(left, RoadPlannerClaimOverlayRenderer.BorderSide.EAST));
        assertTrue(renderer.visibleBorderForTest(right, RoadPlannerClaimOverlayRenderer.BorderSide.WEST));
    }

    @Test
    void duplicateChunksUseFirstOverlayForClaimsAndBorders() {
        RoadPlannerClaimOverlay first = new RoadPlannerClaimOverlay(
                0, 0, "town-a", "Town A", "", "", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlay duplicate = new RoadPlannerClaimOverlay(
                0, 0, "town-b", "Town B", "", "", RoadPlannerClaimOverlay.Role.OTHER, 0xAA0000, 0x660000
        );
        RoadPlannerClaimOverlay neighbor = new RoadPlannerClaimOverlay(
                1, 0, "town-a", "Town A", "", "", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(first, duplicate, neighbor));

        assertEquals(first, renderer.claimAtWorld(0, 0).orElseThrow());
        assertFalse(renderer.visibleBorderForTest(neighbor, RoadPlannerClaimOverlayRenderer.BorderSide.WEST));
    }

    @Test
    void adjacentSameOwnerNegativeChunksSuppressInternalBorder() {
        RoadPlannerClaimOverlay west = new RoadPlannerClaimOverlay(
                -2, -1, "town-a", "Town A", "", "", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlay east = new RoadPlannerClaimOverlay(
                -1, -1, "town-a", "Town A", "", "", RoadPlannerClaimOverlay.Role.OTHER, 0x00AA00, 0x006600
        );
        RoadPlannerClaimOverlayRenderer renderer = new RoadPlannerClaimOverlayRenderer(List.of(west, east));

        assertFalse(renderer.visibleBorderForTest(west, RoadPlannerClaimOverlayRenderer.BorderSide.EAST));
        assertFalse(renderer.visibleBorderForTest(east, RoadPlannerClaimOverlayRenderer.BorderSide.WEST));
    }
}
