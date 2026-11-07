package com.monpai.sailboatmod.transport.air.airport;

import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.FixedWingRunwayPolicy;
import com.monpai.sailboatmod.transport.air.FlightRoutePlanner;
import com.monpai.sailboatmod.transport.air.airport.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bounded pure filtering of configured resources. Admission here never reserves or starts a mission. */
public final class AirportGroundSelection {
    public static final int MAX_PAIRS=1024, MAX_ROUTE_EVALUATIONS=64;
    public static final int MAX_LEG_COMBINATIONS=4096, MAX_FLIGHT_EVALUATIONS=256;
    public record Capabilities(UUID bodyId,AABB hull,Vec3 nativeReference,double yawDegrees,double speed,
            double brakingDeceleration,double minimumTurnRadius,double maximumSteeringDegrees,
            double nativeConservativeSpeed,boolean liveCalibrated,boolean nativeReady) { }
    @FunctionalInterface public interface RouteGate {
        boolean admits(AirportTaxiRoute route,Capabilities capabilities,double corridorHalfWidth);
    }
    public record Candidate(GroundAssignment assignment,AirportParkingStand stand,
            AirportFacility selectedRunway,AirportTaxiRoute outbound,AirportTaxiRoute inbound) { }
    public record Search(List<Candidate> candidates,int examinedPairs,int routeEvaluations,boolean exhausted,String reason) {
        public Search { candidates=List.copyOf(candidates); }
    }
    public interface LegGate {
        /** Must assess the current leg's actual source OUT and destination IN using their shared corridor. */
        boolean taxi(Candidate source,Candidate destination);
        /** Pure flight geometry for these runways only; caller retains every later native/live gate. */
        boolean flight(AirportFacility source,AirportFacility destination);
    }
    public record Leg(Candidate source,Candidate destination) { }
    public record LegSearch(Optional<Leg> selected,int examinedCombinations,int flightEvaluations,boolean exhausted,String reason) { }
    private record RunwayPair(AirportFacility source,AirportFacility destination) { }
    private AirportGroundSelection() { }

    public static Search search(AirportGroundLayout layout,List<AirportFacility> runways,
            AirportTaxiRoute.Direction role,Capabilities capabilities,RouteGate gate) {
        String failure=initialFailure(layout,runways,role,capabilities,gate);
        if(!failure.isEmpty())return new Search(List.of(),0,0,false,failure);
        var context=new Evaluation(layout,List.copyOf(runways),capabilities,gate);
        List<Candidate> candidates=new ArrayList<>();int examined=0;
        // Stable configuration order; prefer a valid same-runway opposite route for each primary.
        for(var stand:layout.stands()){
            if(!standFits(stand,capabilities,role,context.diameter))continue;
            for(var runway:context.runways){
                var primary=layout.route(role,stand.id(),runway.id()).orElse(null);
                if(primary==null)continue;
                for(var other:oppositeRoutes(layout,stand.id(),runway.id(),role)){
                    if(examined>=MAX_PAIRS)return new Search(candidates,examined,context.gateCalls,true,"candidate_limit");
                    examined++;
                    AirportTaxiRoute out=role==AirportTaxiRoute.Direction.OUTBOUND?primary:other;
                    AirportTaxiRoute in=role==AirportTaxiRoute.Direction.INBOUND?primary:other;
                    if(!context.routeFits(out)||!context.routeFits(in))continue;
                    var assignment=new GroundAssignment(layout.revision(),runway.id(),stand.id(),out.id(),in.id());
                    if(assignment.forDirection(layout,context.runways,role).filter(assignment::equals).isEmpty())continue;
                    candidates.add(new Candidate(assignment,stand,runway,out,in));
                }
            }
        }
        return new Search(candidates,examined,context.gateCalls,false,candidates.isEmpty()?"no_compatible_pair":"");
    }

    /** Validate just the captured identities, never search for a replacement when they are no longer usable. */
    public static Optional<Candidate> captured(AirportGroundLayout layout,List<AirportFacility> runways,
            AirportTaxiRoute.Direction role,GroundAssignment exact,Capabilities capabilities,RouteGate gate) {
        if(!initialFailure(layout,runways,role,capabilities,gate).isEmpty()||exact==null
                ||exact.forDirection(layout,runways,role).filter(exact::equals).isEmpty())return Optional.empty();
        var context=new Evaluation(layout,List.copyOf(runways),capabilities,gate);
        var stand=layout.stand(exact.standId()).orElse(null);
        if(stand==null||!standFits(stand,capabilities,role,context.diameter))return Optional.empty();
        var out=layout.taxiRoutes().stream().filter(r->r.id().equals(exact.outboundRouteId())).findFirst().orElse(null);
        var in=layout.taxiRoutes().stream().filter(r->r.id().equals(exact.inboundRouteId())).findFirst().orElse(null);
        var runway=runways.stream().filter(r->r.id().equals(exact.runwayId())).findFirst().orElse(null);
        if(runway==null||out==null||in==null||!context.routeFits(out)||!context.routeFits(in))return Optional.empty();
        return Optional.of(new Candidate(exact,stand,runway,out,in));
    }

    /** Current-leg routes are independent of the stored opposite routes; assess those again on an exact return. */
    public static LegSearch leg(List<Candidate> sources,List<Candidate> destinations,LegGate gate) {
        if(sources==null||destinations==null||gate==null)return noLeg("invalid_candidates",false);
        if(sources.size()>MAX_PAIRS||destinations.size()>MAX_PAIRS)return noLeg("candidate_limit",true);
        var sourceActive=activeRoutes(sources,AirportTaxiRoute.Direction.OUTBOUND);
        var targetActive=activeRoutes(destinations,AirportTaxiRoute.Direction.INBOUND);
        if(sourceActive==null||targetActive==null)return noLeg("invalid_candidates",false);
        if(sourceActive.size()>64||targetActive.size()>64)return noLeg("active_route_limit",true);
        if(sourceActive.values().stream().map(Candidate::selectedRunway).distinct().count()>16
                ||targetActive.values().stream().map(Candidate::selectedRunway).distinct().count()>16)
            return noLeg("runway_limit",true);
        Map<RunwayPair,Boolean> flightCache=new HashMap<>();int examined=0,flights=0;
        for(Candidate source:sourceActive.values())for(Candidate target:targetActive.values()){
            if(examined>=MAX_LEG_COMBINATIONS)return new LegSearch(Optional.empty(),examined,flights,true,"leg_limit");
            examined++;
            boolean taxi;
            try{taxi=gate.taxi(source,target);}catch(RuntimeException unproven){taxi=false;}
            if(!taxi)continue;
            RunwayPair key=new RunwayPair(source.selectedRunway(),target.selectedRunway());
            Boolean flight=flightCache.get(key);
            if(flight==null){
                if(flights>=MAX_FLIGHT_EVALUATIONS)return new LegSearch(Optional.empty(),examined,flights,true,"flight_limit");
                flights++;
                try{flight=gate.flight(key.source,key.destination);}catch(RuntimeException unproven){flight=false;}
                flightCache.put(key,flight);
            }
            if(flight)return new LegSearch(Optional.of(new Leg(source,target)),examined,flights,false,"");
        }
        return new LegSearch(Optional.empty(),examined,flights,false,"no_compatible_leg");
    }

    private static LegSearch noLeg(String reason,boolean exhausted){return new LegSearch(Optional.empty(),0,0,exhausted,reason);}
    private static LinkedHashMap<String,Candidate> activeRoutes(List<Candidate> candidates,AirportTaxiRoute.Direction role){
        LinkedHashMap<String,Candidate> active=new LinkedHashMap<>();
        for(Candidate c:candidates){
            if(c==null||c.assignment==null||c.stand==null||c.selectedRunway==null||c.outbound==null||c.inbound==null
                    ||!c.assignment.standId().equals(c.stand.id())||!c.assignment.outboundRouteId().equals(c.outbound.id())
                    ||!c.assignment.inboundRouteId().equals(c.inbound.id())||!c.outbound.standId().equals(c.stand.id())
                    ||!c.inbound.standId().equals(c.stand.id())||c.outbound.direction()!=AirportTaxiRoute.Direction.OUTBOUND
                    ||c.inbound.direction()!=AirportTaxiRoute.Direction.INBOUND)return null;
            var route=role==AirportTaxiRoute.Direction.OUTBOUND?c.outbound:c.inbound;
            if(!c.assignment.runwayId().equals(route.runwayId())||!c.selectedRunway.id().equals(route.runwayId()))return null;
            active.putIfAbsent(route.id(),c);
        }
        return active;
    }
    private static String initialFailure(AirportGroundLayout layout,List<AirportFacility> runways,
            AirportTaxiRoute.Direction role,Capabilities cap,RouteGate gate){
        if(!capabilityValid(cap))return "capability_unproven";
        if(layout==null||runways==null||role==null||gate==null||runways.size()>16)return "invalid_geometry";
        Set<String> ids=new HashSet<>();for(var r:runways)if(r==null||!r.validGeometry()||!ids.add(r.id()))return "invalid_geometry";
        var references=AirportGroundValidation.references(layout,runways);
        return references==AirportGroundValidation.Result.VALID?"":references.name().toLowerCase(Locale.ROOT);
    }
    private static boolean capabilityValid(Capabilities c){
        if(c==null||c.bodyId==null||c.hull==null||c.nativeReference==null||!c.liveCalibrated||!c.nativeReady)return false;
        var h=c.hull;var p=c.nativeReference;
        return finite(h.minX,h.maxX,h.minY,h.maxY,h.minZ,h.maxZ,p.x,p.y,p.z,c.yawDegrees,c.speed,
                c.brakingDeceleration,c.minimumTurnRadius,c.maximumSteeringDegrees,c.nativeConservativeSpeed)
                &&h.getXsize()>0&&h.getYsize()>0&&h.getZsize()>0&&c.speed>=0&&c.speed<=.25
                &&c.brakingDeceleration>0&&c.minimumTurnRadius>0&&c.maximumSteeringDegrees>0
                &&c.maximumSteeringDegrees<=30&&c.nativeConservativeSpeed>0;
    }
    private static boolean finite(double... values){for(double v:values)if(!Double.isFinite(v))return false;return true;}
    private static boolean standFits(AirportParkingStand stand,Capabilities c,AirportTaxiRoute.Direction role,double diameter){
        if(!stand.validGeometry()||!stand.cargoEnabled())return false;
        if(role==AirportTaxiRoute.Direction.OUTBOUND)return stand.contains(c.hull,c.yawDegrees,c.speed);
        // Conservative envelope around the native reference at any arrival yaw; no fake pose/world mutation.
        return stand.length()>=diameter&&stand.width()>=diameter&&stand.clearanceHeight()>=c.hull.getYsize();
    }
    private static List<AirportTaxiRoute> oppositeRoutes(AirportGroundLayout layout,String stand,String runway,AirportTaxiRoute.Direction role){
        var opposite=role==AirportTaxiRoute.Direction.OUTBOUND?AirportTaxiRoute.Direction.INBOUND:AirportTaxiRoute.Direction.OUTBOUND;
        List<AirportTaxiRoute> result=new ArrayList<>();
        layout.route(opposite,stand,runway).ifPresent(result::add);
        for(var r:layout.taxiRoutes())if(r.direction()==opposite&&r.standId().equals(stand)&&!r.runwayId().equals(runway))result.add(r);
        return result;
    }
    private static final class Evaluation {
        final AirportGroundLayout layout;final List<AirportFacility> runways;final Capabilities cap;final RouteGate gate;
        final double diameter;final Map<String,Boolean> routeCache=new HashMap<>();int gateCalls;
        Evaluation(AirportGroundLayout layout,List<AirportFacility> runways,Capabilities cap,RouteGate gate){
            this.layout=layout;this.runways=runways;this.cap=cap;this.gate=gate;diameter=FlightRoutePlanner.requiredHorizontalDiameter(cap.hull,cap.nativeReference);
        }
        boolean routeFits(AirportTaxiRoute route){
            Boolean old=routeCache.get(route.id());if(old!=null)return old;
            boolean fits=false;
            var runway=runways.stream().filter(r->r.id().equals(route.runwayId())).findFirst().orElse(null);
            double corridor=(route.width()-diameter)/2;
            if(route.fits(cap.hull)&&corridor>=.5&&runway!=null&&runway.supports(AircraftClass.FIXED_WING)
                    &&runway.width()>=diameter+2&&runway.clearanceHeight()>=cap.hull.getYsize()
                    &&runway.length()>=Math.max(32,cap.nativeConservativeSpeed*6)
                    &&FixedWingRunwayPolicy.stoppingFits(runway.length(),cap.nativeConservativeSpeed,cap.brakingDeceleration,diameter)
                    &&AirportGroundValidation.linkedEndpoint(route,runways,layout.stands())){
                if(gateCalls>=MAX_ROUTE_EVALUATIONS)return false;
                gateCalls++;
                try{fits=gate.admits(route,cap,corridor);}catch(RuntimeException unproven){fits=false;}
            }
            routeCache.put(route.id(),fits);return fits;
        }
    }
}
