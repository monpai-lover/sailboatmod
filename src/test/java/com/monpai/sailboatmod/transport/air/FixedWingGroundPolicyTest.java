package com.monpai.sailboatmod.transport.air;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;

/** Boundary tests for the registrar's stand dispatch and delivery gates. */
class FixedWingGroundPolicyTest {
    private static Class<?> policy() {
        return assertDoesNotThrow(() -> Class.forName("com.monpai.sailboatmod.transport.air.FixedWingGroundPolicy"));
    }
    private static String mode(boolean fixedWing, boolean sourceLayout, boolean destinationLayout) throws Exception {
        return policy().getMethod("mode", boolean.class, boolean.class, boolean.class)
                .invoke(null, fixedWing, sourceLayout, destinationLayout).toString();
    }
    private static boolean delivery(String phase, boolean recovered, boolean assigned, boolean nativeGround,
                                    boolean bindingsMatch, double speed, double angular, int stable) throws Exception {
        Method m = policy().getMethod("standDeliveryReady", String.class, boolean.class, boolean.class,
                boolean.class, boolean.class, double.class, double.class, int.class);
        return (boolean) m.invoke(null, phase, recovered, assigned, nativeGround, bindingsMatch, speed, angular, stable);
    }
    @Test void bothAirportsMustHaveGroundLayoutBeforeStandDispatch() throws Exception {
        assertEquals("STAND", mode(true, true, true));
        assertEquals("INCOMPLETE", mode(true, true, false));
        assertEquals("INCOMPLETE", mode(true, false, true));
    }
    @Test void legacyAndOtherAirframesKeepTheirExistingProcedure() throws Exception {
        assertEquals("LEGACY", mode(true, false, false));
        assertEquals("LEGACY", mode(false, true, true));
    }
    @Test void runwayStopAndFlightCompletionCannotSettleStandBoundCargo() throws Exception {
        for (String phase : new String[]{"FC_ACTIVE", "WAIT_FC_RELEASE", "TAXI_IN", "STAND_STABILIZING", "NEEDS_REVIEW", ""})
            assertFalse(delivery(phase, false, true, true, true, 0, 0, 40), phase);
    }
    @Test void restoredCompleteStateRequiresNewPhysicalProof() throws Exception {
        assertFalse(delivery("COMPLETE", true, true, true, true, 0, 0, 40));
        assertTrue(delivery("COMPLETE", false, true, true, true, 0, 0, 40));
    }
    @Test void wrongStandContactOrRevisionCannotDeliver() throws Exception {
        assertFalse(delivery("COMPLETE", false, false, true, true, 0, 0, 40));
        assertFalse(delivery("COMPLETE", false, true, false, true, 0, 0, 40));
        assertFalse(delivery("COMPLETE", false, true, true, false, 0, 0, 40));
    }
    @Test void movingSpinningAndUnstableObservationsCannotDeliver() throws Exception {
        assertFalse(delivery("COMPLETE", false, true, true, true, .251, 0, 40));
        assertFalse(delivery("COMPLETE", false, true, true, true, 0, Math.toRadians(2.1), 40));
        assertFalse(delivery("COMPLETE", false, true, true, true, 0, 0, 39));
        assertFalse(delivery("COMPLETE", false, true, true, true, Double.NaN, 0, 40));
        assertFalse(delivery("COMPLETE", false, true, true, true, 0, Double.POSITIVE_INFINITY, 40));
        assertFalse(delivery("COMPLETE", false, true, true, true, -1, 0, 40));
    }
}
