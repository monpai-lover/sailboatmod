package com.monpai.sailboatmod.transport.air.rail;

import com.monpai.sailboatmod.transport.air.airport.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailStandDepartureConnectorTest {
    private final AirportParkingStand stand=new AirportParkingStand("stand",new Vec3(0,64,0),0,24,20,8,true);
    private final AirportTaxiRoute route=new AirportTaxiRoute("out",AirportTaxiRoute.Direction.OUTBOUND,"runway","stand",
            List.of(stand.position(),new Vec3(0,64,18),new Vec3(0,64,40)),10,8,2,1);
    private final Vec3 forward=new Vec3(0,0,1);
    private Object connect(AirportParkingStand s,Vec3 start,Vec3 f,AirportTaxiRoute out,double radius,double corner,Function<Object,RailAircraftPath> compiler){
        try {
            Class<?> type=Class.forName("com.monpai.sailboatmod.transport.air.rail.RailStandDepartureConnector");
            return type.getMethod("connect",AirportParkingStand.class,Vec3.class,Vec3.class,AirportTaxiRoute.class,double.class,double.class,Function.class)
                    .invoke(null,s,start,f,out,radius,corner,compiler);
        }catch(ClassNotFoundException missing){return fail("Certified stand departure connector is missing",missing);}
        catch(InvocationTargetException failure){return fail("Connector must reject invalid geometry without failing its caller",failure.getCause());}
        catch(ReflectiveOperationException failure){return fail("Connector API contract changed",failure);}
    }
    private static Object value(Object owner,String name){try{return owner.getClass().getMethod(name).invoke(owner);}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
    @SuppressWarnings("unchecked") private static List<Vec3> points(Object connection,String name){return (List<Vec3>)value(connection,name);}
    private Function<Object,RailAircraftPath> compile(double corner,double finalYaw){return connection->RailAircraftPath.compile(List.of(
            new RailAircraftPath.Leg(RailAircraftPath.Phase.TAXI_OUT,points(connection,"prefixPoints"),2,corner,false),
            new RailAircraftPath.Leg(RailAircraftPath.Phase.TAXI_OUT,points(connection,"remainingOutboundPoints"),2,corner,false)),finalYaw);}
    @SuppressWarnings("unchecked") private Optional<RailAircraftPath> path(Object result){return (Optional<RailAircraftPath>)value(result,"path");}
    private RailAircraftPath accepted(Vec3 start){return path(connect(stand,start,forward,route,2,2,compile(2,0))).orElseThrow();}
    private void rejected(AirportParkingStand s,Vec3 start,Vec3 f,AirportTaxiRoute out,double radius,double corner){
        Object result=connect(s,start,f,out,radius,corner,compile(2,0));assertTrue(path(result).isEmpty());assertEquals("rail_start_alignment_required",value(result,"reason"));
    }
    @Test void startsAtTheActualPointEvenForASmallOffCenterParkingPosition(){
        Vec3 actual=new Vec3(.2,65.25,.3);var path=accepted(actual);
        assertEquals(actual,path.sample(0).position());assertTrue(path.sample(0).tangent().dot(forward)>1-1e-10);
    }
    @Test void supportsSeveralMetersOfLateralAndLongitudinalOffsetWithoutACenterTeleport(){
        for(Vec3 actual:List.of(new Vec3(3,65.25,-2),new Vec3(-3,65.25,2),new Vec3(7,65.25,0)))assertEquals(actual,accepted(actual).sample(0).position());
    }
    @Test void connectionRetainsTheExistingFirstSegmentAndHoldPoint(){
        List<Object> seen=new ArrayList<>();Object result=connect(stand,new Vec3(2,65.25,0),forward,route,2,2,c->{seen.add(c);return compile(2,0).apply(c);});
        assertTrue(path(result).isPresent());Object selected=seen.getLast();var prefix=points(selected,"prefixPoints");var rest=points(selected,"remainingOutboundPoints");
        assertEquals(4,prefix.size());assertEquals(prefix.getLast(),rest.getFirst());assertEquals(route.holdPointIndex(),value(selected,"holdPointIndex"));
        assertEquals(new Vec3(0,65.25,18),rest.get(1));assertEquals(new Vec3(0,65.25,40),rest.getLast());
        assertTrue(prefix.getLast().subtract(prefix.get(prefix.size()-2)).normalize().dot(forward)>1-1e-10);
        assertTrue((double)value(selected,"joinFraction")>0&&(double)value(selected,"joinFraction")<1);
    }
    @Test void certifiesTheActualCompiledCurvesAndTheirEntireSweptCircle(){
        var path=accepted(new Vec3(3,65.25,-2));
        List<?> hulls=(List<?>)value(path,"controlPolygons");double end=0;
        for(Object hull:hulls)if((int)value(hull,"legIndex")==0){
            end=Math.max(end,(double)value(hull,"endDistance"));
            @SuppressWarnings("unchecked") List<Vec3> controls=(List<Vec3>)value(hull,"points");
            for(int i=0;i<=200;i++)assertTrue(sweptInside(bezier(controls,i/200D),stand,route,2),"compiled Bezier escaped the verified ground union");
        }
        assertTrue(end>0);
        for(int i=0;i<=500;i++){var p=path.sample(end*i/500D);assertEquals(65.25,p.position().y,1e-10);assertEquals(0,p.bankRadians());assertEquals(0,p.pitchRadians());}
    }
    @Test void rotatedStandsAndRoutesUseTheirActualOrientation(){
        var rotated=new AirportParkingStand("stand",new Vec3(100,64,200),90,24,20,8,true);
        var out=new AirportTaxiRoute("out",route.direction(),"runway","stand",List.of(rotated.position(),new Vec3(82,64,200),new Vec3(60,64,200)),10,8,2,1);
        Vec3 actual=new Vec3(101,65.25,203);var result=connect(rotated,actual,new Vec3(-1,0,0),out,2,2,compile(2,90));
        assertTrue(path(result).isPresent());assertEquals(actual,path(result).orElseThrow().sample(0).position());assertTrue(path(result).orElseThrow().sample(0).tangent().dot(new Vec3(-1,0,0))>1-1e-10);
    }
    @Test void actualSweepRadiusCannotBeReplacedByTheNarrowBodyCenterline(){rejected(stand,new Vec3(9,65.25,0),forward,route,2,2);}
    @Test void insufficientRouteWidthAndExcessiveCornerRadiusFailClosed(){
        var narrow=new AirportTaxiRoute("out",route.direction(),"runway","stand",route.points(),4,8,2,1);
        rejected(stand,new Vec3(.2,65.25,0),forward,narrow,2,2);rejected(stand,new Vec3(.2,65.25,0),forward,route,2,4);
    }
    @Test void cannotPassAnAlreadyConfiguredHoldAtTheStandToJoinFartherAlong(){
        var immediate=new AirportTaxiRoute("out",route.direction(),"runway","stand",route.points(),10,8,2,0);
        rejected(stand,new Vec3(.2,65.25,0),forward,immediate,2,2);
    }
    @Test void anUnknownOrDishonestCompilerCannotCertifyADifferentStartOrUnsafePrefix(){
        Vec3 actual=new Vec3(.2,65.25,0);
        Function<Object,RailAircraftPath> wrong=c->RailAircraftPath.compile(List.of(new RailAircraftPath.Leg(RailAircraftPath.Phase.TAXI_OUT,List.of(new Vec3(0,65.25,0),new Vec3(0,65.25,40)),2,2,false)),0);
        assertTrue(path(connect(stand,actual,forward,route,2,2,wrong)).isEmpty());
        Function<Object,RailAircraftPath> unsafe=c->RailAircraftPath.compile(List.of(
                new RailAircraftPath.Leg(RailAircraftPath.Phase.TAXI_OUT,List.of(actual,actual.add(0,0,1),new Vec3(40,65.25,10),points(c,"prefixPoints").getLast()),2,2,false),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.TAXI_OUT,points(c,"remainingOutboundPoints"),2,2,false)),0);
        assertTrue(path(connect(stand,actual,forward,route,2,2,unsafe)).isEmpty());
    }
    @Test void aCompilerRejectionDoesNotEscapeOrCreateAnUnprovenConnection(){assertTrue(path(connect(stand,new Vec3(.2,65.25,0),forward,route,2,2,c->{throw new IllegalArgumentException("phase join rejected");})).isEmpty());}
    @Test void nullAndNonFiniteInputsFailClosed(){
        rejected(null,new Vec3(.2,65.25,0),forward,route,2,2);rejected(stand,null,forward,route,2,2);
        rejected(stand,new Vec3(.2,65.25,0),new Vec3(0,1,0),route,2,2);rejected(stand,new Vec3(Double.NaN,65,0),forward,route,2,2);
        rejected(stand,new Vec3(.2,65.25,0),forward,route,Double.NaN,2);rejected(stand,new Vec3(.2,65.25,0),forward,route,-1,2);
        assertTrue(path(connect(stand,new Vec3(.2,65.25,0),forward,route,2,2,null)).isEmpty());
    }
    private static Vec3 bezier(List<Vec3> points,double t){var work=new ArrayList<>(points);for(int n=work.size()-1;n>0;n--)for(int i=0;i<n;i++)work.set(i,work.get(i).lerp(work.get(i+1),t));return work.getFirst();}
    private static boolean sweptInside(Vec3 p,AirportParkingStand s,AirportTaxiRoute r,double radius){
        double yaw=Math.toRadians(s.yawDegrees()),dx=p.x-s.position().x,dz=p.z-s.position().z;
        boolean rectangle=Math.abs(-Math.sin(yaw)*dx+Math.cos(yaw)*dz)<=s.length()/2-radius+1e-8&&Math.abs(Math.cos(yaw)*dx+Math.sin(yaw)*dz)<=s.width()/2-radius+1e-8;
        Vec3 a=r.points().getFirst(),b=r.points().get(1);double x=b.x-a.x,z=b.z-a.z,t=Math.max(0,Math.min(1,((p.x-a.x)*x+(p.z-a.z)*z)/(x*x+z*z)));
        return rectangle||Math.hypot(p.x-a.x-t*x,p.z-a.z-t*z)<=r.width()/2-radius+1e-8;
    }
}
