package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.transport.air.TakeoffAssistConfig;
import com.monpai.sailboatmod.transport.air.TakeoffAssistSession;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeTakeoffAssistPowerRegressionTest {
    @Test void normalZeroAllocatorOutputDoesNotMeanThePoweredEngineHasStopped() {
        // Actual clean-start trace: shaft -256 RPM, ready/active true, capacity 1737 N.
        // A 28.5 Ns assist pulse causes native allocation 26.24 -> 0 -> 24.28 N.
        // The powered capability remains available throughout that control response.
        UUID body=UUID.randomUUID(),task=UUID.randomUUID();
        var session=new TakeoffAssistSession(body,task,"real-profile",new TakeoffAssistConfig(true,12));
        double[] capacity={1737.72,1737.78,1723.88};
        double total=0;
        for(int i=0;i<capacity.length;i++) {
            boolean physicalPower=NativeTakeoffAssistEvidence.powered(true,capacity[i]);
            var frame=new TakeoffAssistSession.Frame(body,task,"real-profile",41.725+i*.025,.025,
                    95,.3+i*.3,21.679,2,TakeoffAssistSession.Contact.GROUNDED,true,true,physicalPower,true,true);
            double impulse=session.evaluate(frame).impulse();
            assertEquals(28.5,impulse,1e-8,"A normal zero native allocation must not end assisted takeoff");
            total+=impulse;
        }
        assertEquals(85.5,total,1e-8);
    }

    @Test void anInactiveOrInvalidPhysicalSourceStillCannotAuthorizeAssistance() {
        assertFalse(NativeTakeoffAssistEvidence.powered(false,1737));
        for(double value:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY})
            assertFalse(NativeTakeoffAssistEvidence.powered(true,value));
    }
}
