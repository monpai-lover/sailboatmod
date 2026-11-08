package com.monpai.sailboatmod.transport.rail;

import static com.monpai.sailboatmod.transport.rail.RailFreightEta.activeLegTicks;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RailFreightEtaTest {
    @Test
    void aSlowDepartureUsesTheAccelerationRampInsteadOfExtrapolatingItsFirstTick() {
        assertEquals(70, activeLegTicks(100, 0, 2, .1).orElseThrow());
        assertEquals(70, activeLegTicks(100, .00001, 2, .1).orElseThrow());
        assertEquals(63, activeLegTicks(100, 1, 2, .1).orElseThrow());
        assertEquals(60, activeLegTicks(100, 2, 2, .1).orElseThrow());
    }

    @Test
    void shortLegsReachALowerPeakAndNearArrivalCanAlreadyBeBraking() {
        assertEquals(20, activeLegTicks(10, 0, 2, .1).orElseThrow());
        assertEquals(20, activeLegTicks(20, 2, 2, .1).orElseThrow());
        assertEquals(1, activeLegTicks(1, 2, 2, .1).orElseThrow());
    }

    @Test
    void unusableMotionInputsAndOutOfRangeEstimatesStayUnknown() {
        assertTrue(activeLegTicks(-1, 0, 2, .1).isEmpty());
        assertTrue(activeLegTicks(100, Double.NaN, 2, .1).isEmpty());
        assertTrue(activeLegTicks(100, 0, 0, .1).isEmpty());
        assertTrue(activeLegTicks(100, 0, 2, 0).isEmpty());
        assertTrue(activeLegTicks(Double.MAX_VALUE, 0, 2, .1).isEmpty());
    }
}
