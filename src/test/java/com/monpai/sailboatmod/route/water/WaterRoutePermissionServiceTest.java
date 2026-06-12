package com.monpai.sailboatmod.route.water;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterRoutePermissionServiceTest {
    @Test
    void missingTownFailsBeforeDiplomacy() {
        WaterRoutePermissionService.DockAccess source = dock("", "nation-a");
        WaterRoutePermissionService.DockAccess target = dock("town-b", "nation-a");

        WaterRouteResult<Void> result = WaterRoutePermissionService.evaluate(source, target, (a, b) -> "allied");

        assertEquals(WaterRouteFailureReason.MISSING_TOWN, result.reason());
    }

    @Test
    void missingNationFails() {
        WaterRouteResult<Void> result = WaterRoutePermissionService.evaluate(
                dock("town-a", ""), dock("town-b", "nation-b"), (a, b) -> "trade");

        assertEquals(WaterRouteFailureReason.MISSING_NATION, result.reason());
    }

    @Test
    void sameNationSucceeds() {
        WaterRouteResult<Void> result = WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"), dock("town-b", "nation-a"), (a, b) -> "");

        assertTrue(result.successful());
    }

    @Test
    void tradeOrAlliedNationSucceeds() {
        assertTrue(WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"),
                dock("town-b", "nation-b"),
                relation(Map.of("nation-a|nation-b", "trade"))
        ).successful());
        assertTrue(WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"),
                dock("town-b", "nation-b"),
                relation(Map.of("nation-a|nation-b", "allied"))
        ).successful());
    }

    @Test
    void neutralEnemyOrMissingRelationFails() {
        assertEquals(WaterRouteFailureReason.NO_PERMISSION, WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"),
                dock("town-b", "nation-b"),
                relation(Map.of("nation-a|nation-b", "neutral"))
        ).reason());
        assertEquals(WaterRouteFailureReason.NO_PERMISSION, WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"),
                dock("town-b", "nation-b"),
                relation(Map.of("nation-a|nation-b", "enemy"))
        ).reason());
        assertEquals(WaterRouteFailureReason.NO_PERMISSION, WaterRoutePermissionService.evaluate(
                dock("town-a", "nation-a"),
                dock("town-b", "nation-b"),
                relation(Map.of())
        ).reason());
    }

    private static WaterRoutePermissionService.DockAccess dock(String townId, String nationId) {
        return new WaterRoutePermissionService.DockAccess(townId, nationId);
    }

    private static WaterRoutePermissionService.RelationLookup relation(Map<String, String> values) {
        return (left, right) -> values.getOrDefault(
                left + "|" + right,
                values.getOrDefault(right + "|" + left, "")
        );
    }
}
