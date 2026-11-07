package com.monpai.sailboatmod.block.entity;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual registrar HOLD entry; modeled native processing is not a rigid-body stability certificate. */
class RegisteredGroundHeldParkingMaintenanceTest {
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void verifiedSameBodyParkingRetainsNeutralOutsideTheOriginalCargoStand()throws Exception{
        // Both normal stopped-ground adoption and outbound-arrival/return preflight may be outside sourceTerminal's stand.
        for(boolean returnPreflight:new boolean[]{false,true}){
            Object f=fixture();var c=controller(f);UUID body=(UUID)get(f,"body");
            set(c,"groundMission",null);set(c,"active",returnPreflight);set(c,"paused",false);
            set(c,"sourceTerminal",new BlockPos(1,70,2));set(c,"returnOrigin",new BlockPos(1001,70,2));
            set(c,"returnFlightPending",returnPreflight);set(c,"returnTrip",returnPreflight);
            var parking=new RegisteredAircraftParkingBrake();parking.adopt(body);set(c,"parkingBrake",parking);
            assertFalse(c.maintainGroundHold(evidence(body,false),port(f)),"a submitted command is not yet ACK");
            assertEquals(15,c.signal(Direction.DOWN));assertEquals(1,get(get(f,"port"),"holdRequests"));
            nativeStep(f);nativeStep(f);
            assertTrue(c.maintainGroundHold(evidence(body,false),port(f)),"maintain an already verified runtime hold without acquiring a new stand");
            assertEquals(0.0,get(get(f,"port"),"nativePropulsion"));assertFalse((boolean)get(c,"groundOutputsOwned"));
            assertNull(get(c,"groundMission"));
        }
    }

    @Test void beingOutsideAStandWithNoExistingHoldCannotAcquireParkingThroughHoldMaintenance()throws Exception{
        Object f=fixture();var c=controller(f);set(c,"groundMission",null);set(c,"active",false);set(c,"paused",false);
        assertFalse(c.maintainGroundHold(evidence((UUID)get(f,"body"),false),port(f)));noWrites(f);
    }

    @Test void anotherPhysicalBodyExistingHoldCannotAuthorizeThisBodyEvenInsideAStand()throws Exception{
        Object f=fixture();var c=controller(f);set(c,"groundMission",null);set(c,"active",false);set(c,"paused",false);
        var parking=new RegisteredAircraftParkingBrake();parking.adopt(UUID.randomUUID());set(c,"parkingBrake",parking);
        assertFalse(c.maintainGroundHold(evidence((UUID)get(f,"body"),true),port(f)));noWrites(f);
    }

    private static RegisteredVehicleControllerBlockEntity.GroundHoldEvidence evidence(UUID body,boolean wholeStand){
        return new RegisteredVehicleControllerBlockEntity.GroundHoldEvidence(body,true,true,true,true,true,false,false,wholeStand,6,6);
    }
    private static Object fixture()throws Exception{Method m=RegisteredGroundHoldRecoveryTest.class.getDeclaredMethod("fixture");m.setAccessible(true);return m.invoke(null);}
    private static RegisteredVehicleControllerBlockEntity controller(Object f)throws Exception{return(RegisteredVehicleControllerBlockEntity)get(f,"controller");}
    private static RegisteredVehicleControllerBlockEntity.GroundHoldPort port(Object f)throws Exception{return(RegisteredVehicleControllerBlockEntity.GroundHoldPort)get(f,"port");}
    private static void nativeStep(Object f)throws Exception{Object p=get(f,"port");Method m=p.getClass().getDeclaredMethod("actualModeledNativePhysics");m.setAccessible(true);m.invoke(p);}
    private static void noWrites(Object f)throws Exception{Object p=get(f,"port");for(String n:new String[]{"neutralCalls","brakeCalls","holdRequests"})assertEquals(0,get(p,n));}
    private static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
}
