package com.monpai.sailboatmod.transport.air.rail;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailAircraftPathTest {
    static final String PREFIX="com.monpai.sailboatmod.transport.air.rail.";
    static Class<?> type(String name) {
        try { return Class.forName(PREFIX+name); }
        catch (ClassNotFoundException missing) { return fail("Required rail domain implementation is absent: "+name,missing); }
    }
    static Object invoke(Object target,String method,Object...args) {
        Class<?> owner=target instanceof Class<?> c?c:target.getClass();
        try {
            Method found=Arrays.stream(owner.getMethods()).filter(m->m.getName().equals(method)&&m.getParameterCount()==args.length).findFirst().orElseThrow();
            return found.invoke(target instanceof Class<?>?null:target,args);
        } catch(InvocationTargetException wrapped) {
            if(wrapped.getCause() instanceof RuntimeException failure)throw failure;
            if(wrapped.getCause() instanceof Error failure)throw failure;
            return fail("Rail domain call failed",wrapped.getCause());
        } catch(ReflectiveOperationException|NoSuchElementException missing) { return fail("Required rail API is absent: "+method,missing); }
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    static Object leg(String phase,List<Vec3> points,double speed,double radius,boolean bank) {
        try {
            Class<?> p=type("RailAircraftPath$Phase");
            return type("RailAircraftPath$Leg").getConstructor(p,List.class,double.class,double.class,boolean.class)
                    .newInstance(Enum.valueOf((Class)p,phase),points,speed,radius,bank);
        } catch(InvocationTargetException wrapped) {
            if(wrapped.getCause() instanceof RuntimeException failure)throw failure;
            return fail(wrapped.getCause());
        } catch(ReflectiveOperationException missing) { return fail(missing); }
    }
    static Object compile(double yaw,Object...legs) { return invoke(type("RailAircraftPath"),"compile",List.of(legs),yaw); }
    static Object sample(Object path,double s) { return invoke(path,"sample",s); }
    static double number(Object target,String name) { return ((Number)invoke(target,name)).doubleValue(); }
    static Vec3 position(Object path,double s) { return (Vec3)invoke(sample(path,s),"position"); }
    static Object straight(double length,double speed) {
        return compile(-90,leg("TAXI_OUT",List.of(new Vec3(0,64,0),new Vec3(length,64,0)),speed,0,false));
    }

    @Test void straightDistanceIsArcLengthAndFiniteOutOfRangeSamplesAreClamped() {
        Object path=straight(100,10);
        assertEquals(100,number(path,"length"),1e-9);
        assertEquals(new Vec3(25,64,0),position(path,25));
        assertEquals(new Vec3(0,64,0),position(path,-1));
        assertEquals(new Vec3(100,64,0),position(path,200));
        assertThrows(IllegalArgumentException.class,()->sample(path,Double.NaN));
        assertThrows(IllegalArgumentException.class,()->sample(path,Double.POSITIVE_INFINITY));
        for(int i=1;i<20;i++)assertEquals(.5,position(path,i*.5).distanceTo(position(path,(i-1)*.5)),1e-10);
    }
    @Test void roundedGroundCornerStaysInsideTheAuthoredCorridorAndNeverBanks() {
        Object path=compile(0,leg("TAXI_IN",List.of(new Vec3(0,64,0),new Vec3(20,64,0),new Vec3(20,64,20)),2,3,false));
        assertTrue(number(path,"length")<40);
        for(double s=0;s<=number(path,"length");s+=.05) {
            Object state=sample(path,s);Vec3 p=(Vec3)invoke(state,"position");
            double first=Math.hypot(Math.max(0,Math.max(-p.x,p.x-20)),p.z);
            double second=Math.hypot(p.x-20,Math.max(0,Math.max(-p.z,p.z-20)));
            assertTrue(Math.min(first,second)<=3+1e-8,"Rounded center escaped the available corridor");
            assertEquals(0,number(state,"bankRadians"));assertEquals(0,number(state,"pitchRadians"));
        }
        assertEquals(new Vec3(20,64,20),position(path,number(path,"length")));
    }
    @Test void holdingBoundaryIsExactAndTheNextStepCanContinueBeyondItsMarker() {
        Vec3 start=new Vec3(0,64,0),hold=new Vec3(10,64,0),end=new Vec3(30,64,0);
        Object path=compile(-90,leg("TAXI_OUT",List.of(start,hold),2,2,false),
                leg("HOLD_SHORT",List.of(hold),0,0,false),leg("LINE_UP",List.of(hold,end),2,0,false));
        assertEquals(10,((Number)invoke(path,"nextBoundary",9.9)).doubleValue());
        assertEquals("HOLD_SHORT",invoke(sample(path,10),"phase").toString());
        assertEquals(30,((Number)invoke(path,"nextBoundary",10.0)).doubleValue());
        assertTrue(((Number)invoke(path,"speedForAdvance",10.0)).doubleValue()>0);
        assertEquals(new Vec3(1,0,0),(Vec3)invoke(sample(path,20),"tangent"));
    }
    @Test void coordinatedBankHasTheInwardSignAndGrowsWithSpeedButStaysBounded() {
        List<Vec3> points=List.of(new Vec3(-200,100,0),new Vec3(0,100,0),new Vec3(0,100,200));
        Object slow=compile(0,leg("CRUISE",points,12,60,true));
        Object fast=compile(0,leg("CRUISE",points,30,60,true));
        double low=0,high=0,previous=0;
        for(double s=0;s<=number(fast,"length");s+=.25) {
            double bank=number(sample(fast,s),"bankRadians");
            assertTrue(bank>=-1e-10,"East-to-south turn must lean toward +Z");
            assertTrue(Math.abs(bank)<=Math.toRadians(30)+1e-10);
            assertTrue(Math.abs(bank-previous)<Math.toRadians(3),"Bank must enter and leave gradually");previous=bank;
            high=Math.max(high,bank);low=Math.max(low,number(sample(slow,s),"bankRadians"));
        }
        assertTrue(high>Math.toRadians(5));assertTrue(high>low);
        assertEquals(0,number(sample(fast,0),"bankRadians"),1e-10);
        assertEquals(0,number(sample(fast,number(fast,"length")),"bankRadians"),1e-10);
    }
    @Test void largerTurnRadiusReducesTheCoordinatedBank() {
        List<Vec3> p=List.of(new Vec3(-200,100,0),new Vec3(0,100,0),new Vec3(0,100,200));
        Object small=compile(0,leg("CRUISE",p,15,20,true)),large=compile(0,leg("CRUISE",p,15,80,true));
        double a=0,b=0;for(double s=0;s<number(small,"length");s+=.5)a=Math.max(a,number(sample(small,s),"bankRadians"));
        for(double s=0;s<number(large,"length");s+=.5)b=Math.max(b,number(sample(large,s),"bankRadians"));
        assertTrue(a>b);
    }
    @Test void climbAndApproachPitchAreBoundedAndTheFinalStandHeadingIsExact() {
        Object path=compile(-90,leg("CLIMB",List.of(new Vec3(0,64,0),new Vec3(100,100,0)),20,30,true),
                leg("APPROACH",List.of(new Vec3(100,100,0),new Vec3(200,64,0)),20,30,true),
                leg("LANDING_ROLL",List.of(new Vec3(200,64,0),new Vec3(250,64,0)),5,0,false),
                leg("STAND_STABILIZING",List.of(new Vec3(250,64,0)),0,0,false));
        boolean climbed=false,descended=false;
        for(double s=0;s<number(path,"length");s+=.2) {
            double pitch=number(sample(path,s),"pitchRadians");
            assertTrue(Math.abs(pitch)<=Math.toRadians(30)+1e-9);climbed|=pitch>.01;descended|=pitch<-.01;
        }
        assertTrue(climbed);assertTrue(descended);
        Object end=sample(path,number(path,"length"));
        assertEquals(new Vec3(250,64,0),invoke(end,"position"));
        assertEquals(0,((Vec3)invoke(end,"tangent")).distanceTo(new Vec3(1,0,0)),1e-9);
        assertEquals(0,number(end,"pitchRadians"));assertEquals(0,number(end,"bankRadians"));
    }
    @Test void malformedDisconnectedAndReversingPathsAreRejected() {
        assertThrows(IllegalArgumentException.class,()->compile(-90));
        assertThrows(IllegalArgumentException.class,()->compile(-90,leg("CRUISE",List.of(Vec3.ZERO,new Vec3(Double.NaN,0,0)),20,5,true)));
        assertThrows(IllegalArgumentException.class,()->compile(-90,leg("CRUISE",List.of(Vec3.ZERO,Vec3.ZERO),20,5,true)));
        assertThrows(IllegalArgumentException.class,()->compile(-90,leg("CRUISE",List.of(Vec3.ZERO,new Vec3(10,0,0),Vec3.ZERO),20,5,true)));
        assertThrows(IllegalArgumentException.class,()->compile(-90,leg("CRUISE",List.of(Vec3.ZERO,new Vec3(10,0,0)),20,5,true),leg("TAXI_IN",List.of(new Vec3(11,0,0),new Vec3(20,0,0)),2,0,false)));
        assertThrows(IllegalArgumentException.class,()->compile(Double.NaN,leg("CRUISE",List.of(Vec3.ZERO,new Vec3(10,0,0)),20,5,true)));
    }
    @Test void pathNbtRoundTripKeepsEverySampleAndRejectsInvalidNumbersAndShape() {
        Object path=compile(0,leg("CRUISE",List.of(new Vec3(-50,100,0),new Vec3(0,100,0),new Vec3(0,100,50)),20,10,true));
        CompoundTag saved=(CompoundTag)invoke(path,"save");Object copy=invoke(type("RailAircraftPath"),"load",saved);
        assertEquals(number(path,"length"),number(copy,"length"));
        for(double s=0;s<=number(path,"length");s+=.5)assertEquals(sample(path,s),sample(copy,s));
        CompoundTag invalid=saved.copy();invalid.putDouble("FinalYaw",Double.NaN);
        assertThrows(IllegalArgumentException.class,()->invoke(type("RailAircraftPath"),"load",invalid));
        CompoundTag missing=saved.copy();missing.remove("Legs");
        assertThrows(IllegalArgumentException.class,()->invoke(type("RailAircraftPath"),"load",missing));
    }
    @Test void takeoffAcceleratesByDistanceAndNeverSinksBelowItsFlatRunway() {
        RailAircraftPath p=RailAircraftPath.compile(List.of(
                new RailAircraftPath.Leg(RailAircraftPath.Phase.LINE_UP,List.of(new Vec3(0,64,0),new Vec3(10,64,0)),3,0,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.TAKEOFF,List.of(new Vec3(10,64,0),new Vec3(110,64,0)),25,0,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.CLIMB,List.of(new Vec3(110,64,0),new Vec3(310,104,0)),25,30,true)), -90);
        assertEquals(3,p.speedForAdvance(10),1e-9);
        assertTrue(p.speedForAdvance(60)>3&&p.speedForAdvance(60)<25);
        assertEquals(25,p.speedForAdvance(110),1e-9);
        for(double s=10;s<110;s+=.1)assertEquals(64,p.sample(s).position().y,1e-9);
        assertTrue(p.sample(109).pitchRadians()>0,"The nose rises naturally before leaving the runway");
        assertEquals(p.sample(110-.0001).pitchRadians(),p.sample(110+.0001).pitchRadians(),1e-4);
        assertEquals(Math.sin(p.sample(109).pitchRadians()),p.sample(109).tangent().y,1e-9);
    }
    @Test void landingSlowsAndFinalTaxiRetainsPositiveSpeedUntilTheExactStand() {
        RailAircraftPath p=RailAircraftPath.compile(List.of(
                new RailAircraftPath.Leg(RailAircraftPath.Phase.LANDING_ROLL,List.of(new Vec3(0,64,0),new Vec3(100,64,0)),25,0,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.TAXI_IN,List.of(new Vec3(100,64,0),new Vec3(120,64,0)),3,0,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.STAND_STABILIZING,List.of(new Vec3(120,64,0)),0,0,false)), -90);
        assertEquals(25,p.sample(0).speed());assertTrue(p.sample(50).speed()>3&&p.sample(50).speed()<25);
        assertEquals(3,p.sample(100).speed());
        assertTrue(p.speedForAdvance(119.999)>0&&p.speedForAdvance(119.999)<3);
        assertEquals(0,p.sample(120).speed());
        RailAircraftMission m=(RailAircraftMission)RailAircraftMissionTest.mission(p);
        int ticks=0;while(m.distance()<p.length()&&ticks++<4000)assertTrue(m.commitStep(m.proposeStep(true),true));
        assertEquals(p.length(),m.distance(),"Positive final taxi floor must eventually reach the exact stand");
    }
    @Test void evenSubNanometreRemaindersCannotSkipAHoldOrStrandTheFinalStep() {
        Vec3 hold=new Vec3(10,64,0);
        RailAircraftPath p=RailAircraftPath.compile(List.of(
                new RailAircraftPath.Leg(RailAircraftPath.Phase.TAXI_OUT,List.of(new Vec3(0,64,0),hold),2,0,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.HOLD_SHORT,List.of(hold),0,0,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.LINE_UP,List.of(hold,new Vec3(20,64,0)),2,0,false)), -90);
        assertEquals(10,p.nextBoundary(Math.nextDown(10.0)));
        assertTrue(p.speedForAdvance(Math.nextDown(p.length()))>0);
    }
    @Test void theFinalTaxiCurveMeetsStandHeadingWithoutAnInstantLastFrameRotation() {
        RailAircraftPath p=RailAircraftPath.compile(List.of(
                new RailAircraftPath.Leg(RailAircraftPath.Phase.TAXI_IN,List.of(new Vec3(0,64,0),new Vec3(20,64,0)),3,3,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.STAND_STABILIZING,List.of(new Vec3(20,64,0)),0,0,false)), 0);
        Vec3 finalForward=new Vec3(0,0,1);
        assertTrue(p.sample(p.length()-.001).tangent().dot(finalForward)>.9999);
        for(double s=0;s<p.length();s+=.05)assertTrue(Math.abs(p.sample(s).position().z)<=3+1e-8);
    }
    @Test void interpolationCannotMakeAnOtherwiseBoundedRouteLeaveWorldBounds() {
        assertThrows(IllegalArgumentException.class,()->RailAircraftPath.compile(List.of(
                new RailAircraftPath.Leg(RailAircraftPath.Phase.CLIMB,List.of(new Vec3(0,2000,0),new Vec3(100,2047,0)),25,30,true),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.CRUISE,List.of(new Vec3(100,2047,0),new Vec3(200,2047,0)),25,30,true)), -90));
    }
    @Test void leavingAHoldTurnsGraduallyWithoutMovingTheHoldPointOrCuttingTheRunway() {
        Vec3 hold=new Vec3(10,64,0);
        RailAircraftPath p=RailAircraftPath.compile(List.of(
                new RailAircraftPath.Leg(RailAircraftPath.Phase.TAXI_OUT,List.of(new Vec3(0,64,0),hold),2,3,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.HOLD_SHORT,List.of(hold),0,0,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.LINE_UP,List.of(hold,new Vec3(10,64,20)),2,3,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.TAKEOFF,List.of(new Vec3(10,64,20),new Vec3(10,64,120)),25,0,false)),0);
        assertEquals(hold,p.sample(10).position());
        assertTrue(p.sample(10-.001).tangent().dot(p.sample(10+.001).tangent())>.9999);
        double runway=p.nextBoundary(10);for(double s=runway;s<p.length();s+=.1)assertEquals(10,p.sample(s).position().x,1e-9);
    }
    @Test void aZeroRadiusCannotSilentlyCreateAnInstantMovingTurn() {
        assertThrows(IllegalArgumentException.class,()->RailAircraftPath.compile(List.of(
                new RailAircraftPath.Leg(RailAircraftPath.Phase.TAXI_IN,List.of(new Vec3(0,64,0),new Vec3(10,64,0),new Vec3(10,64,10)),3,0,false)),0));
    }
    @Test void actualCompiledControlHullsAreImmutableCorrectlyOwnedAndDoNotChangeThePath() {
        Object p=compile(0,leg("TAXI_OUT",List.of(new Vec3(0,64,0),new Vec3(10,64,0),new Vec3(10,64,10)),2,2,false),
                leg("HOLD_SHORT",List.of(new Vec3(10,64,10)),0,0,false),
                leg("LINE_UP",List.of(new Vec3(10,64,10),new Vec3(10,64,30)),2,2,false));
        CompoundTag before=(CompoundTag)invoke(p,"save");List<?> hulls=(List<?>)invoke(p,"controlPolygons");
        assertTrue(hulls.size()>2);assertThrows(UnsupportedOperationException.class,hulls::clear);
        boolean prefix=false,afterHold=false;double end=0;
        for(Object hull:hulls) {
            int index=(int)number(hull,"legIndex");prefix|=index==0;afterHold|=index==2;assertNotEquals(1,index);
            double start=number(hull,"startDistance"),last=number(hull,"endDistance");
            assertEquals(end,start,1e-9);assertTrue(last>start);end=last;
            List<?> points=(List<?>)invoke(hull,"points");assertTrue(points.size()>=2&&points.size()<=4);
            assertThrows(UnsupportedOperationException.class,points::clear);
            assertEquals(0,((Vec3)points.getFirst()).distanceTo(position(p,start)),1e-8);
            assertEquals(0,((Vec3)points.getLast()).distanceTo(position(p,last)),1e-8);
            assertEquals(index==0?"TAXI_OUT":"LINE_UP",invoke(hull,"phase").toString());
        }
        assertTrue(prefix&&afterHold);assertEquals(number(p,"length"),end,1e-9);assertEquals(before,invoke(p,"save"));
    }
    static Object rotationProcedure(double radius) {
        List<Object> legs=List.of(
                leg("LINE_UP",List.of(new Vec3(0,64,0),new Vec3(10,64,0)),3,0,false),
                leg("TAKEOFF",List.of(new Vec3(10,64,0),new Vec3(110,64,0)),25,0,false),
                leg("CLIMB",List.of(new Vec3(110,64,0),new Vec3(310,104,0)),25,0,true),
                leg("CRUISE",List.of(new Vec3(310,104,0),new Vec3(510,104,0),new Vec3(510,104,200)),25,40,true),
                leg("APPROACH",List.of(new Vec3(510,104,200),new Vec3(710,64,200)),25,0,true),
                leg("LANDING_ROLL",List.of(new Vec3(710,64,200),new Vec3(810,64,200)),25,0,false),
                leg("TAXI_IN",List.of(new Vec3(810,64,200),new Vec3(830,64,200)),3,0,false),
                leg("STAND_STABILIZING",List.of(new Vec3(830,64,200)),0,0,false));
        return invoke(type("RailAircraftPath"),"compile",legs,-90D,radius);
    }
    @Test void rotationRadiusIsBoundedPersistedAndIncludedInTheGeometryFingerprint() {
        Object p=rotationProcedure(16.56);assertEquals(16.56,number(p,"rotationRadius"));
        CompoundTag saved=(CompoundTag)invoke(p,"save");assertEquals(16.56,saved.getDouble("RotationRadius"));
        Object copy=invoke(type("RailAircraftPath"),"load",saved);
        assertNotEquals(invoke(p,"fingerprint"),invoke(rotationProcedure(0),"fingerprint"));
        for(double s=0;s<=number(p,"length");s+=.5)assertEquals(sample(p,s),sample(copy,s));
        for(double invalid:new double[]{-.1,64.1,Double.NaN,Double.POSITIVE_INFINITY})assertThrows(IllegalArgumentException.class,()->rotationProcedure(invalid));
        assertDoesNotThrow(()->rotationProcedure(64));
        CompoundTag old=(CompoundTag)invoke(rotationProcedure(0),"save");old.remove("RotationRadius");
        assertEquals(0,number(invoke(type("RailAircraftPath"),"load",old),"rotationRadius"));
        saved.putDouble("RotationRadius",Double.NaN);assertThrows(IllegalArgumentException.class,()->invoke(type("RailAircraftPath"),"load",saved));
    }
    @Test void visualRotationCannotPushTheRearOrFrontSupportBelowItsOriginalGroundPlane() {
        double radius=16.56,belowAnchor=2.7,ground=64-belowAnchor;Object p=rotationProcedure(radius);
        for(double s=0;s<number(p,"length");s+=.05) {
            Object state=sample(p,s);String phase=invoke(state,"phase").toString();
            if(!List.of("TAKEOFF","CLIMB","APPROACH").contains(phase))continue;
            double pitch=number(state,"pitchRadians"),anchor=((Vec3)invoke(state,"position")).y;
            double lowestCorner=anchor-radius*Math.abs(Math.sin(pitch))-belowAnchor*Math.cos(pitch);
            assertTrue(lowestCorner>=ground-1e-8,"Support penetrates ground at "+s+" in "+phase+": "+lowestCorner);
        }
    }
    @Test void compensatedPositionAndPitchHaveContinuousFirstDerivativesAcrossFiveProcedureBoundaries() {
        Object p=rotationProcedure(16.56);List<?> hulls=(List<?>)invoke(p,"controlPolygons");
        for(int leg=0;leg<5;leg++) {
            double at=0;for(Object hull:hulls)if((int)number(hull,"legIndex")==leg)at=Math.max(at,number(hull,"endDistance"));
            double h=.001;Vec3 center=position(p,at),left=center.subtract(position(p,at-h)).scale(1/h),right=position(p,at+h).subtract(center).scale(1/h);
            assertTrue(left.distanceTo(right)<.002,"Position derivative jumps at leg "+leg+": "+left+" / "+right);
            double pitch=number(sample(p,at),"pitchRadians"),before=(pitch-number(sample(p,at-h),"pitchRadians"))/h,after=(number(sample(p,at+h),"pitchRadians")-pitch)/h;
            assertEquals(before,after,.0002,"Pitch derivative jumps at leg "+leg);
        }
    }
    @Test void cruiseBankAddsNoHeightAndGroundEndpointsRemainExact() {
        Object p=rotationProcedure(16.56),base=rotationProcedure(0);boolean banked=false;
        for(double s=0;s<number(p,"length");s+=.5) {
            Object state=sample(p,s);if(!"CRUISE".equals(invoke(state,"phase").toString()))continue;
            banked|=Math.abs(number(state,"bankRadians"))>.02;assertEquals(position(base,s).y,position(p,s).y,1e-10);
        }
        assertTrue(banked);assertEquals(new Vec3(0,64,0),position(p,0));assertEquals(new Vec3(830,64,200),position(p,number(p,"length")));
    }
    @Test void clearanceCannotBeCompiledBeyondTheWorldHeightLimit() {
        assertThrows(IllegalArgumentException.class,()->invoke(type("RailAircraftPath"),"compile",List.of(
                leg("TAKEOFF",List.of(new Vec3(0,2040,0),new Vec3(100,2040,0)),25,0,false),
                leg("CLIMB",List.of(new Vec3(100,2040,0),new Vec3(200,2046,0)),25,0,true)),-90D,16.56));
    }
}
