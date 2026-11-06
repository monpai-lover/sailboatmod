package com.monpai.sailboatmod.transport.air;

import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.InvocationTargetException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailFlightPlanBuilderTest {
    final AirportFacility source=new AirportFacility("source",AirportFacility.Type.RUNWAY,new BlockPos(0,-60,0),new BlockPos(256,-60,0),32,16);
    final AirportFacility target=new AirportFacility("target",AirportFacility.Type.RUNWAY,new BlockPos(2000,-60,0),new BlockPos(2256,-60,0),32,16);
    final AirportTaxiRoute out=new AirportTaxiRoute("out",AirportTaxiRoute.Direction.OUTBOUND,"source","src",List.of(new Vec3(-40.5,-60,.5),new Vec3(-10.5,-60,.5),new Vec3(.5,-60,.5)),32,16,3,1);
    final AirportTaxiRoute in=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"target","dst",List.of(new Vec3(2256.5,-60,.5),new Vec3(2280.5,-60,.5),new Vec3(2300.5,-60,.5)),32,16,3,-1);
    final AirportParkingStand stand=new AirportParkingStand("dst",new Vec3(2300.5,-60,.5),-90,32,32,16,true);
    RailAircraftPath build() { return RailFlightPlanBuilder.build(source,target,out,in,stand,2.7,20,List.of(new Vec3(.5,81,.5),new Vec3(2000.5,81,.5)),25); }

    @Test void usesActualAirportSurfaceAndMeasuredAnchorOffsetInsteadOfLogicalAirportY() {
        var path=build(); assertEquals(out.points().getFirst().add(0,2.7,0),path.sample(0).position());
        assertEquals(stand.position().add(0,2.7,0),path.sample(path.length()).position());
        assertEquals(-57.3,path.legs().stream().filter(l->l.phase()==RailAircraftPath.Phase.TAKEOFF).findFirst().orElseThrow().points().getFirst().y,1e-8);
    }
    @Test void containsGroundProceduresAndExactRunwayRolls() {
        var p=build();var phases=p.legs().stream().map(RailAircraftPath.Leg::phase).toList();
        for(var phase:List.of(RailAircraftPath.Phase.TAXI_OUT,RailAircraftPath.Phase.HOLD_SHORT,RailAircraftPath.Phase.LINE_UP,RailAircraftPath.Phase.TAKEOFF,RailAircraftPath.Phase.CLIMB,RailAircraftPath.Phase.CRUISE,RailAircraftPath.Phase.APPROACH,RailAircraftPath.Phase.LANDING_ROLL,RailAircraftPath.Phase.TAXI_IN,RailAircraftPath.Phase.STAND_STABILIZING))assertTrue(phases.contains(phase),phase.toString());
        var roll=p.legs().stream().filter(l->l.phase()==RailAircraftPath.Phase.LANDING_ROLL).findFirst().orElseThrow();
        assertEquals(new Vec3(2000.5,-57.3,.5),roll.points().getFirst()); assertEquals(new Vec3(2256.5,-57.3,.5),roll.points().getLast());
    }
    @Test void climbApproachStayBelowTwelveDegreesAndGroundNeverBanks() {
        var p=build();
        for(double s=0;s<p.length();s+=.5){var v=p.sample(s);assertTrue(Math.abs(v.pitchRadians())<=Math.toRadians(12)+1e-6,"pitch at "+s);
            if(v.phase()==RailAircraftPath.Phase.TAXI_OUT||v.phase()==RailAircraftPath.Phase.LINE_UP||v.phase()==RailAircraftPath.Phase.TAKEOFF||v.phase()==RailAircraftPath.Phase.LANDING_ROLL||v.phase()==RailAircraftPath.Phase.TAXI_IN)assertEquals(0,v.bankRadians(),1e-9);}
    }
    @Test void oppositeHeadingAirportsProduceARealTurningPathAndFinishUpright() {
        var west=new AirportFacility("target",AirportFacility.Type.RUNWAY,new BlockPos(-1800,-60,0),new BlockPos(-2056,-60,0),32,16);
        var arrival=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"target","dst",List.of(new Vec3(-2055.5,-60,.5),new Vec3(-2075.5,-60,.5),new Vec3(-2100.5,-60,.5)),32,16,3,-1);
        var park=new AirportParkingStand("dst",new Vec3(-2100.5,-60,.5),90,32,32,16,true);
        var p=RailFlightPlanBuilder.build(source,west,out,arrival,park,2.7,20,List.of(new Vec3(0,0,0),new Vec3(-1800,0,0)),25);
        boolean banked=false;for(double s=0;s<p.length();s+=2)if(Math.abs(p.sample(s).bankRadians())>.02)banked=true;
        assertTrue(banked);assertTrue(p.sample(p.length()).tangent().distanceTo(new Vec3(-1,0,0))<1e-9);assertEquals(0,p.sample(p.length()).bankRadians(),1e-9);
    }
    @Test void refusesUnconfiguredGroundGapsAndWrongFinalStandHeading() {
        var gap=new AirportTaxiRoute("out",out.direction(),out.runwayId(),out.standId(),List.of(out.points().getFirst(),new Vec3(10.5,-60,.5)),32,16,3,0);
        assertThrows(IllegalArgumentException.class,()->RailFlightPlanBuilder.build(source,target,gap,in,stand,2.7,20,List.of(Vec3.ZERO,new Vec3(2000,0,0)),25));
        var wrong=new AirportParkingStand("dst",stand.position(),0,32,32,16,true);
        assertThrows(IllegalArgumentException.class,()->RailFlightPlanBuilder.build(source,target,out,in,wrong,2.7,20,List.of(Vec3.ZERO,new Vec3(2000,0,0)),25));
    }
    @Test void rejectsNonFiniteAndOversizedGeometry() {
        assertThrows(IllegalArgumentException.class,()->RailFlightPlanBuilder.build(source,target,out,in,stand,Double.NaN,20,List.of(Vec3.ZERO,new Vec3(2000,0,0)),25));
        assertThrows(IllegalArgumentException.class,()->RailFlightPlanBuilder.build(source,target,out,in,stand,2.7,33,List.of(Vec3.ZERO,new Vec3(2000,0,0)),25));
    }
    @Test void anAuthoredIntermediateMayCoincideWithTheGeneratedApproachEntry() {
        double height=-60+2.7+48,length=96/Math.tan(Math.toRadians(12));
        Vec3 entry=new Vec3(2000.5-length,height,.5);
        RailAircraftPath p=assertDoesNotThrow(()->RailFlightPlanBuilder.build(source,target,out,in,stand,2.7,20,
                List.of(new Vec3(.5,0,.5),entry,new Vec3(2000.5,0,.5)),25));
        assertEquals(stand.position().add(0,2.7,0),p.sample(p.length()).position());
    }
    @Test void coincidentClimbAndApproachEntriesDoNotRequireAZeroLengthCruise() {
        var nearby=new AirportFacility("target",AirportFacility.Type.RUNWAY,new BlockPos(1280,-60,0),new BlockPos(1536,-60,0),32,16);
        var taxi=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"target","dst",List.of(new Vec3(1536.5,-60,.5),new Vec3(1560.5,-60,.5),new Vec3(1580.5,-60,.5)),32,16,3,-1);
        var park=new AirportParkingStand("dst",new Vec3(1580.5,-60,.5),-90,32,32,16,true);
        double height=-60+2.7+256*Math.tan(Math.toRadians(12));
        RailAircraftPath p=assertDoesNotThrow(()->RailFlightPlanBuilder.build(source,nearby,out,taxi,park,2.7,20,
                List.of(new Vec3(.5,0,.5),new Vec3(.5,height,.5),new Vec3(1280.5,0,.5)),25));
        assertEquals(park.position().add(0,2.7,0),p.sample(p.length()).position());
    }
    @Test void pathologicalFiftyEightPointReversalsAreRejectedAtTheExistingComplexityBound() {
        List<Vec3> authored=new ArrayList<>();authored.add(new Vec3(.5,0,.5));
        for(int i=0;i<56;i++)authored.add(new Vec3(900+3*(i%2),-60+2.7+48,100));
        authored.add(new Vec3(2000.5,0,.5));
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->RailFlightPlanBuilder.build(source,target,out,in,stand,2.7,20,authored,25));
        assertEquals("rail_route_too_complex",failure.getMessage());
    }
    @Test void theSupportedFiftyEightStraightAuthoredPointsRemainWithinEveryCompiledLegBound() {
        List<Vec3> authored=new ArrayList<>();authored.add(new Vec3(.5,0,.5));
        for(int i=0;i<56;i++)authored.add(new Vec3(800+12.5*i,-60+2.7+48,.5));
        authored.add(new Vec3(2000.5,0,.5));
        RailAircraftPath p=assertDoesNotThrow(()->RailFlightPlanBuilder.build(source,target,out,in,stand,2.7,20,authored,25));
        assertTrue(p.legs().stream().allMatch(l->l.points().size()<=4096));
        assertEquals(stand.position().add(0,2.7,0),p.sample(p.length()).position());
    }
    @Test void everyPronouncedAirTurnBanksTowardItsMeasuredHeadingChange() {
        var west=new AirportFacility("target",AirportFacility.Type.RUNWAY,new BlockPos(-1800,-60,0),new BlockPos(-2056,-60,0),32,16);
        var arrival=new AirportTaxiRoute("in",AirportTaxiRoute.Direction.INBOUND,"target","dst",List.of(new Vec3(-2055.5,-60,.5),new Vec3(-2075.5,-60,.5),new Vec3(-2100.5,-60,.5)),32,16,3,-1);
        var park=new AirportParkingStand("dst",new Vec3(-2100.5,-60,.5),90,32,32,16,true);
        RailAircraftPath p=RailFlightPlanBuilder.build(source,west,out,arrival,park,2.7,20,List.of(Vec3.ZERO,new Vec3(-1800,0,0)),25);
        int proved=0;
        for(double s=1;s<p.length()-1;s+=2) {
            var a=p.sample(s);var b=p.sample(s+1);double cross=a.tangent().x*b.tangent().z-a.tangent().z*b.tangent().x;
            if(a.phase()==RailAircraftPath.Phase.CRUISE&&Math.abs(cross)>.002&&Math.abs(a.bankRadians())>.02) {
                assertTrue(a.bankRadians()*cross>0,"Turn banks outwards at distance "+s);proved++;
            }
        }
        assertTrue(proved>10);
    }
    @Test void aBodyInsideTheStandButAwayFromPathStartRequiresAlignmentRatherThanRecentering() {
        Vec3 start=out.points().getFirst().add(0,2.7,0),forward=new Vec3(1,0,0);
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,()->actualStart(start.add(1,0,0),forward));
        assertEquals("rail_start_alignment_required",failure.getMessage());
        assertThrows(IllegalArgumentException.class,()->actualStart(start,forward.scale(-1)));
        assertDoesNotThrow(()->actualStart(start,forward));
    }
    private RailAircraftPath actualStart(Vec3 position,Vec3 forward) {
        try {
            var method=RailFlightPlanBuilder.class.getMethod("build",AirportFacility.class,AirportFacility.class,AirportTaxiRoute.class,
                    AirportTaxiRoute.class,AirportParkingStand.class,double.class,double.class,List.class,double.class,Vec3.class,Vec3.class);
            return (RailAircraftPath)method.invoke(null,source,target,out,in,stand,2.7,20,List.of(new Vec3(.5,81,.5),new Vec3(2000.5,81,.5)),25,position,forward);
        } catch(InvocationTargetException wrapped) {
            if(wrapped.getCause() instanceof RuntimeException failure)throw failure;
            return fail(wrapped.getCause());
        } catch(ReflectiveOperationException missing){return fail("Actual-start alignment API is absent",missing);}
    }
    @Test void aSmallOffCenterDepartureUsesTheExactActualAnchorAndPreservesTheOriginalHold() {
        Vec3 actual=out.points().getFirst().add(0,2.7,.2);RailAircraftPath p=connectedStart(actual,new Vec3(1,0,0));
        assertEquals(actual,p.sample(0).position());assertEquals(new Vec3(1,0,0),p.sample(0).tangent());
        assertEquals(RailAircraftPath.Phase.TAXI_OUT,p.legs().getFirst().phase());
        assertEquals(p.legs().getFirst().points().getLast(),p.legs().get(1).points().getFirst());
        assertEquals(out.points().get(out.holdPointIndex()).add(0,2.7,0),p.legs().stream().filter(l->l.phase()==RailAircraftPath.Phase.HOLD_SHORT).findFirst().orElseThrow().points().getFirst());
    }
    @Test void aSeveralMetreOffsetKeepsItsActualInitialHeadingAndJoinsContinuously() {
        Vec3 actual=out.points().getFirst().add(0,2.7,3),forward=new Vec3(1,0,-.1).normalize();
        RailAircraftPath p=connectedStart(actual,forward);assertEquals(actual,p.sample(0).position());
        assertTrue(p.sample(0).tangent().dot(forward)>1-1e-10);
        double join=p.controlPolygons().stream().filter(h->h.legIndex()==0).mapToDouble(RailAircraftPath.ControlHull::endDistance).max().orElseThrow();
        assertTrue(p.sample(join-.001).tangent().dot(p.sample(join+.001).tangent())>.9999);
    }
    @Test void anUnprovedOrVerticallyMisalignedDepartureIsRejectedWithoutRecentering() {
        Vec3 start=out.points().getFirst().add(0,2.7,0),forward=new Vec3(1,0,0);
        assertEquals("rail_start_alignment_required",assertThrows(IllegalArgumentException.class,()->connectedStart(start.add(0,0,7),forward)).getMessage());
        assertEquals("rail_start_alignment_required",assertThrows(IllegalArgumentException.class,()->connectedStart(start.add(0,.1,.2),forward)).getMessage());
    }
    private RailAircraftPath connectedStart(Vec3 actual,Vec3 forward) {
        var sourceStand=new AirportParkingStand("src",out.points().getFirst(),-90,32,32,16,true);
        try {
            var method=RailFlightPlanBuilder.class.getMethod("build",AirportFacility.class,AirportFacility.class,AirportTaxiRoute.class,
                    AirportTaxiRoute.class,AirportParkingStand.class,double.class,double.class,List.class,double.class,AirportParkingStand.class,Vec3.class,Vec3.class);
            return (RailAircraftPath)method.invoke(null,source,target,out,in,stand,2.7,20,List.of(new Vec3(.5,81,.5),new Vec3(2000.5,81,.5)),25,sourceStand,actual,forward);
        } catch(InvocationTargetException wrapped) {
            if(wrapped.getCause() instanceof RuntimeException failure)throw failure;
            return fail(wrapped.getCause());
        } catch(ReflectiveOperationException missing){return fail("Certified stand departure API is absent",missing);}
    }
    @Test void theActualSweepRadiusSurvivesTheCertifiedDepartureRecompile() {
        assertEquals(10,build().rotationRadius());
        assertEquals(10,connectedStart(out.points().getFirst().add(0,2.7,.2),new Vec3(1,0,0)).rotationRadius());
    }
    @Test void aLargeAircraftCannotAddRotationClearanceAboveTheWorldCeiling() {
        var highSource=new AirportFacility("source",AirportFacility.Type.RUNWAY,new BlockPos(0,1989,0),new BlockPos(256,1989,0),32,16);
        var highTarget=new AirportFacility("target",AirportFacility.Type.RUNWAY,new BlockPos(2000,1989,0),new BlockPos(2256,1989,0),32,16);
        var highOut=new AirportTaxiRoute(out.id(),out.direction(),out.runwayId(),out.standId(),out.points().stream().map(p->p.add(0,2049,0)).toList(),32,16,3,1);
        var highIn=new AirportTaxiRoute(in.id(),in.direction(),in.runwayId(),in.standId(),in.points().stream().map(p->p.add(0,2049,0)).toList(),32,16,3,-1);
        var highStand=new AirportParkingStand(stand.id(),stand.position().add(0,2049,0),stand.yawDegrees(),32,32,16,true);
        var failure=assertThrows(IllegalArgumentException.class,()->RailFlightPlanBuilder.build(highSource,highTarget,highOut,highIn,highStand,2.7,20,List.of(Vec3.ZERO,new Vec3(2000,0,0)),25));
        assertEquals("flight_outside_height",failure.getMessage());
    }
}
