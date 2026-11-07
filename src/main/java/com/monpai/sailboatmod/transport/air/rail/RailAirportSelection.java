package com.monpai.sailboatmod.transport.air.rail;

import com.monpai.sailboatmod.transport.air.AircraftClass;
import com.monpai.sailboatmod.transport.air.airport.*;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Pure geometry admission for actual aircraft; no native flight capability or calibration is implied. */
public final class RailAirportSelection {
    public static final int MAX_LEG_EVALUATIONS=256;
    public record Aircraft(UUID bodyId, AABB hull, Vec3 anchor, double yawDegrees) { }
    public record Candidate(GroundAssignment assignment, AirportParkingStand stand, AirportFacility runway,
                            AirportTaxiRoute outbound, AirportTaxiRoute inbound) { }
    public record Selection(Candidate source, Candidate destination, double anchorAboveSurface, double sweepDiameter) { }
    public record Result(Optional<Selection> selection, String reason) { }
    private RailAirportSelection() { }

    public static Result select(Aircraft aircraft, AirportGroundLayout source, List<AirportFacility> sourceFacilities,
                                GroundAssignment exactSource, AirportGroundLayout destination,
                                List<AirportFacility> destinationFacilities, GroundAssignment exactDestination) {
        return select(aircraft,source,sourceFacilities,exactSource,destination,destinationFacilities,exactDestination,selection->true);
    }
    /** Caller may reject a blocked/badly shaped leg; rejection never silently replaces captured resource IDs. */
    public static Result select(Aircraft aircraft, AirportGroundLayout source, List<AirportFacility> sourceFacilities,
                                GroundAssignment exactSource, AirportGroundLayout destination,
                                List<AirportFacility> destinationFacilities, GroundAssignment exactDestination,
                                Predicate<Selection> admissible) {
        if (!validAircraft(aircraft)) return unavailable("rail_aircraft_invalid");
        if (admissible==null) return unavailable("no_compatible_leg");
        if (!validAirport(source,sourceFacilities) || !validAirport(destination,destinationFacilities))
            return unavailable("rail_airport_invalid");
        double diameter=sweepDiameter(aircraft); boolean sourceFound=false; int evaluated=0;
        for (Candidate from : candidates(source,sourceFacilities,AirportTaxiRoute.Direction.OUTBOUND,exactSource)) {
            if (!parked(aircraft,from.stand(),0,0)) continue;
            double height=aircraft.anchor().y-from.stand().position().y;
            if (height<0 || height>from.stand().clearanceHeight() || !fits(aircraft,from,height,diameter,sourceFacilities)) continue;
            sourceFound=true;
            for (Candidate to : candidates(destination,destinationFacilities,AirportTaxiRoute.Direction.INBOUND,exactDestination)) {
                if (!fits(aircraft,to,height,diameter,destinationFacilities)) continue;
                if (evaluated++>=MAX_LEG_EVALUATIONS) return unavailable("rail_candidate_limit");
                Selection selection=new Selection(from,to,height,diameter);
                boolean accepted;
                try { accepted=admissible.test(selection); }
                catch (RuntimeException|LinkageError rejected) { accepted=false; }
                if (accepted) return new Result(Optional.of(selection),"");
            }
        }
        return unavailable(evaluated>0?"no_compatible_leg":sourceFound?"rail_destination_unavailable":"rail_source_unavailable");
    }

    public static AirportFacility.Type directType(AircraftClass airframe) {
        return switch (airframe) {
            case FIXED_WING -> AirportFacility.Type.RUNWAY;
            case ROTORCRAFT -> AirportFacility.Type.HELIPAD;
            case AIRSHIP -> AirportFacility.Type.MOORING;
        };
    }

    public static List<Candidate> directCandidates(AircraftClass airframe, AirportGroundLayout layout,
                                                   List<AirportFacility> facilities, GroundAssignment exact) {
        if (airframe == AircraftClass.FIXED_WING || layout == null || facilities == null) return List.of();
        return facilities.stream().filter(facility -> facility != null && facility.type() == directType(airframe)
                        && facility.validGeometry())
                .map(facility -> new Candidate(GroundAssignment.direct(layout.revision(),facility),null,facility,null,null))
                .filter(candidate -> exact == null || exact.equals(candidate.assignment())).toList();
    }

    public static boolean directParked(Aircraft aircraft, AirportFacility facility,double speed,double angularSpeed) {
        return validAircraft(aircraft) && facility != null && facility.type() != AirportFacility.Type.RUNWAY
                && Double.isFinite(angularSpeed) && angularSpeed >= 0 && angularSpeed <= Math.toRadians(2)
                && facility.isParked(aircraft.hull(),speed);
    }

    public static Result selectDirect(AircraftClass airframe,Aircraft aircraft, double speed, double angularSpeed,
                                      AirportGroundLayout source,List<AirportFacility> sourceFacilities,GroundAssignment exactSource,
                                      AirportGroundLayout target,List<AirportFacility> targetFacilities,GroundAssignment exactTarget,
                                      Predicate<Selection> admissible) {
        if (!validAircraft(aircraft)) return unavailable("rail_aircraft_invalid");
        double diameter = sweepDiameter(aircraft);
        boolean sourceFound = false;
        int evaluated = 0;
        for (Candidate from : directCandidates(airframe,source,sourceFacilities,exactSource)) {
            if (!directParked(aircraft,from.runway(),speed,angularSpeed)) continue;
            double height = aircraft.anchor().y - from.runway().start().getY();
            if (height < 0) continue;
            sourceFound = true;
            for (Candidate to : directCandidates(airframe,target,targetFacilities,exactTarget)) {
                if (to.runway().width() < diameter+2
                        || height+aircraft.hull().maxY-aircraft.anchor().y > to.runway().clearanceHeight()) continue;
                if (evaluated++ >= MAX_LEG_EVALUATIONS) return unavailable("rail_candidate_limit");
                Selection selection = new Selection(from,to,height,diameter);
                if (admissible.test(selection)) return new Result(Optional.of(selection),"");
            }
        }
        return unavailable(evaluated>0?"no_compatible_leg":sourceFound?"rail_direct_destination_unavailable":"rail_direct_source_unavailable");
    }

    /** Includes every heading around this actual material anchor, which need not be the hull center. */
    public static double sweepDiameter(Aircraft aircraft) {
        if (!validAircraft(aircraft)) return Double.NaN;
        AABB h=aircraft.hull(); Vec3 p=aircraft.anchor();
        return 2*Math.hypot(Math.max(Math.abs(h.minX-p.x),Math.abs(h.maxX-p.x)),
                Math.max(Math.abs(h.minZ-p.z),Math.abs(h.maxZ-p.z)));
    }

    /** A physical stand observation, not a claim about native wheel contact or completed mission progress. */
    public static boolean parked(Aircraft aircraft, AirportParkingStand stand, double speed, double angularSpeed) {
        return validAircraft(aircraft) && stand!=null && stand.cargoEnabled() && stand.validGeometry()
                && Double.isFinite(speed) && speed>=0 && speed<=.25
                && Double.isFinite(angularSpeed) && angularSpeed>=0 && angularSpeed<=Math.toRadians(2)
                && aircraft.anchor().y>=stand.position().y
                && aircraft.anchor().y<=stand.position().y+stand.clearanceHeight()
                && stand.contains(aircraft.hull(),aircraft.yawDegrees(),speed);
    }

    public static boolean validAircraft(Aircraft aircraft) {
        if (aircraft==null || aircraft.bodyId()==null || aircraft.hull()==null || aircraft.anchor()==null) return false;
        AABB h=aircraft.hull(); Vec3 p=aircraft.anchor();
        return finite(h.minX,h.minY,h.minZ,h.maxX,h.maxY,h.maxZ,p.x,p.y,p.z,aircraft.yawDegrees())
                && h.getXsize()>0 && h.getYsize()>0 && h.getZsize()>0
                && p.x>=h.minX-1e-7 && p.x<=h.maxX+1e-7 && p.y>=h.minY-1e-7 && p.y<=h.maxY+1e-7
                && p.z>=h.minZ-1e-7 && p.z<=h.maxZ+1e-7;
    }

    private static boolean fits(Aircraft aircraft,Candidate candidate,double anchorHeight,double diameter,List<AirportFacility> facilities) {
        AirportParkingStand stand=candidate.stand();
        double top=anchorHeight+aircraft.hull().maxY-aircraft.anchor().y;
        if (!stand.cargoEnabled() || stand.length()<diameter || stand.width()<diameter || top>stand.clearanceHeight()) return false;
        return routeFits(candidate.outbound(),aircraft,top,diameter,facilities)
                && routeFits(candidate.inbound(),aircraft,top,diameter,facilities);
    }
    private static boolean routeFits(AirportTaxiRoute route,Aircraft aircraft,double top,double diameter,List<AirportFacility> facilities) {
        AirportFacility runway=facility(facilities,route.runwayId());
        return route.validGeometry() && route.fits(aircraft.hull()) && route.width()>=diameter+1
                && top<=route.clearanceHeight() && runway!=null && runway.width()>=diameter+2
                && top<=runway.clearanceHeight();
    }
    private static boolean validAirport(AirportGroundLayout layout,List<AirportFacility> facilities) {
        if (layout==null || facilities==null || facilities.size()>16) return false;
        Set<String> ids=new HashSet<>();
        for (AirportFacility facility:facilities)
            if (facility==null || !facility.validGeometry() || !ids.add(facility.id())) return false;
        return AirportGroundValidation.references(layout,facilities)==AirportGroundValidation.Result.VALID;
    }
    private static List<Candidate> candidates(AirportGroundLayout layout,List<AirportFacility> facilities,
                                               AirportTaxiRoute.Direction role,GroundAssignment exact) {
        if (exact!=null) {
            if (exact.forDirection(layout,facilities,role).filter(exact::equals).isEmpty()) return List.of();
            Candidate value=candidate(layout,facilities,exact);
            return value==null?List.of():List.of(value);
        }
        List<Candidate> result=new ArrayList<>();
        for (AirportParkingStand stand:layout.stands()) {
            if (!stand.cargoEnabled()) continue;
            for (AirportFacility runway:facilities) {
                if (!runway.supports(AircraftClass.FIXED_WING)) continue;
                AirportTaxiRoute active=layout.route(role,stand.id(),runway.id()).orElse(null);
                if (active==null) continue;
                AirportTaxiRoute.Direction opposite=role==AirportTaxiRoute.Direction.OUTBOUND
                        ? AirportTaxiRoute.Direction.INBOUND:AirportTaxiRoute.Direction.OUTBOUND;
                List<AirportTaxiRoute> alternatives=new ArrayList<>();
                layout.route(opposite,stand.id(),runway.id()).ifPresent(alternatives::add);
                for (AirportTaxiRoute route:layout.taxiRoutes())
                    if (route.direction()==opposite && route.standId().equals(stand.id()) && !route.runwayId().equals(runway.id())) alternatives.add(route);
                for (AirportTaxiRoute other:alternatives) {
                    AirportTaxiRoute out=role==AirportTaxiRoute.Direction.OUTBOUND?active:other;
                    AirportTaxiRoute in=role==AirportTaxiRoute.Direction.INBOUND?active:other;
                    Candidate candidate=candidate(layout,facilities,new GroundAssignment(layout.revision(),runway.id(),stand.id(),out.id(),in.id()));
                    if (candidate!=null) result.add(candidate);
                }
            }
        }
        return result;
    }
    private static Candidate candidate(AirportGroundLayout layout,List<AirportFacility> facilities,GroundAssignment assignment) {
        AirportParkingStand stand=layout.stand(assignment.standId()).orElse(null);
        AirportFacility selected=facility(facilities,assignment.runwayId());
        AirportTaxiRoute out=layout.taxiRoutes().stream().filter(r->r.id().equals(assignment.outboundRouteId())).findFirst().orElse(null);
        AirportTaxiRoute in=layout.taxiRoutes().stream().filter(r->r.id().equals(assignment.inboundRouteId())).findFirst().orElse(null);
        if (stand==null || selected==null || out==null || in==null) return null;
        for (AirportTaxiRoute route:List.of(out,in)) {
            AirportFacility runway=facility(facilities,route.runwayId());
            if (runway==null || !runway.supports(AircraftClass.FIXED_WING)
                    || !AirportGroundValidation.linkedEndpoint(route,facilities,layout.stands())) return null;
        }
        return new Candidate(assignment,stand,selected,out,in);
    }
    private static AirportFacility facility(List<AirportFacility> facilities,String id) {
        return facilities.stream().filter(f->f.id().equals(id)).findFirst().orElse(null);
    }
    private static boolean finite(double... values) { for (double value:values) if (!Double.isFinite(value)) return false; return true; }
    private static Result unavailable(String reason) { return new Result(Optional.empty(),reason); }
}
