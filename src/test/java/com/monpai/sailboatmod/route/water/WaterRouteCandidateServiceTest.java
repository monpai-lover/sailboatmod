package com.monpai.sailboatmod.route.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WaterRouteCandidateServiceTest {
    private static final WaterRoutePermissionService.DockAccess SOURCE =
            new WaterRoutePermissionService.DockAccess("source-town", "nation-a");
    private static final WaterRoutePermissionService.DockAccess SAME_NATION =
            new WaterRoutePermissionService.DockAccess("target-town", "nation-a");
    private static final WaterRoutePermissionService.DockAccess FOREIGN =
            new WaterRoutePermissionService.DockAccess("target-town", "nation-b");

    @Test
    void listsSameNationWithoutAnyTerrainOrDiplomacyProbe() {
        assertTrue(WaterRouteCandidateService.canListCandidate(SOURCE, SAME_NATION, false,
                (a, b) -> { throw new AssertionError("Same-nation listing needs no lookup"); }).successful());
    }

    @Test
    void allowsAlliesAndTradeButStillRejectsWarAndUnknownRelations() {
        for (String relation : new String[]{"allied", "trade"}) {
            assertTrue(WaterRouteCandidateService.canListCandidate(SOURCE, FOREIGN, false,
                    (a, b) -> relation).successful());
        }
        for (String relation : new String[]{"war", "neutral", ""}) {
            assertEquals(WaterRouteFailureReason.NO_PERMISSION,
                    WaterRouteCandidateService.canListCandidate(SOURCE, FOREIGN, false,
                            (a, b) -> relation).reason());
        }
    }

    @Test
    void rejectsMissingEndpointsAndWrongTerminalKinds() {
        assertEquals(WaterRouteFailureReason.MISSING_SOURCE_DOCK,
                WaterRouteCandidateService.canListCandidate(null, SAME_NATION, false, null).reason());
        assertEquals(WaterRouteFailureReason.MISSING_TARGET_DOCK,
                WaterRouteCandidateService.canListCandidate(SOURCE, null, false, null).reason());
        assertEquals(WaterRouteFailureReason.INVALID_TERMINAL_KIND,
                WaterRouteCandidateService.canListCandidate(SOURCE, SAME_NATION, true, null).reason());
    }

    @Test
    void doesNotBypassTownOrNationOwnershipChecks() {
        assertEquals(WaterRouteFailureReason.MISSING_TOWN,
                WaterRouteCandidateService.canListCandidate(
                        new WaterRoutePermissionService.DockAccess("", "nation-a"), SAME_NATION, false, null).reason());
        assertEquals(WaterRouteFailureReason.MISSING_NATION,
                WaterRouteCandidateService.canListCandidate(
                        new WaterRoutePermissionService.DockAccess("source-town", ""), SAME_NATION, false, null).reason());
    }
}
