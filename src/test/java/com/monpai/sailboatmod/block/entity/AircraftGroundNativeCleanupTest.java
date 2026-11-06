package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutral;
import java.lang.reflect.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.*;

/** Cleanup cannot depend on a still-configured navigation offset or a loaded world. */
class AircraftGroundNativeCleanupTest {
    @BeforeAll static void bootstrap(){AircraftGroundNativeHandoffIntegrationTest.bootstrap();}
    @Test void missingTerminalOffsetStillReleasesTheActualOwnedRegistryGeneration()throws Exception{
        Method fixture=AircraftGroundNativeHandoffIntegrationTest.class.getDeclaredMethod("controller");fixture.setAccessible(true);
        var c=(RegisteredVehicleControllerBlockEntity)fixture.invoke(null);
        Field pos=BlockEntity.class.getDeclaredField("worldPosition");pos.setAccessible(true);pos.set(c,BlockPos.ZERO);
        Method factory=c.getClass().getDeclaredMethod("groundNeutralPort",ServerLevel.class);factory.setAccessible(true);
        var port=(RegisteredVehicleControllerBlockEntity.GroundNeutralPort)factory.invoke(c,new Object[]{null});
        Object computer=new Object();
        var context=new NativeGroundNeutral.Context(c,c.getTransportUuid(),new Object(),new Object(),new Object(),"any-profile",1,1);
        var old=NativeGroundNeutral.request(computer,context,System.nanoTime(),true);assertNotNull(old);
        NativeGroundNeutral.Lease fresh=null;
        try{
            Class<?> wrapper=Class.forName("com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge$GroundNeutralHandle");
            Constructor<?> constructor=wrapper.getDeclaredConstructor(Object.class,NativeGroundNeutral.Context.class,NativeGroundNeutral.Lease.class);constructor.setAccessible(true);
            set(c,"groundNativePort",port);set(c,"groundNativeLease",constructor.newInstance(computer,context,old));
            c.clearGroundNativeLease();
            fresh=NativeGroundNeutral.request(computer,context,System.nanoTime(),true);assertNotNull(fresh);
            assertTrue(fresh.generation()>old.generation(),"forgetting the RVC handle after a null-offset exception must not leave its old registry generation alive");
        }finally{NativeGroundNeutral.release(old);NativeGroundNeutral.release(fresh);}
    }
    private static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
}
