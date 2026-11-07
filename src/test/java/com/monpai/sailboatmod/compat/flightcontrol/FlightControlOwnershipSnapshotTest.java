package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FlightControlOwnershipSnapshotTest {
    private static final UUID BODY=UUID.randomUUID(), TASK=UUID.randomUUID();
    private static Object snapshot(boolean available,boolean owned,boolean commands,boolean foreign) throws Exception {
        Class<?> type=assertDoesNotThrow(()->Class.forName("com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge$ControlOwnership"));
        return type.getConstructor(boolean.class,String.class,UUID.class,UUID.class,String.class,boolean.class,boolean.class,boolean.class)
                .newInstance(available,"",BODY,TASK,"fingerprint",owned,commands,foreign);
    }
    private static boolean flight(Object proof,UUID body,UUID task,String fingerprint) throws Exception {
        return (boolean)proof.getClass().getMethod("provesFlight",UUID.class,UUID.class,String.class).invoke(proof,body,task,fingerprint);
    }
    private static boolean released(Object proof,UUID body) throws Exception {
        return (boolean)proof.getClass().getMethod("provesReleased",UUID.class).invoke(proof,body);
    }
    @Test void flightHandoffRequiresExactNativeLeaseIdentityAndFingerprint() throws Exception {
        var p=snapshot(true,true,true,false);
        assertTrue(flight(p,BODY,TASK,"fingerprint"));
        assertFalse(flight(p,UUID.randomUUID(),TASK,"fingerprint"));
        assertFalse(flight(p,BODY,UUID.randomUUID(),"fingerprint"));
        assertFalse(flight(p,BODY,TASK,"changed"));
        assertFalse(flight(snapshot(true,false,true,false),BODY,TASK,"fingerprint"));
        assertFalse(flight(snapshot(true,true,true,true),BODY,TASK,"fingerprint"));
    }
    @Test void terminalCompletionWithoutEmptyBrokerFrameDoesNotProveRelease() throws Exception {
        assertFalse(released(snapshot(true,false,true,false),BODY));
        assertFalse(released(snapshot(true,false,false,true),BODY));
        assertFalse(released(snapshot(true,true,false,false),BODY));
        assertTrue(released(snapshot(true,false,false,false),BODY));
    }
    @Test void unavailableOrOtherBodyNeverProvesEitherHandoff() throws Exception {
        assertFalse(released(snapshot(false,false,false,false),BODY));
        assertFalse(released(snapshot(true,false,false,false),UUID.randomUUID()));
        assertFalse(flight(snapshot(false,true,true,false),BODY,TASK,"fingerprint"));
    }
}
