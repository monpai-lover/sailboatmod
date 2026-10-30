package com.monpai.sailboatmod.transport.air;

import com.monpai.sailboatmod.transport.air.airport.AirportFacility;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Builds explicit 3D procedures; Flight Control supplies calibrated aircraft dynamics. */
public final class FlightRoutePlanner {
    private FlightRoutePlanner() { }
    /** Bounds landing around FC's logical origin, even when that origin is off-center or yaw changes. */
    public static double requiredHorizontalDiameter(AABB hull, Vec3 reference) {
        if (hull == null || reference == null || !Double.isFinite(reference.x) || !Double.isFinite(reference.z)
                || !Double.isFinite(hull.minX) || !Double.isFinite(hull.maxX)
                || !Double.isFinite(hull.minZ) || !Double.isFinite(hull.maxZ))
            throw new IllegalArgumentException("invalid_aircraft_dimensions");
        double x = Math.max(Math.abs(hull.minX-reference.x), Math.abs(hull.maxX-reference.x));
        double z = Math.max(Math.abs(hull.minZ-reference.z), Math.abs(hull.maxZ-reference.z));
        return 2*Math.hypot(x,z);
    }
    public static List<Vec3> cruiseForLeg(List<Vec3> authored, boolean returning) {
        List<Vec3> result=new ArrayList<>(authored);
        if(returning) Collections.reverse(result);
        return List.copyOf(result);
    }
    public static List<Vec3> plan(AircraftClass type, AirportFacility source, AirportFacility target,
                                 List<Vec3> cruise, double referenceAboveHull, double aircraftWidth, double speed) {
        if (type==null || source==null || target==null || !source.supports(type) || !target.supports(type))
            throw new IllegalArgumentException("incompatible_airport");
        if (!Double.isFinite(referenceAboveHull) || referenceAboveHull<0 || referenceAboveHull>64
                || !Double.isFinite(aircraftWidth) || aircraftWidth<=0 || !Double.isFinite(speed) || speed<=0)
            throw new IllegalArgumentException("invalid_aircraft_dimensions");
        if (aircraftWidth+2>source.width() || aircraftWidth+2>target.width()) throw new IllegalArgumentException("facility_too_narrow");
        if (cruise==null || cruise.size()<2 || cruise.size()>58 || cruise.stream().anyMatch(p->p==null || !Double.isFinite(p.x) || !Double.isFinite(p.y) || !Double.isFinite(p.z)))
            throw new IllegalArgumentException("invalid_flight_route");
        double height=cruise.stream().mapToDouble(p->p.y).max().orElse(0);
        height=Math.max(height,Math.max(source.start().getY(),target.start().getY())+Math.max(32,referenceAboveHull+16));
        List<Vec3> points=new ArrayList<>();
        if(type==AircraftClass.FIXED_WING) {
            if(source.length()<Math.max(32,speed*6) || target.length()<Math.max(32,speed*6)) throw new IllegalArgumentException("runway_too_short");
            Vec3 end=surface(source.end(),referenceAboveHull);
            Vec3 axis=end.subtract(surface(source.start(),referenceAboveHull)).normalize();
            points.add(end.add(axis.scale(Math.max(32,speed*4))).add(0,Math.max(16,height-end.y),0));
        } else points.add(source.landingPosition().add(0,height-source.start().getY(),0));
        // Preserve authored intermediate route points; endpoint procedures belong to the selected facility.
        for(int i=1;i<cruise.size()-1;i++) {
            Vec3 point=cruise.get(i), from=cruise.getFirst(), to=cruise.getLast();
            // Airport commands include vertical endpoint legs. Replace those with aircraft procedures.
            if (Math.hypot(point.x-from.x,point.z-from.z)<1 || Math.hypot(point.x-to.x,point.z-to.z)<1) continue;
            points.add(point);
        }
        if(type==AircraftClass.FIXED_WING) {
            Vec3 touchdown=surface(target.start(),referenceAboveHull), stop=surface(target.end(),referenceAboveHull);
            Vec3 axis=stop.subtract(touchdown).normalize();
            points.add(touchdown.subtract(axis.scale(Math.max(64,speed*8))).add(0,height-touchdown.y,0));
            points.add(touchdown); points.add(stop);
        } else {
            Vec3 landing=target.landingPosition().add(0,referenceAboveHull,0);
            points.add(new Vec3(landing.x,height,landing.z)); points.add(landing);
        }
        return List.copyOf(points);
    }
    private static Vec3 surface(net.minecraft.core.BlockPos p,double offset) { return new Vec3(p.getX()+.5,p.getY()+offset,p.getZ()+.5); }
}
