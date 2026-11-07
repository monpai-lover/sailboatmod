package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightControlRecoverySupportTest {
    private static final String TERMINAL="e906e63530dc02322269e96e9c8e23d9ad9de23aaf579cfaa20eb7cf60fc7c0a";
    private static final String COMPUTER="44487adde64fd7fa88b091fad99be96cfc0e26ec6d179b24334e3d186d9f5cd1";
    @Test void onlyTheInspectedVersionBytesAndNativeCallShapeEnableRecovery() {
        assertTrue(FlightControlRecoverySupport.supports("0.8.3",TERMINAL,COMPUTER,1,2));
        assertFalse(FlightControlRecoverySupport.supports("0.8.4",TERMINAL,COMPUTER,1,2));
        assertFalse(FlightControlRecoverySupport.supports("0.8.3","unknown",COMPUTER,1,2));
        assertFalse(FlightControlRecoverySupport.supports("0.8.3",TERMINAL,"unknown",1,2));
        assertFalse(FlightControlRecoverySupport.supports("0.8.3",TERMINAL,COMPUTER,2,2));
        assertFalse(FlightControlRecoverySupport.supports("0.8.3",TERMINAL,COMPUTER,1,1));
    }
}
