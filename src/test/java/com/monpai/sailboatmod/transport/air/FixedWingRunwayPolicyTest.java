package com.monpai.sailboatmod.transport.air;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.*;
class FixedWingRunwayPolicyTest {
    private String failure(double source,double target,double speed,double decel,double diameter)throws Exception {
        Class<?> c=assertDoesNotThrow(()->Class.forName("com.monpai.sailboatmod.transport.air.FixedWingRunwayPolicy"));
        Method m=c.getMethod("failure",double.class,double.class,double.class,double.class,double.class);
        return (String)m.invoke(null,source,target,speed,decel,diameter);
    }
    @Test void nativeSpeedRejectsBoth256And512WhereNominalTenWouldHavePassed()throws Exception {
        assertEquals("departure_runway_too_short",failure(256,2048,45.4251,1,16));
        assertEquals("arrival_runway_too_short",failure(2048,512,45.4251,1,16));
        assertEquals("",failure(2048,2048,45.4251,1,16));
    }
    @Test void eachLegChecksItsOwnRunwayAndSwappingReturnLegChangesFailureSide()throws Exception {
        assertEquals("departure_runway_too_short",failure(100,2048,45,1,16));
        assertEquals("arrival_runway_too_short",failure(2048,100,45,1,16));
    }
    @Test void unknownBrakingAndNonfiniteEnvelopeNeverBecomeDefaultValues()throws Exception {
        assertEquals("ground_calibration_required",failure(2048,2048,45,Double.NaN,16));
        assertEquals("fixed_wing_speed_invalid",failure(2048,2048,Double.NaN,1,16));
    }
}
