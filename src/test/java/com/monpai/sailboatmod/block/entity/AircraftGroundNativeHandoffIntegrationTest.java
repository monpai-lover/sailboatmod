package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.*;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.control.ControllerRegistration;
import com.monpai.sailboatmod.transport.control.ControlChannel;
import com.monpai.sailboatmod.transport.physical.TransportLoadingWindow;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;

/** Real registrar ownership fields and the same entry methods used by its ACQUIRE/START/removal paths. */
class AircraftGroundNativeHandoffIntegrationTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    @Test void issuedNeutralRequestDoesNotYetOwnWheelOutputs()throws Exception{
        var c=controller();var port=new Port(false);
        assertFalse(c.tryAcquireGroundOutputs(true,port),"a request is not native physics acknowledgement");
        assertEquals(false,get(c,"groundOutputsOwned"));assertEquals(1,port.requests);
    }
    @Test void acknowledgedLeaseIsRetainedAlongsideActualGroundOwnership()throws Exception{
        var c=controller();var port=new Port(true);
        assertTrue(c.tryAcquireGroundOutputs(true,port));assertEquals(true,get(c,"groundOutputsOwned"));
        assertSame(port.handle,get(c,"groundNativeLease"),"the active claim must retain its exact native generation handle");
        assertEquals(1,port.requests);
    }
    @Test void foreignOrStalePhysicalEligibilityNeverRequestsANeutralLease()throws Exception{
        var c=controller();var port=new Port(true);
        assertFalse(c.tryAcquireGroundOutputs(false,port));assertEquals(false,get(c,"groundOutputsOwned"));assertEquals(0,port.requests);
    }
    @Test void flightStartWaitsForANormalPhysicsFrameAfterExactlyOneRelease()throws Exception{
        var c=controller();var port=new Port(true);set(c,"groundNativeLease",port.handle);
        assertFalse(c.nativeReadyAfterGroundRelease(true,port),"neutral wheel acknowledgement alone cannot reuse the neutral native frame");
        assertEquals(1,port.releases);assertSame(port.handle,port.releasedHandle);assertNull(get(c,"groundNativeLease"));
        assertFalse(c.nativeReadyAfterGroundRelease(true,port));assertEquals(1,port.releases,"a pending callback may not repeatedly reset the release barrier");
        port.normal=true;assertTrue(c.nativeReadyAfterGroundRelease(true,port));assertEquals(1,port.releases);
    }
    @Test void nonNeutralWheelsCannotReleaseNativeGroundProtection()throws Exception{
        var c=controller();var port=new Port(true);set(c,"groundNativeLease",port.handle);
        assertFalse(c.nativeReadyAfterGroundRelease(false,port));assertEquals(0,port.releases);assertSame(port.handle,get(c,"groundNativeLease"));
    }
    @Test void actualRegistrarRemovalReleasesItsRuntimeLeaseWithoutChangingNativeDefaults()throws Exception{
        var c=controller();var port=new Port(true);set(c,"groundNativeLease",port.handle);set(c,"groundNativePort",port);
        c.setRemoved();assertEquals(1,port.releases);assertSame(port.handle,port.releasedHandle);assertNull(get(c,"groundNativeLease"));
    }
    static final class Port implements RegisteredVehicleControllerBlockEntity.GroundNeutralPort {
        final Object handle=new Object();final boolean acknowledged;boolean normal;int requests,releases;Object releasedHandle;
        Port(boolean acknowledged){this.acknowledged=acknowledged;}
        public GroundNeutralSnapshot request(){requests++;return new GroundNeutralSnapshot(true,"",handle,acknowledged,9,5.0,acknowledged?2:1,System.nanoTime());}
        public GroundNeutralRelease release(Object handle){releases++;releasedHandle=handle;return new GroundNeutralRelease(5,100,200);}
        public boolean normalAfter(GroundNeutralRelease barrier){assertEquals(5,barrier.physicsTimeSeconds());return normal;}
    }
    private static RegisteredVehicleControllerBlockEntity controller()throws Exception{
        Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);
        var c=(RegisteredVehicleControllerBlockEntity)((Unsafe)f.get(null)).allocateInstance(RegisteredVehicleControllerBlockEntity.class);
        set(c,"registration",new ControllerRegistration(UUID.randomUUID(),"owner",LogisticsCarrier.Kind.AIR));
        set(c,"aircraftClass",AircraftClass.FIXED_WING);set(c,"signals",new EnumMap<Direction,Integer>(Direction.class));
        set(c,"ports",new EnumMap<ControlChannel,Direction>(ControlChannel.class));set(c,"loading",new TransportLoadingWindow());return c;
    }
    private static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
}
