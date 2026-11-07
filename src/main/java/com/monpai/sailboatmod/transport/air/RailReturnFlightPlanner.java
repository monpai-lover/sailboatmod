package com.monpai.sailboatmod.transport.air;

import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Builds the actual opposite airport procedures for fuel budgeting; return taxi routes may be much longer. */
public final class RailReturnFlightPlanner {
    public record Airport(AirportGroundLayout layout,List<AirportFacility> facilities,GroundAssignment assignment) {
        public Airport {Objects.requireNonNull(layout);Objects.requireNonNull(assignment);facilities=List.copyOf(facilities);}
    }
    private RailReturnFlightPlanner() { }
    public static RailAircraftPath build(RailAircraftPath outbound,Airport source,Airport destination,List<Vec3> returnWaypoints,double speed,double returnSweepDiameter) {
        if(outbound==null||source==null||destination==null||!Double.isFinite(returnSweepDiameter)||returnSweepDiameter<=0)throw invalid();
        var departure=destination.assignment().forDirection(destination.layout(),destination.facilities(),AirportTaxiRoute.Direction.OUTBOUND).orElseThrow(RailReturnFlightPlanner::invalid);
        var arrival=source.assignment().forDirection(source.layout(),source.facilities(),AirportTaxiRoute.Direction.INBOUND).orElseThrow(RailReturnFlightPlanner::invalid);
        var start=destination.layout().stand(departure.standId()).orElseThrow(RailReturnFlightPlanner::invalid);
        var finish=source.layout().stand(arrival.standId()).orElseThrow(RailReturnFlightPlanner::invalid);
        var taxiOut=route(destination,departure.outboundRouteId());var taxiIn=route(source,arrival.inboundRouteId());
        Vec3 anchor=outbound.sample(outbound.length()).position();double height=anchor.y-start.position().y;
        double yaw=Math.toRadians(start.yawDegrees());Vec3 forward=new Vec3(-Math.sin(yaw),0,Math.cos(yaw));
        return RailFlightPlanBuilder.build(facility(destination,departure.runwayId()),facility(source,arrival.runwayId()),taxiOut,taxiIn,finish,
                height,returnSweepDiameter,returnWaypoints,speed,start,anchor,forward);
    }
    private static AirportTaxiRoute route(Airport airport,String id){return airport.layout().taxiRoutes().stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(RailReturnFlightPlanner::invalid);}
    private static AirportFacility facility(Airport airport,String id){return airport.facilities().stream().filter(f->f.id().equals(id)).findFirst().orElseThrow(RailReturnFlightPlanner::invalid);}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("fuel_return_plan_unavailable");}
}
