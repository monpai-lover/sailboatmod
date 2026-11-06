package com.monpai.sailboatmod.block.entity;
import com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.*;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import static org.junit.jupiter.api.Assertions.*;
/** Real RVC barrier/ACQUIRE/normal-after entry methods; modeled callback observations are not physics certification. */
class AircraftRunwayValidationNativeRearmTest {
    @BeforeAll static void bootstrap(){AircraftGroundNativeHandoffIntegrationTest.bootstrap();}
    @Test void waitDiscardsOldNormalFrameAndRequiresNewDriveAckBeforeANewRelease()throws Exception{
        var c=controller();var p=new Port();set(c,"groundNativeRelease",p.oldBarrier);set(c,"groundOutputsOwned",true);
        assertFalse(c.rearmGroundAfterRunwayWait(true,p));assertNull(get(c,"groundNativeRelease"),"old normal-after witness cannot survive pending validation");assertSame(p.newHandle,get(c,"groundNativeLease"));assertEquals(1,p.requests,"reclaim native neutral immediately, without a full unprotected tick");assertEquals(0,p.releases);
        p.physics();assertFalse(c.rearmGroundAfterRunwayWait(true,p));p.physics();assertTrue(c.rearmGroundAfterRunwayWait(true,p));assertSame(p.newHandle,get(c,"groundNativeLease"));
        assertFalse(c.nativeReadyAfterGroundRelease(true,p),"a new release must await a new normal frame even though the old frame was ready");assertEquals(1,p.releases);assertSame(p.newBarrier,get(c,"groundNativeRelease"));assertFalse(c.nativeReadyAfterGroundRelease(true,p));p.newNormal=true;assertTrue(c.nativeReadyAfterGroundRelease(true,p));
    }
    @Test void waitingBeforeReleaseKeepsItsExistingNativeGeneration()throws Exception{
        var c=controller();var p=new Port();p.observations=2;set(c,"groundOutputsOwned",true);set(c,"groundNativeLease",p.newHandle);set(c,"groundNativeGeneration",31L);
        assertTrue(c.rearmGroundAfterRunwayWait(true,p));assertSame(p.newHandle,get(c,"groundNativeLease"));assertEquals(0,p.releases,"do not open a ticket-like release/reclaim gap");
    }
    @Test void startedOrForeignAuthorityCannotReclaimGroundDuringScanWait()throws Exception{
        var c=controller();var p=new Port();set(c,"groundStartRequested",true);set(c,"groundNativeRelease",p.oldBarrier);assertFalse(c.rearmGroundAfterRunwayWait(true,p));assertEquals(0,p.requests);assertSame(p.oldBarrier,get(c,"groundNativeRelease"));
        c=controller();p=new Port();assertFalse(c.rearmGroundAfterRunwayWait(false,p));assertEquals(0,p.requests);
    }
    static final class Port implements RegisteredVehicleControllerBlockEntity.GroundNeutralPort {
        final Object newHandle=new Object();final GroundNeutralRelease oldBarrier=new GroundNeutralRelease(10,10,10),newBarrier=new GroundNeutralRelease(20,20,20);int requests,releases,observations;boolean newNormal;
        public GroundNeutralSnapshot request(){requests++;return new GroundNeutralSnapshot(true,"",newHandle,observations>=2,31,20+observations*.025,observations,System.nanoTime());}
        public GroundNeutralRelease release(Object h){assertSame(newHandle,h);releases++;return newBarrier;}
        public boolean normalAfter(GroundNeutralRelease b){return b==oldBarrier||b==newBarrier&&newNormal;}
        void physics(){observations++;}
    }
    private static RegisteredVehicleControllerBlockEntity controller()throws Exception{Method m=AircraftGroundNativeHandoffIntegrationTest.class.getDeclaredMethod("controller");m.setAccessible(true);return(RegisteredVehicleControllerBlockEntity)m.invoke(null);}
    private static Object get(Object c,String n)throws Exception{Field f=c.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(c);}
    private static void set(Object c,String n,Object v)throws Exception{Field f=c.getClass().getDeclaredField(n);f.setAccessible(true);f.set(c,v);}
}
