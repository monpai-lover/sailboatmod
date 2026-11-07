package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightControlPowerSnapshotTest {
    private static boolean ready(boolean known,int linked,int powered,double thrust) throws Exception {
        Class<?> type=assertDoesNotThrow(()->Class.forName("com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge$PowerSnapshot"));
        Object p=type.getConstructor(boolean.class,String.class,int.class,int.class,double.class)
                .newInstance(known,"",linked,powered,thrust);
        return (boolean)type.getMethod("ready").invoke(p);
    }
    @Test void aLinkedButDisconnectedPropellerCannotStartFlight() throws Exception {
        assertFalse(ready(true,1,0,0));
        assertFalse(ready(true,0,0,0));
        assertTrue(ready(true,1,1,10));
    }
    @Test void unavailableOrNonFiniteCapacityDoesNotProvePoweredPropulsion() throws Exception {
        assertFalse(ready(false,1,1,10));
        assertFalse(ready(true,1,1,Double.NaN));
        assertFalse(ready(true,1,1,Double.POSITIVE_INFINITY));
        assertFalse(ready(true,1,1,-1));
    }
    @Test void capacityCountsMustBeCoherentAndBounded() throws Exception {
        assertFalse(ready(true,0,1,10));
        assertFalse(ready(true,-1,1,10));
        assertFalse(ready(true,129,1,10));
    }
}
