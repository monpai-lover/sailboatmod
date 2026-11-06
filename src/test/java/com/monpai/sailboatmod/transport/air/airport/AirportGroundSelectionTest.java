package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.air.airport.AirportGroundSelection.*;

/** Selector contract tests; route geometry is a separate injected/pr4-tested necessary-condition gate. */
class AirportGroundSelectionTest {
    private static final Vec3 STAND=new Vec3(-80.5,64,80.5);
    private static final RouteGate VERIFIED=(r,c,w)->true;
    private record Fixture(AirportGroundLayout layout,List<AirportFacility> runways) { }

    @Test void differentCustomHullsChooseFittingIncomingStandsWithoutAircraftIdentityRules() {
        var r=runway("arbitrary-r",32,256);
        var compact=stand("compact",8);var wide=stand("wide",32);
        var layout=new AirportGroundLayout(7,List.of(compact,wide),List.of(
                out("compact-out",r.id(),compact.id(),8),in("compact-in",r.id(),compact.id(),8),
                out("wide-out",r.id(),wide.id(),32),in("wide-in",r.id(),wide.id(),32)));
        assertConnected(layout,List.of(r));
        var small=search(layout,List.of(r),AirportTaxiRoute.Direction.INBOUND,capabilities(4,4,true,true),VERIFIED);
        assertFalse(small.candidates().isEmpty(),"A verified compact custom aircraft has a compatible pair");
        assertEquals("compact",small.candidates().getFirst().assignment().standId());
        var large=search(layout,List.of(r),AirportTaxiRoute.Direction.INBOUND,capabilities(18,8,true,true),VERIFIED);
        assertFalse(large.candidates().isEmpty(),"The wider custom hull must skip the first small stand");
        assertEquals("wide",large.candidates().getFirst().assignment().standId());
        assertTrue(large.candidates().stream().noneMatch(c->c.assignment().standId().equals("compact")));
    }

    @Test void anUnusableFirstConnectionDoesNotHideAnotherFittingRunwayTaxiPair() {
        var s=stand("any-stand-id",32);var narrow=runway("first-narrow",8,256);var wide=runway("second-wide",32,256);
        var layout=new AirportGroundLayout(8,List.of(s),List.of(out("n-out",narrow.id(),s.id(),8),in("n-in",narrow.id(),s.id(),8),
                out("w-out",wide.id(),s.id(),32),in("w-in",wide.id(),s.id(),32)));
        assertConnected(layout,List.of(narrow,wide));
        var result=search(layout,List.of(narrow,wide),AirportTaxiRoute.Direction.OUTBOUND,capabilities(18,8,true,true),VERIFIED);
        assertFalse(result.candidates().isEmpty());
        assertEquals("second-wide",result.candidates().getFirst().assignment().runwayId());
        assertTrue(result.candidates().stream().noneMatch(c->c.assignment().runwayId().equals("first-narrow")));
    }

    @Test void routeAssessmentRejectionSkipsTheFirstPairAndSeesMeasuredTurnCapability() {
        var s=stand("custom-pad",32);var sharp=runway("sharp-r",32,256);var gentle=runway("gentle-r",32,256);
        var layout=new AirportGroundLayout(9,List.of(s),List.of(out("sharp-out",sharp.id(),s.id(),32),in("sharp-in",sharp.id(),s.id(),32),
                out("gentle-out",gentle.id(),s.id(),32),in("gentle-in",gentle.id(),s.id(),32)));
        assertConnected(layout,List.of(sharp,gentle));
        var caps=capabilities(4,4,true,true);List<String> assessed=new ArrayList<>();
        RouteGate geometryResult=(route,cap,corridor)->{assertSame(caps,cap);assertEquals(12,cap.minimumTurnRadius());assertTrue(corridor>0);assessed.add(route.id());return !route.id().startsWith("sharp");};
        var result=search(layout,List.of(sharp,gentle),AirportTaxiRoute.Direction.OUTBOUND,caps,geometryResult);
        assertFalse(result.candidates().isEmpty());assertEquals("gentle-r",result.candidates().getFirst().assignment().runwayId());
        assertTrue(assessed.contains("sharp-out"));assertTrue(assessed.contains("gentle-out"));
    }

    @Test void unverifiedCapabilitiesAndDisconnectedConfigurationNeverBecomeCandidates() {
        var f=onePair();
        for(var caps:List.of(capabilities(4,4,false,true),capabilities(4,4,true,false))){
            var result=search(f.layout,f.runways,AirportTaxiRoute.Direction.OUTBOUND,caps,VERIFIED);
            assertTrue(result.candidates().isEmpty());assertEquals("capability_unproven",result.reason());
        }
        var s=f.layout.stands().getFirst();var r=f.runways.getFirst();
        var wrong=new AirportTaxiRoute("disconnected",AirportTaxiRoute.Direction.INBOUND,r.id(),s.id(),
                List.of(new Vec3(256.5,64,.5),new Vec3(64.5,64,.5),STAND.add(0,0,3)),32,8,1,-1);
        var broken=new AirportGroundLayout(2,List.of(s),List.of(f.layout.taxiRoutes().getFirst(),wrong));
        var result=search(broken,f.runways,AirportTaxiRoute.Direction.INBOUND,capabilities(4,4,true,true),VERIFIED);
        assertTrue(result.candidates().isEmpty());assertEquals("disconnected_route",result.reason());
    }

    @Test void exactCapturedReturnDoesNotSubstituteAnotherStillValidRoute() {
        var f=onePair();var caps=capabilities(4,4,true,true);
        var exact=new GroundAssignment(f.layout.revision(),"runway-a","pad-a","out-a","in-a");
        assertTrue(captured(f.layout,f.runways,AirportTaxiRoute.Direction.OUTBOUND,exact,caps,VERIFIED).isPresent());
        RouteGate oldRouteBlocked=(route,c,w)->!route.id().equals("in-a");
        assertTrue(captured(f.layout,f.runways,AirportTaxiRoute.Direction.OUTBOUND,exact,caps,oldRouteBlocked).isEmpty());
        assertTrue(captured(f.layout.withRevision(99),f.runways,AirportTaxiRoute.Direction.OUTBOUND,exact,caps,VERIFIED).isEmpty());
    }

    @Test void legalMaximumPairsCacheEachRouteAndAvoidThePairCrossProductForLegChecks() {
        var f=denseLayout();var caps=capabilities(4,4,true,true);Map<String,Integer> calls=new HashMap<>();
        var departure=search(f.layout,f.runways,AirportTaxiRoute.Direction.OUTBOUND,caps,(route,c,w)->{calls.merge(route.id(),1,Integer::sum);return true;});
        assertFalse(departure.candidates().isEmpty());assertTrue(departure.examinedPairs()<=MAX_PAIRS);
        assertTrue(departure.routeEvaluations()<=MAX_ROUTE_EVALUATIONS);assertTrue(calls.values().stream().allMatch(n->n==1));
        var arrival=search(f.layout,f.runways,AirportTaxiRoute.Direction.INBOUND,caps,VERIFIED);
        AtomicInteger flights=new AtomicInteger();
        var result=leg(departure.candidates(),arrival.candidates(),new LegGate(){
            public boolean taxi(Candidate a,Candidate b){return true;}
            public boolean flight(AirportFacility a,AirportFacility b){flights.incrementAndGet();return false;}
        });
        assertTrue(result.selected().isEmpty());assertFalse(result.exhausted(),"All legal active-route combinations fit the hard bound");
        assertTrue(result.examinedCombinations()>0&&result.examinedCombinations()<=MAX_LEG_COMBINATIONS);
        assertTrue(flights.get()>0&&flights.get()<=MAX_FLIGHT_EVALUATIONS);assertEquals(flights.get(),result.flightEvaluations());
    }

    private static void assertConnected(AirportGroundLayout layout,List<AirportFacility> runways){assertEquals(AirportGroundValidation.Result.VALID,AirportGroundValidation.references(layout,runways),"Positive selector fixture must satisfy the existing domain reference/hold checks");}
    private static Fixture connectedFixture(AirportGroundLayout layout,List<AirportFacility> runways){assertConnected(layout,runways);return new Fixture(layout,runways);}
    private static Capabilities capabilities(double x,double z,boolean calibrated,boolean nativeReady){
        var hull=new AABB(STAND.x-x/2,64,STAND.z-z/2,STAND.x+x/2,68,STAND.z+z/2);
        return new Capabilities(UUID.randomUUID(),hull,new Vec3(STAND.x,66,STAND.z),-90,0,1,12,30,10,calibrated,nativeReady);
    }
    private static AirportParkingStand stand(String id,double size){return new AirportParkingStand(id,STAND,-90,size,size,8,true);}
    private static AirportFacility runway(String id,int width,int length){return new AirportFacility(id,AirportFacility.Type.RUNWAY,new BlockPos(0,64,0),new BlockPos(length,64,0),width,8);}
    private static AirportTaxiRoute out(String id,String runway,String stand,double width){return new AirportTaxiRoute(id,AirportTaxiRoute.Direction.OUTBOUND,runway,stand,
            List.of(STAND,new Vec3(-64.5,64,80.5),new Vec3(-40.5,64,64.5),new Vec3(-40.5,64,.5),new Vec3(-36.5,64,.5),new Vec3(.5,64,.5)),width,8,1,4);}
    private static AirportTaxiRoute in(String id,String runway,String stand,double width){return new AirportTaxiRoute(id,AirportTaxiRoute.Direction.INBOUND,runway,stand,
            List.of(new Vec3(256.5,64,.5),new Vec3(320.5,64,.5),new Vec3(320.5,64,160.5),new Vec3(128.5,64,160.5),new Vec3(-64.5,64,160.5),new Vec3(-128.5,64,160.5),new Vec3(-128.5,64,80.5),STAND),width,8,1,-1);}
    private static Fixture onePair(){var s=stand("pad-a",32);var r=runway("runway-a",32,256);return connectedFixture(new AirportGroundLayout(2,List.of(s),List.of(out("out-a",r.id(),s.id(),32),in("in-a",r.id(),s.id(),32))),List.of(r));}
    private static Fixture denseLayout(){
        List<AirportFacility> runways=new ArrayList<>();for(int i=0;i<16;i++)runways.add(runway("r"+i,32,256));
        List<AirportParkingStand> stands=List.of(stand("s0",32),stand("s1",32));List<AirportTaxiRoute> routes=new ArrayList<>();
        for(var s:stands)for(var r:runways){routes.add(out(s.id()+"-o-"+r.id(),r.id(),s.id(),32));routes.add(in(s.id()+"-i-"+r.id(),r.id(),s.id(),32));}
        return connectedFixture(new AirportGroundLayout(1,stands,routes),runways);
    }
}
