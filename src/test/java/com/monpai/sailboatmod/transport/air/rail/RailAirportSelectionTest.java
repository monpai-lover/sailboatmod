package com.monpai.sailboatmod.transport.air.rail;

import com.monpai.sailboatmod.transport.air.airport.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailAirportSelectionTest {
    private record Frame(UUID body, AABB hull, Vec3 anchor, double yaw) { }
    private record Airport(AirportGroundLayout layout, List<AirportFacility> facilities) { }
    private final UUID body=UUID.randomUUID();
    private final Airport source=airport(0,"stand",32,16,8);
    private final Airport target=airport(1000,"stand",32,16,8);
    private Frame frame(){return new Frame(body,new AABB(-42.5,64,-2.5,-38.5,67,3.5),new Vec3(-40.5,65.25,.5),0);}
    private static Airport airport(int x,String standId,int standWidth,int routeWidth,int height){
        var runway=new AirportFacility("runway",AirportFacility.Type.RUNWAY,new BlockPos(x,64,0),new BlockPos(x+128,64,0),32,height);
        var stand=new AirportParkingStand(standId,new Vec3(x-40.5,64,.5),0,32,standWidth,height,true);
        var out=new AirportTaxiRoute("out",AirportTaxiRoute.Direction.OUTBOUND,runway.id(),stand.id(),List.of(stand.position(),new Vec3(x-25.5,64,.5),new Vec3(x+.5,64,.5)),routeWidth,height,2,1);
        var in=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,runway.id(),stand.id(),List.of(new Vec3(x+128.5,64,.5),new Vec3(x+128.5,64,30.5),new Vec3(x-40.5,64,30.5),stand.position()),routeWidth,height,2,-1);
        return new Airport(new AirportGroundLayout(7,List.of(stand),List.of(out,in)),List.of(runway));
    }
    // Reflection keeps the missing-feature RED executable before the new selector class exists.
    private Object result(Frame frame,Airport from,GroundAssignment fromExact,Airport to,GroundAssignment toExact){
        try{
            Class<?> selector=Class.forName("com.monpai.sailboatmod.transport.air.rail.RailAirportSelection");
            Class<?> aircraft=Class.forName(selector.getName()+"$Aircraft");
            Object actual=frame==null?null:aircraft.getConstructor(UUID.class,AABB.class,Vec3.class,double.class).newInstance(frame.body,frame.hull,frame.anchor,frame.yaw);
            return selector.getMethod("select",aircraft,AirportGroundLayout.class,List.class,GroundAssignment.class,AirportGroundLayout.class,List.class,GroundAssignment.class)
                    .invoke(null,actual,from.layout,from.facilities,fromExact,to.layout,to.facilities,toExact);
        }catch(ClassNotFoundException missing){return fail("Rail airport selection feature is missing",missing);}
        catch(InvocationTargetException failure){return fail("Selector must reject invalid input without throwing",failure.getCause());}
        catch(ReflectiveOperationException failure){return fail("Rail selector public API contract changed",failure);}
    }
    private Object admissibleResult(Airport to,GroundAssignment exact,Predicate<Object> accepts){
        try{
            Class<?> selector=Class.forName("com.monpai.sailboatmod.transport.air.rail.RailAirportSelection");Class<?> aircraft=Class.forName(selector.getName()+"$Aircraft");var f=frame();
            Object actual=aircraft.getConstructor(UUID.class,AABB.class,Vec3.class,double.class).newInstance(f.body,f.hull,f.anchor,f.yaw);
            return selector.getMethod("select",aircraft,AirportGroundLayout.class,List.class,GroundAssignment.class,AirportGroundLayout.class,List.class,GroundAssignment.class,Predicate.class)
                    .invoke(null,actual,source.layout,source.facilities,null,to.layout,to.facilities,exact,accepts);
        }catch(NoSuchMethodException missing){return fail("Rail admissibility fallback feature is missing",missing);}
        catch(InvocationTargetException failure){return fail("Admissibility callback failure must reject only that candidate",failure.getCause());}
        catch(ReflectiveOperationException failure){return fail("Rail selector callback API failed",failure);}
    }
    private static Object value(Object owner,String name){try{return owner.getClass().getMethod(name).invoke(owner);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private Optional<?> selected(Object result){return (Optional<?>)value(result,"selection");}
    private Object selection(){return selected(result(frame(),source,null,target,null)).orElseThrow();}
    private static GroundAssignment assignment(Object candidate){return (GroundAssignment)value(candidate,"assignment");}
    private static GroundAssignment pinned(){return new GroundAssignment(7,"runway","stand","out","in");}
    private void rejected(Frame f,Airport from,GroundAssignment exactFrom,Airport to,GroundAssignment exactTo){assertTrue(selected(result(f,from,exactFrom,to,exactTo)).isEmpty());}

    @Test void selectsActualBodyGeometryWithoutNativeCapabilitiesOrCalibration(){
        var leg=selection();assertEquals(pinned(),assignment(value(leg,"source")));assertEquals(pinned(),assignment(value(leg,"destination")));
        assertEquals(1.25,(double)value(leg,"anchorAboveSurface"),1e-12);
        assertEquals(2*Math.hypot(2,3),(double)value(leg,"sweepDiameter"),1e-12);
    }
    @Test void requiresWholeSourceHullInsideItsActualStand(){var f=frame();rejected(new Frame(body,f.hull.move(15,0,0),f.anchor.add(15,0,0),0),source,null,target,null);}
    @Test void sourceHeadingMustMatchTheConfiguredStand(){var f=frame();rejected(new Frame(body,f.hull,f.anchor,25),source,null,target,null);}
    @Test void sourceUsesStandClearanceWithoutPretendingTheHullBottomIsWheelContact(){
        var f=frame();assertTrue(selected(result(new Frame(body,f.hull.move(0,.702,0),f.anchor.add(0,.702,0),0),source,null,target,null)).isPresent());
        for(double y:new double[]{-.51,6})rejected(new Frame(body,f.hull.move(0,y,0),f.anchor.add(0,y,0),0),source,null,target,null);
    }
    @Test void refusesAnAnchorOutsideTheActualBody(){var f=frame();rejected(new Frame(body,f.hull,f.anchor.add(20,0,0),0),source,null,target,null);}
    @Test void offCenterAnchorRequiresItsOwnSweptClearance(){
        var narrow=airport(0,"stand",32,10,8);var f=new Frame(body,new AABB(-46.5,64,-2.5,-38.5,67,3.5),new Vec3(-38.75,65,.5),0);
        rejected(f,narrow,null,target,null);
    }
    @Test void destinationSelectionSkipsAStandThatCannotContainTheBody(){
        var small=airport(1000,"small",6,16,8);var big=airport(1000,"big",32,16,8);
        List<AirportTaxiRoute> routes=new ArrayList<>();for(var r:small.layout.taxiRoutes())routes.add(rename(r,"small-"+r.id()));for(var r:big.layout.taxiRoutes())routes.add(rename(r,"big-"+r.id()));
        var both=new Airport(new AirportGroundLayout(7,List.of(small.layout.stands().getFirst(),big.layout.stands().getFirst()),routes),big.facilities);
        var leg=selected(result(frame(),source,null,both,null)).orElseThrow();assertEquals("big",assignment(value(leg,"destination")).standId());
    }
    @Test void cannotInventAReverseTaxiRoute(){var layout=new AirportGroundLayout(7,target.layout.stands(),List.of(target.layout.taxiRoutes().getFirst()));rejected(frame(),source,null,new Airport(layout,target.facilities),null);}
    @Test void doesNotSubstituteAnotherAssignmentForAStaleCapturedRevision(){rejected(frame(),source,new GroundAssignment(8,"runway","stand","out","in"),target,null);}
    @Test void doesNotSubstituteAnotherRouteForAMissingCapturedId(){rejected(frame(),source,pinned(),target,new GroundAssignment(7,"runway","stand","out","removed"));}
    @Test void pinnedRoleMustMatchItsActiveRunwayDirection(){
        var opposite=oppositeTarget();rejected(frame(),source,null,opposite,new GroundAssignment(7,"runway","stand","out","in-reverse"));
    }
    @Test void preservesOppositeRunwayIdsAndBothExactRouteIds(){
        var opposite=oppositeTarget();var leg=selected(result(frame(),source,null,opposite,null)).orElseThrow();
        assertEquals(new GroundAssignment(7,"reverse","stand","out","in-reverse"),assignment(value(leg,"destination")));
        assertEquals("reverse",((AirportFacility)value(value(leg,"destination"),"runway")).id());
    }
    @Test void exactValidAssignmentsRemainUnchanged(){var leg=selected(result(frame(),source,pinned(),target,pinned())).orElseThrow();assertEquals(pinned(),assignment(value(leg,"source")));assertEquals(pinned(),assignment(value(leg,"destination")));}
    @Test void routeAndRunwayHeightClearanceIncludeTheActualAnchorHeight(){
        var f=frame();var tall=new Frame(body,new AABB(f.hull.minX,64,f.hull.minZ,f.hull.maxX,72.2,f.hull.maxZ),new Vec3(f.anchor.x,65.25,f.anchor.z),0);
        rejected(tall,airport(0,"stand",32,16,12),null,target,null);
    }
    @Test void cannotJoinAPlatformToAnUnrelatedMiddleOfTheRunway(){
        var out=source.layout.taxiRoutes().getFirst();var wrong=new AirportTaxiRoute(out.id(),out.direction(),out.runwayId(),out.standId(),List.of(out.points().getFirst(),out.points().get(1),new Vec3(64.5,64,.5)),out.width(),out.clearanceHeight(),out.speedLimitMetersPerSecond(),out.holdPointIndex());
        var bad=new Airport(new AirportGroundLayout(7,source.layout.stands(),List.of(wrong,source.layout.taxiRoutes().getLast())),source.facilities);
        assertEquals(AirportGroundValidation.Result.VALID,AirportGroundValidation.references(bad.layout,bad.facilities));rejected(frame(),bad,null,target,null);
    }
    @Test void allCapturedRoutesMustFitEvenWhenTheActiveRouteFits(){
        var out=source.layout.taxiRoutes().getFirst();var in=source.layout.taxiRoutes().getLast();
        var thin=new AirportTaxiRoute(in.id(),in.direction(),in.runwayId(),in.standId(),in.points(),8,in.clearanceHeight(),in.speedLimitMetersPerSecond(),-1);
        rejected(frame(),new Airport(new AirportGroundLayout(7,source.layout.stands(),List.of(out,thin)),source.facilities),null,target,null);
    }
    @Test void aWideTaxiRouteCannotHideAnUndersizedRunway(){
        var r=source.facilities.getFirst();var thin=new AirportFacility(r.id(),r.type(),r.start(),r.end(),7,r.clearanceHeight());
        rejected(frame(),new Airport(source.layout,List.of(thin)),null,target,null);
    }
    @Test void invalidOrMissingActualFramesFailClosed(){
        var f=frame();rejected(null,source,null,target,null);rejected(new Frame(null,f.hull,f.anchor,0),source,null,target,null);
        rejected(new Frame(body,f.hull,new Vec3(Double.NaN,65,.5),0),source,null,target,null);rejected(new Frame(body,f.hull,f.anchor,Double.NaN),source,null,target,null);
        rejected(new Frame(body,new AABB(0,0,0,0,0,0),Vec3.ZERO,0),source,null,target,null);
    }
    @Test void duplicateFacilityIdsAndMissingReferencesFailClosed(){
        rejected(frame(),new Airport(source.layout,List.of(source.facilities.getFirst(),source.facilities.getFirst())),null,target,null);
        rejected(frame(),new Airport(source.layout,List.of()),null,target,null);
    }
    @Test void newBodyIdentitiesUseTheSameGeometryRules(){var f=frame();assertTrue(selected(result(new Frame(UUID.randomUUID(),f.hull,f.anchor,0),source,null,target,null)).isPresent());}
    @Test void rejectedFirstLegContinuesToTheNextGeometricallyValidPair(){
        List<String> observed=new ArrayList<>();var result=admissibleResult(twoStands(),null,leg->{String id=assignment(value(leg,"destination")).standId();observed.add(id);return id.equals("second");});
        assertEquals(List.of("first","second"),observed);assertEquals("second",assignment(value(selected(result).orElseThrow(),"destination")).standId());
    }
    @Test void predicateFailureRejectsTheCandidateWithoutAbortingLaterPairs(){
        var result=admissibleResult(twoStands(),null,leg->{if(assignment(value(leg,"destination")).standId().equals("first"))throw new IllegalStateException("route build rejected");return true;});
        assertEquals("second",assignment(value(selected(result).orElseThrow(),"destination")).standId());
    }
    @Test void allRejectedLegsAndNullPredicateFailClosed(){
        var result=admissibleResult(twoStands(),null,leg->false);assertTrue(selected(result).isEmpty());assertEquals("no_compatible_leg",value(result,"reason"));
        assertTrue(selected(admissibleResult(twoStands(),null,null)).isEmpty());
    }
    @Test void predicateCannotReplaceAnExplicitCapturedAssignment(){
        var exact=new GroundAssignment(7,"runway","first","first-out","first-in");List<String> seen=new ArrayList<>();
        assertTrue(selected(admissibleResult(twoStands(),exact,leg->{seen.add(assignment(value(leg,"destination")).standId());return false;})).isEmpty());
        assertEquals(List.of("first"),seen);
    }
    private Airport twoStands(){
        var a=airport(1000,"first",32,16,8);var b=airport(1000,"second",32,16,8);List<AirportTaxiRoute> routes=new ArrayList<>();
        for(var r:a.layout.taxiRoutes())routes.add(rename(r,"first-"+r.id()));for(var r:b.layout.taxiRoutes())routes.add(rename(r,"second-"+r.id()));
        return new Airport(new AirportGroundLayout(7,List.of(a.layout.stands().getFirst(),b.layout.stands().getFirst()),routes),a.facilities);
    }
    private static AirportTaxiRoute rename(AirportTaxiRoute r,String id){return new AirportTaxiRoute(id,r.direction(),r.runwayId(),r.standId(),r.points(),r.width(),r.clearanceHeight(),r.speedLimitMetersPerSecond(),r.holdPointIndex());}
    private Airport oppositeTarget(){
        var r=target.facilities.getFirst();var reverse=new AirportFacility("reverse",r.type(),r.end(),r.start(),r.width(),r.clearanceHeight());var old=target.layout.taxiRoutes().getLast();
        var in=new AirportTaxiRoute("in-reverse",old.direction(),reverse.id(),old.standId(),List.of(new Vec3(1000.5,64,.5),old.points().getLast()),old.width(),old.clearanceHeight(),old.speedLimitMetersPerSecond(),-1);
        return new Airport(new AirportGroundLayout(7,target.layout.stands(),List.of(target.layout.taxiRoutes().getFirst(),in)),List.of(r,reverse));
    }
}
