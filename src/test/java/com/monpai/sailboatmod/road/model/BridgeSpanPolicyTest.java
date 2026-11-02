package com.monpai.sailboatmod.road.model;

import com.monpai.sailboatmod.road.planning.RoutePolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeSpanPolicyTest {
    @Test
    void detourAllowsOnlyShortFlatWaterException() {
        BridgeSpan shortWater = new BridgeSpan(4, 9, 63, 55,
                BridgeSpanKind.SHORT_SPAN_FLAT, 66, BridgeGapKind.WATER_GAP);
        BridgeSpan regularWater = new BridgeSpan(4, 20, 63, 49,
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.WATER_GAP);

        assertTrue(RoutePolicy.DETOUR.allowsSpan(shortWater));
        assertFalse(RoutePolicy.DETOUR.allowsSpan(regularWater));
    }

    @Test
    void detourTreatsLandRavineSeparatelyFromWater() {
        BridgeSpan landRavine = new BridgeSpan(7, 11, 0, 58,
                BridgeSpanKind.SHORT_SPAN_FLAT, 70, BridgeGapKind.LAND_RAVINE_GAP);

        assertTrue(landRavine.landRavineGap());
        assertFalse(landRavine.waterGap());
        assertTrue(RoutePolicy.DETOUR.allowsSpan(landRavine));
    }

    @Test
    void bridgePolicyAllowsRegularBridgeSpans() {
        BridgeSpan regular = new BridgeSpan(3, 30, 63, 40,
                BridgeSpanKind.REGULAR_BRIDGE, Integer.MIN_VALUE, BridgeGapKind.MIXED_GAP);

        assertTrue(RoutePolicy.BRIDGE.allowsSpan(regular));
    }
}
