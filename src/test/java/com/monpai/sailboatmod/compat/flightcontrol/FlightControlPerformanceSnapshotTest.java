package com.monpai.sailboatmod.compat.flightcontrol;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FlightControlPerformanceSnapshotTest {
    static final UUID BODY=UUID.randomUUID();
    private Object snapshot(boolean available,String status,long estimateTick,long sampledTick,double v1,double vr,double pitch,double forward,double envelope)throws Exception {
        Class<?> c=assertDoesNotThrow(()->Class.forName("com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge$FixedWingPerformanceSnapshot"));
        return c.getConstructor(boolean.class,String.class,UUID.class,long.class,long.class,String.class,double.class,double.class,double.class,double.class,double.class)
                .newInstance(available,"native_unavailable",BODY,estimateTick,sampledTick,status,v1,vr,pitch,forward,envelope);
    }
    private String failure(Object s,UUID body,long now)throws Exception{return (String)s.getClass().getMethod("failure",UUID.class,long.class).invoke(s,body,now);}
    private double envelope(Object s)throws Exception{return (double)s.getClass().getMethod("conservativeSpeed").invoke(s);}
    @Test void nativeReferenceAgeBodyAndReadyAreRequiredWithoutNominalFallback()throws Exception {
        assertEquals("",failure(snapshot(true,"READY",100,199,36.052,43.262,0,50,50),BODY,199));
        assertFalse(failure(snapshot(true,"READY",100,200,36.052,43.262,0,50,50),BODY,200).isEmpty());
        assertFalse(failure(snapshot(true,"READY",201,200,36.052,43.262,0,50,50),BODY,200).isEmpty());
        assertFalse(failure(snapshot(true,"NO_TRIM",100,100,36,43,0,50,50),BODY,100).isEmpty());
        assertFalse(failure(snapshot(false,"READY",100,100,36,43,0,50,50),BODY,100).isEmpty());
        assertFalse(failure(snapshot(true,"READY",100,100,36,43,0,50,50),UUID.randomUUID(),100).isEmpty());
    }
    @Test void allNativeReferenceNumbersMustBeFiniteAndOrdered()throws Exception {
        assertFalse(failure(snapshot(true,"READY",100,100,Double.NaN,43,0,50,50),BODY,100).isEmpty());
        assertFalse(failure(snapshot(true,"READY",100,100,44,43,0,50,50),BODY,100).isEmpty());
        assertFalse(failure(snapshot(true,"READY",100,100,36,43,Double.NaN,50,50),BODY,100).isEmpty());
        assertFalse(failure(snapshot(true,"READY",100,100,36,43,0,50,Double.POSITIVE_INFINITY),BODY,100).isEmpty());
    }
    @Test void forwardFiveCannotBeHiddenByOtherAxisMagnitude()throws Exception {
        assertEquals("fixed_wing_forward_limit_below_vr",failure(snapshot(true,"READY",100,100,36.052,43.262,0,5,100),BODY,100));
    }
    @Test void nativeLandingFloorAndAllDirectionEnvelopeReplaceRegistrarTen()throws Exception {
        assertEquals(45.4251,envelope(snapshot(true,"READY",100,100,36.052,43.262,0,44,44)),1e-8);
        assertEquals(90,envelope(snapshot(true,"READY",100,100,36.052,43.262,5,50,90)),1e-8);
    }
    public static class Facing { public Direction direction(){return Direction.WEST;} }
    public static class NativeLimits {
        public double maxVxPos=90,maxVxNeg=45,maxVyPos=3,maxVyNeg=4,maxVzPos=5,maxVzNeg=12;
        public Facing getBodyForwardFacing(){return new Facing();}
    }
    @Test void actualSignedNativeFieldReaderUsesWestNegativeLimitAndBothDirections()throws Exception {
        var m=assertDoesNotThrow(()->FlightControlReflection.class.getDeclaredMethod("velocityBounds",Object.class));m.setAccessible(true);
        double[] v=(double[])m.invoke(null,new NativeLimits());
        assertArrayEquals(new double[]{90,4,12,45},v,1e-9);
    }
    public static class NativeForwardSource {
        public Object facing=Direction.WEST;
        public java.util.List<net.minecraft.core.BlockPos> targets=java.util.List.of(new net.minecraft.core.BlockPos(1,2,3));
        public Object getForwardFacing(){return facing;}
        public java.util.List<net.minecraft.core.BlockPos> getLinkedTargetWorldPositions(){return targets;}
    }
    private java.lang.reflect.Method forwardGuard() {
        var method=assertDoesNotThrow(()->FlightControlReflection.class.getDeclaredMethod("requireForwardReference",Object.class,Object.class,net.minecraft.core.BlockPos.class,boolean.class));
        method.setAccessible(true);return method;
    }
    @Test void forwardSourceMustBelongToTheSameNativeBodyEvenWithMatchingFacingAndLink()throws Exception {
        var method=forwardGuard();var source=new NativeForwardSource();var pos=new net.minecraft.core.BlockPos(1,2,3);
        assertDoesNotThrow(()->method.invoke(null,source,Direction.WEST,pos,true));
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,source,Direction.WEST,pos,false));
        assertEquals("flight_forward_source_binding_lost",failure.getCause().getMessage());
    }
    @Test void forwardSourceFacingAndReciprocalTargetMustBothMatchWithoutFallback()throws Exception {
        var method=forwardGuard();var source=new NativeForwardSource();var pos=new net.minecraft.core.BlockPos(1,2,3);
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,source,Direction.EAST,pos,true));
        source.targets=java.util.List.of();
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,source,Direction.WEST,pos,true));
        source.targets=java.util.List.of(pos);source.facing=null;
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->method.invoke(null,source,null,pos,true));
    }
}
