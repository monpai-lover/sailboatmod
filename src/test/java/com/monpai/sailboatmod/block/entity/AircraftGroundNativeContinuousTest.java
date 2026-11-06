package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.*;
import com.monpai.sailboatmod.transport.air.ground.AircraftGroundMission;
import java.lang.reflect.*;
import org.junit.jupiter.api.*;

/** Uses the real RVC methods called both before its ground step and before native throttle writes. */
class AircraftGroundNativeContinuousTest {
    @BeforeAll static void bootstrap(){AircraftGroundNativeHandoffIntegrationTest.bootstrap();}
    @Test void everyOwnedGroundCallbackRenewsAndInspectsTheSameGeneration()throws Exception{
        var c=controller();set(c,"groundOutputsOwned",true);var port=new Port();
        for(int tick=0;tick<80;tick++)assertTrue(c.maintainGroundNative(true,port));
        assertEquals(80,port.requests,"ownership must not bypass renewal after the initial ACQUIRE");
        assertEquals(0,port.releases,"wrappers may change while the actual lease generation remains the same");
    }
    @Test void expiredAcknowledgementCannotAuthorizeADriveWriteThroughAnOldOwnedFlag()throws Exception{
        var c=controller();var port=new Port();assertTrue(c.tryAcquireGroundOutputs(true,port));port.ack=false;
        var drive=new AircraftGroundMission.Outputs(0,0,0,0,0);
        assertFalse(c.groundNativeAllowsOutputs(drive,true,port),"the final native-throttle gate must reject an expired ACK even when groundOutputsOwned remains true for safe stopping");
        assertEquals(true,get(c,"groundOutputsOwned"));assertTrue(port.requests>=2);
    }
    @Test void wheelNeutralDuringPostReleaseWaitDoesNotReclaimTheGroundLease()throws Exception{
        var c=controller();var port=new Port();assertTrue(c.tryAcquireGroundOutputs(true,port));
        assertFalse(c.nativeReadyAfterGroundRelease(true,port));int before=port.requests;
        assertTrue(c.groundNativeAllowsOutputs(new AircraftGroundMission.Outputs(15,0,0,0,0),true,port));
        assertEquals(before,port.requests,"FC_STARTING must not renew protection after releasing it for the normal native frame");
    }
    @Test void staleOrForeignEligibilityNeverRenewsAnExistingGroundClaim()throws Exception{
        var c=controller();set(c,"groundOutputsOwned",true);var port=new Port();
        assertFalse(c.maintainGroundNative(false,port));assertEquals(0,port.requests);
    }
    static final class Port implements RegisteredVehicleControllerBlockEntity.GroundNeutralPort {
        final Object handle=new Object();boolean ack=true;int requests,releases;
        public GroundNeutralSnapshot request(){requests++;return new GroundNeutralSnapshot(true,"",handle,ack,12,requests+1,ack?2:1,System.nanoTime());}
        public GroundNeutralRelease release(Object ignored){releases++;return new GroundNeutralRelease(1,1,1);}
        public boolean normalAfter(GroundNeutralRelease barrier){return false;}
    }
    private static RegisteredVehicleControllerBlockEntity controller()throws Exception{
        Method m=AircraftGroundNativeHandoffIntegrationTest.class.getDeclaredMethod("controller");m.setAccessible(true);
        var c=(RegisteredVehicleControllerBlockEntity)m.invoke(null);
        set(c,"groundConfiguration",RegisteredAircraftGroundMission.Configuration.parse("1 32 30 15 0 1").confirmed());return c;
    }
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
}
