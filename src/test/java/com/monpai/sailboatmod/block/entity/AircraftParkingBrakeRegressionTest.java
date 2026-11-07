package com.monpai.sailboatmod.block.entity;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.physical.TransportLoadingWindow;
import com.monpai.sailboatmod.transport.control.ControllerRegistration;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.*;
import sun.misc.Unsafe;
import java.lang.reflect.*;
import java.util.*;

/** Real registrar output: generic AIR cleanup may not silently drop a separately owned parked brake. */
class AircraftParkingBrakeRegressionTest {
 @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
 @Test void normalAircraftHoldDoesNotReleaseAnExistingParkingBrake()throws Exception{
  var c=controller();signals(c).put(Direction.UP,15);
  hold(c,false);
  assertEquals(15,c.signal(Direction.DOWN),"Arrival/STOP generic AIR cleanup must leave BRAKE15 for the explicit parking lifecycle");
 }
 @Test void manualAircraftHoldYieldsOnlyOurSignals()throws Exception{
  var c=controller();signals(c).put(Direction.UP,15);hold(c,true);assertEquals(0,c.signal(Direction.DOWN));
 }
 @Test void genericAircraftHoldDoesNotCreateANewBrakeClaim()throws Exception{
  var c=controller();hold(c,false);assertTrue(signals(c).isEmpty());
 }
 @Test void chunkRemovalDropsRuntimeParkingOwnershipAndOurBrakeSignal()throws Exception{
  var c=controller();var parking=new RegisteredAircraftParkingBrake();parking.adopt(UUID.randomUUID());set(c,"parkingBrake",parking);signals(c).put(Direction.UP,15);
  c.setRemoved();assertFalse(parking.held(),"Unloaded actuators cannot retain runtime parking authority");assertEquals(0,c.signal(Direction.DOWN));
 }
 @Test void nonFixedWingAircraftKeepsItsOriginalCleanupBehavior()throws Exception{
  var c=controller();set(c,"aircraftClass",AircraftClass.AIRSHIP);signals(c).put(Direction.UP,15);hold(c,false);assertEquals(0,c.signal(Direction.DOWN));
 }
 @SuppressWarnings("unchecked")private static Map<Direction,Integer> signals(Object c)throws Exception{return(Map<Direction,Integer>)get(c,"signals");}
 private static RegisteredVehicleControllerBlockEntity controller()throws Exception{
  Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);var c=(RegisteredVehicleControllerBlockEntity)((Unsafe)f.get(null)).allocateInstance(RegisteredVehicleControllerBlockEntity.class);
  set(c,"registration",new ControllerRegistration(UUID.randomUUID(),"owner",LogisticsCarrier.Kind.AIR));set(c,"signals",new EnumMap<Direction,Integer>(Direction.class));set(c,"aircraftClass",AircraftClass.FIXED_WING);set(c,"loading",new TransportLoadingWindow());return c;
 }
 private static void hold(Object c,boolean manual)throws Exception{Method m=c.getClass().getDeclaredMethod("holdNative",boolean.class);m.setAccessible(true);m.invoke(c,manual);}
 private static Object get(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 private static void set(Object o,String n,Object v)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,v);}
}
