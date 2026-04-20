package com.monpai.sailboatmod.road.construction.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeBuilderTest {
    @Test
    void shortSpanBuildSkipsPierRequirement() {
        assertFalse(BridgeBuilder.requiresPiersForLengthForTest(4));
        assertFalse(BridgeBuilder.requiresPiersForLengthForTest(8));
    }

    @Test
    void longSpanBuildRequiresPierBridge() {
        assertTrue(BridgeBuilder.requiresPiersForLengthForTest(9));
        assertTrue(BridgeBuilder.requiresPiersForLengthForTest(16));
    }
}
