package com.monpai.sailboatmod.block.entity;
import com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class RegisteredGroundHoldRecoveryEdgesTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void nativeReleaseUnknownWithoutForeignFlagStillCannotStopOrClaim()throws Exception{
        var f=fixture();var c=(RegisteredVehicleControllerBlockEntity)get(f,"controller");var p=port(f);var body=(UUID)get(f,"body");
        var e=new RegisteredVehicleControllerBlockEntity.GroundHoldEvidence(body,true,true,true,true,false,false,false,false,6,6);
        assertFalse(c.maintainGroundHold(e,p));noWrites(f);
    }
    @Test void fivePositiveContactsCannotStandInForAllSixConfiguredWheels()throws Exception{
        var f=fixture();var c=(RegisteredVehicleControllerBlockEntity)get(f,"controller");var body=(UUID)get(f,"body");
        var e=new RegisteredVehicleControllerBlockEntity.GroundHoldEvidence(body,true,true,true,true,true,false,false,false,6,5);
        assertFalse(c.maintainGroundHold(e,port(f)));noWrites(f);
    }
    @Test void renewingBridgeWrapperDoesNotReleaseTheSameNativeLeaseGeneration()throws Exception{
        var f=fixture();var c=(RegisteredVehicleControllerBlockEntity)get(f,"controller");var delegate=port(f);var body=(UUID)get(f,"body");
        var e=new RegisteredVehicleControllerBlockEntity.GroundHoldEvidence(body,true,true,true,true,true,false,false,false,6,6);
        var wrappers=new RegisteredVehicleControllerBlockEntity.GroundHoldPort(){
            public boolean neutral(int signal){return delegate.neutral(signal);} public void brake15(){delegate.brake15();}
            public FlightControlBridge.GroundNeutralSnapshot requestHold(){var x=delegate.requestHold();return new FlightControlBridge.GroundNeutralSnapshot(x.available(),x.reason(),new Object(),x.acknowledged(),x.generation(),x.physicsTimeSeconds(),x.observations(),x.observedAtNanos());}
            public void releaseHold(Object wrapper){try{delegate.releaseHold(get(get(f,"port"),"handle"));}catch(Exception ex){throw new AssertionError(ex);}}
            public void clearOnlyOurs(){delegate.clearOnlyOurs();}
        };
        assertFalse(c.maintainGroundHold(e,wrappers));nativeStep(f);
        assertFalse(c.maintainGroundHold(e,wrappers));nativeStep(f);
        assertTrue(c.maintainGroundHold(e,wrappers),"new wrappers with the same native generation are renewals, not ownership replacements");
        assertEquals(0,get(get(f,"port"),"releases"));
    }
    private static Object fixture()throws Exception{Method m=RegisteredGroundHoldRecoveryTest.class.getDeclaredMethod("fixture");m.setAccessible(true);return m.invoke(null);}
    private static RegisteredVehicleControllerBlockEntity.GroundHoldPort port(Object f)throws Exception{return(RegisteredVehicleControllerBlockEntity.GroundHoldPort)get(f,"port");}
    private static void nativeStep(Object f)throws Exception{Object p=get(f,"port");Method m=p.getClass().getDeclaredMethod("actualModeledNativePhysics");m.setAccessible(true);m.invoke(p);}
    private static void noWrites(Object f)throws Exception{Object p=get(f,"port");for(String n:new String[]{"neutralCalls","brakeCalls","holdRequests"})assertEquals(0,get(p,n));}
    private static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
}
