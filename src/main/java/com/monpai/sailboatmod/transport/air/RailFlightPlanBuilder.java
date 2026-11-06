package com.monpai.sailboatmod.transport.air;

import com.monpai.sailboatmod.transport.air.airport.*;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import com.monpai.sailboatmod.transport.air.rail.RailStandDepartureConnector;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;
import static com.monpai.sailboatmod.transport.air.rail.RailAircraftPath.Phase.*;

/** Airport procedures for a real aircraft following a visual route rather than aerodynamic controls. */
public final class RailFlightPlanBuilder {
    private RailFlightPlanBuilder() { }

    /** Start from the actual parked body, admitting only the connector's proved, unchanged compiled path. */
    public static RailAircraftPath build(AirportFacility source,AirportFacility target,
            AirportTaxiRoute outbound,AirportTaxiRoute inbound,AirportParkingStand stand,
            double anchorAboveSurface,double sweepDiameter,List<Vec3> authored,double speed,
            AirportParkingStand sourceStand,Vec3 actualAnchor,Vec3 actualForward) {
        RailAircraftPath base=build(source,target,outbound,inbound,stand,anchorAboveSurface,sweepDiameter,authored,speed);
        if(sourceStand==null||!sourceStand.validGeometry()||!sourceStand.cargoEnabled()||!sourceStand.id().equals(outbound.standId())
                ||outbound.points().getFirst().distanceTo(sourceStand.position())>1
                ||Math.abs(outbound.points().getFirst().y-sourceStand.position().y)>1e-8)throw invalid("rail_invalid_airport_geometry");
        if(actualAnchor==null||actualForward==null||!finite(actualAnchor.x,actualAnchor.y,actualAnchor.z,actualForward.x,actualForward.y,actualForward.z)
                ||Math.abs(actualAnchor.y-base.sample(0).position().y)>1e-8||actualForward.horizontalDistanceSqr()<1e-12)throw invalid("rail_start_alignment_required");
        Vec3 horizontalForward=new Vec3(actualForward.x,0,actualForward.z).normalize();
        if(actualAnchor.distanceTo(base.sample(0).position())<=1e-8&&horizontalForward.dot(base.sample(0).tangent())>1-1e-10)return base;
        if(base.legs().getFirst().phase()!=TAXI_OUT)throw invalid("rail_start_alignment_required");
        double radius=base.legs().getFirst().cornerRadius();
        var connected=RailStandDepartureConnector.connect(sourceStand,actualAnchor,actualForward,outbound,sweepDiameter/2,radius,connection->{
            List<RailAircraftPath.Leg> legs=new ArrayList<>();
            add(legs,TAXI_OUT,connection.prefixPoints(),outbound.speedLimitMetersPerSecond(),radius,false);
            add(legs,TAXI_OUT,connection.remainingOutboundPoints().subList(0,connection.holdPointIndex()+1),outbound.speedLimitMetersPerSecond(),radius,false);
            legs.addAll(base.legs().subList(1,base.legs().size()));
            return compile(legs,stand.yawDegrees(),base.rotationRadius());
        });
        return connected.path().orElseThrow(()->invalid(connected.reason()));
    }

    public static RailAircraftPath build(AirportFacility source, AirportFacility target,
            AirportTaxiRoute outbound, AirportTaxiRoute inbound, AirportParkingStand stand,
            double anchorAboveSurface, double sweepDiameter, List<Vec3> authored, double speed) {
        if(outbound==null||outbound.points().size()<2)throw invalid("rail_invalid_airport_geometry");
        return build(source,target,outbound,inbound,stand,anchorAboveSurface,sweepDiameter,authored,speed,
                outbound.points().getFirst().add(0,anchorAboveSurface,0),outbound.points().get(1).subtract(outbound.points().getFirst()).normalize());
    }

    /** Existing ground geometry is used only after an actual anchor/forward readback proves alignment. */
    public static RailAircraftPath build(AirportFacility source, AirportFacility target,
            AirportTaxiRoute outbound, AirportTaxiRoute inbound, AirportParkingStand stand,
            double anchorAboveSurface, double sweepDiameter, List<Vec3> authored, double speed,Vec3 actualStart,Vec3 actualForward) {
        if(source==null||target==null||outbound==null||inbound==null||stand==null
                ||!source.supports(AircraftClass.FIXED_WING)||!target.supports(AircraftClass.FIXED_WING)
                ||!outbound.validGeometry()||!inbound.validGeometry()||!stand.validGeometry()
                ||outbound.direction()!=AirportTaxiRoute.Direction.OUTBOUND||inbound.direction()!=AirportTaxiRoute.Direction.INBOUND
                ||!source.id().equals(outbound.runwayId())||!target.id().equals(inbound.runwayId())||!stand.id().equals(inbound.standId())
                ||!finite(anchorAboveSurface,sweepDiameter,speed)||anchorAboveSurface<0||anchorAboveSurface>64
                ||sweepDiameter<=0||speed<1||speed>60||authored==null||authored.size()<2||authored.size()>58
                ||authored.stream().anyMatch(p->p==null||!finite(p.x,p.y,p.z)))throw invalid("rail_invalid_airport_geometry");
        double corridor=(Math.min(Math.min(outbound.width(),inbound.width()),Math.min(source.width(),target.width()))-sweepDiameter)/2;
        if(corridor<.5)throw invalid("facility_too_narrow");
        Vec3 from=surface(source.start(),anchorAboveSurface),fromEnd=surface(source.end(),anchorAboveSurface);
        Vec3 to=surface(target.start(),anchorAboveSurface),toEnd=surface(target.end(),anchorAboveSurface);
        List<Vec3> out=offset(outbound.points(),anchorAboveSurface),in=offset(inbound.points(),anchorAboveSurface);
        Vec3 initialDirection=out.get(1).subtract(out.getFirst()).normalize();
        if(actualStart==null||actualForward==null||!finite(actualStart.x,actualStart.y,actualStart.z,actualForward.x,actualForward.y,actualForward.z)
                ||actualStart.distanceTo(out.getFirst())>.05||actualForward.lengthSqr()<1e-12
                ||actualForward.normalize().dot(initialDirection)<Math.cos(Math.toRadians(10)))throw invalid("rail_start_alignment_required");
        Vec3 parked=stand.position().add(0,anchorAboveSurface,0);
        if(out.getLast().distanceTo(from)>.75||in.getFirst().distanceTo(toEnd)>.75
                ||in.getLast().distanceTo(parked)>.75)throw invalid("ground_route_runway_join");
        double finalYaw=Math.toRadians(stand.yawDegrees());Vec3 parkingForward=new Vec3(-Math.sin(finalYaw),0,Math.cos(finalYaw));
        Vec3 lastDirection=in.getLast().subtract(in.get(in.size()-2)).normalize();
        if(lastDirection.dot(parkingForward)<Math.cos(Math.toRadians(10)))throw invalid("rail_stand_entry_heading");
        // The configured route must meet the exact runway/stand; no unvalidated ground connector is invented.
        out=new ArrayList<>(out);out.set(out.size()-1,from);in=new ArrayList<>(in);in.set(0,toEnd);in.set(in.size()-1,parked);
        Vec3 departureAxis=fromEnd.subtract(from).normalize(),arrivalAxis=toEnd.subtract(to).normalize();
        double height=Math.max(from.y,to.y)+48;
        for(int i=1;i<authored.size()-1;i++)height=Math.max(height,authored.get(i).y);
        if(height>2040||height+sweepDiameter/2>2047)throw invalid("flight_outside_height");
        // Cubic height easing has a maximum slope 1.5 times the endpoint chord slope.
        double climbLength=Math.max(128,2*(height-fromEnd.y)/Math.tan(Math.toRadians(12)));
        double approachLength=Math.max(128,2*(height-to.y)/Math.tan(Math.toRadians(12)));
        Vec3 climbEnd=fromEnd.add(departureAxis.scale(climbLength));climbEnd=new Vec3(climbEnd.x,height,climbEnd.z);
        Vec3 approachStart=to.subtract(arrivalAxis.scale(approachLength));approachStart=new Vec3(approachStart.x,height,approachStart.z);
        double turnRadius=Math.max(48,speed*speed/(9.81*Math.tan(Math.toRadians(25))));
        List<Vec3> air=new ArrayList<>();List<Integer> airBreaks=new ArrayList<>();air.add(climbEnd);Vec3 previous=climbEnd,heading=departureAxis;
        List<Vec3> through=new ArrayList<>();
        for(int i=1;i<authored.size()-1;i++) {
            Vec3 p=authored.get(i);
            if(p.subtract(authored.getFirst()).horizontalDistanceSqr()<1||p.subtract(authored.getLast()).horizontalDistanceSqr()<1)continue;
            Vec3 candidate=new Vec3(p.x,height,p.z);
            if((through.isEmpty()?climbEnd:through.getLast()).distanceTo(candidate)>1)through.add(candidate);
        }
        if(!through.isEmpty()&&through.getLast().distanceTo(approachStart)<=1)through.set(through.size()-1,approachStart);
        else through.add(approachStart);
        for(int i=0;i<through.size();i++) {
            Vec3 goal=through.get(i),goalHeading=arrivalAxis;
            if(i+1<through.size()) {
                Vec3 incoming=goal.subtract(previous),outgoing=through.get(i+1).subtract(goal);
                Vec3 bisector=incoming.normalize().add(outgoing.normalize());
                goalHeading=bisector.horizontalDistanceSqr()>1e-8?bisector.normalize():outgoing.normalize();
            }
            append(air,connect(previous,heading,goal,goalHeading,turnRadius));airBreaks.add(air.size()-1);previous=goal;heading=goalHeading;
        }
        List<RailAircraftPath.Leg> legs=new ArrayList<>();int hold=outbound.holdPointIndex();
        add(legs,TAXI_OUT,out.subList(0,hold+1),outbound.speedLimitMetersPerSecond(),corridor,false);
        legs.add(new RailAircraftPath.Leg(HOLD_SHORT,List.of(out.get(hold)),0,0,false));
        add(legs,LINE_UP,out.subList(hold,out.size()),outbound.speedLimitMetersPerSecond(),corridor,false);
        add(legs,TAKEOFF,List.of(from,fromEnd),speed,0,false);
        add(legs,CLIMB,List.of(fromEnd,climbEnd),speed,0,true);
        addCruise(legs,air,airBreaks,speed,turnRadius);
        add(legs,APPROACH,List.of(approachStart,to),speed,0,true);
        add(legs,LANDING_ROLL,List.of(to,toEnd),speed,0,false);
        add(legs,TAXI_IN,in,inbound.speedLimitMetersPerSecond(),corridor,false);
        legs.add(new RailAircraftPath.Leg(STAND_STABILIZING,List.of(parked),0,0,false));
        return compile(legs,stand.yawDegrees(),sweepDiameter/2);
    }
    private static RailAircraftPath compile(List<RailAircraftPath.Leg> legs,double yaw,double rotationRadius) {
        try {return RailAircraftPath.compile(legs,yaw,rotationRadius);}
        catch(IllegalArgumentException invalid) {
            if("path_too_large".equals(invalid.getMessage()))throw invalid("rail_route_too_complex");
            throw invalid;
        }
    }

    private static void add(List<RailAircraftPath.Leg> legs,RailAircraftPath.Phase phase,List<Vec3> points,double speed,double radius,boolean bank) {
        if(points.size()>1)legs.add(new RailAircraftPath.Leg(phase,points,speed,radius,bank));
    }
    private static void addCruise(List<RailAircraftPath.Leg> legs,List<Vec3> points,List<Integer> connections,double speed,double radius) {
        int start=0;
        while(start<points.size()-1) {
            int end=start;for(int boundary:connections)if(boundary>start&&boundary-start<4096)end=boundary;
            if(end==start)throw invalid("rail_air_connection_too_large");
            add(legs,CRUISE,points.subList(start,end+1),speed,radius,true);start=end;
        }
    }
    private static Vec3 surface(net.minecraft.core.BlockPos p,double offset){return new Vec3(p.getX()+.5,p.getY()+offset,p.getZ()+.5);}
    private static List<Vec3> offset(List<Vec3> p,double y){return p.stream().map(v->v.add(0,y,0)).toList();}
    private static boolean finite(double... values){for(double v:values)if(!Double.isFinite(v))return false;return true;}
    private static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
    private static void append(List<Vec3> result,List<Vec3> points){for(Vec3 p:points)if(result.isEmpty()||result.getLast().distanceTo(p)>1e-7)result.add(p);}

    /** Two equal-radius tangent arcs, with exact start/end headings; choose the shorter left/left or right/right path. */
    private static List<Vec3> connect(Vec3 start,Vec3 forward,Vec3 goal,Vec3 endForward,double radius) {
        Vec3 direction=goal.subtract(start);
        if((direction.horizontalDistanceSqr()<1e-8||direction.normalize().dot(forward)>1-1e-8)
                &&forward.dot(endForward)>1-1e-8)return List.of(start,goal);
        // Keep exact tangent straights at procedure/connection boundaries. Without these, the
        // first discretized arc chord and the neighboring climb form a small horizontal S bend.
        double lead=Math.max(10,radius*.2);List<Vec3> result=new ArrayList<>();result.add(start);
        append(result,connectArcs(start.add(forward.scale(lead)),forward,goal.subtract(endForward.scale(lead)),endForward,radius));
        append(result,List.of(goal));return List.copyOf(result);
    }
    private static List<Vec3> connectArcs(Vec3 start,Vec3 forward,Vec3 goal,Vec3 endForward,double radius) {
        List<Vec3> best=null;double bestLength=Double.POSITIVE_INFINITY;
        for(int sign:new int[]{1,-1}) {
            Vec3 c0=start.add(-sign*radius*forward.z,0,sign*radius*forward.x);
            Vec3 c1=goal.add(-sign*radius*endForward.z,0,sign*radius*endForward.x);
            Vec3 delta=c1.subtract(c0);double length=delta.horizontalDistance();if(length<1e-7)continue;
            Vec3 tangent=delta.scale(1/length),radial=new Vec3(sign*radius*tangent.z,0,-sign*radius*tangent.x);
            Vec3 a=c0.add(radial),b=c1.add(radial);
            double begin=Math.atan2(start.z-c0.z,start.x-c0.x),middle=Math.atan2(radial.z,radial.x),end=Math.atan2(goal.z-c1.z,goal.x-c1.x);
            double sweep0=positive(sign*(middle-begin)),sweep1=positive(sign*(end-middle));
            double total=radius*(sweep0+sweep1)+length;if(total>=bestLength)continue;
            List<Vec3> candidate=new ArrayList<>();candidate.add(start);arc(candidate,c0,radius,begin,sweep0,sign,a);append(candidate,List.of(b));arc(candidate,c1,radius,middle,sweep1,sign,goal);
            best=candidate;bestLength=total;
        }
        if(best==null)throw invalid("rail_air_heading_connection");return List.copyOf(best);
    }
    private static void arc(List<Vec3> points,Vec3 center,double radius,double begin,double sweep,int sign,Vec3 exactEnd) {
        int steps=Math.max(1,(int)Math.ceil(sweep/Math.toRadians(5)));
        for(int i=1;i<=steps;i++) {
            double angle=begin+sign*sweep*i/steps;
            append(points,List.of(i==steps?exactEnd:new Vec3(center.x+radius*Math.cos(angle),center.y,center.z+radius*Math.sin(angle))));
        }
    }
    private static double positive(double angle){double value=angle%(2*Math.PI);return value<0?value+2*Math.PI:value;}
}
