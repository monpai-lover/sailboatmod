package com.monpai.sailboatmod.transport.air.airport;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Immutable versioned editor state; an empty legacy layout never implies a stand or reverse path. */
public record AirportGroundLayout(long revision, List<AirportParkingStand> stands, List<AirportTaxiRoute> taxiRoutes) {
    public static final int MAX_STANDS=32,MAX_ROUTES=64;
    public AirportGroundLayout {
        if(revision<0 || revision==Long.MAX_VALUE)throw new IllegalArgumentException("Invalid layout revision");
        stands=List.copyOf(stands);taxiRoutes=List.copyOf(taxiRoutes);
        if(stands.size()>MAX_STANDS || taxiRoutes.size()>MAX_ROUTES)throw new IllegalArgumentException("Ground layout too large");
        Set<String> standIds=new HashSet<>(),routeIds=new HashSet<>(),connections=new HashSet<>();
        for(var stand:stands)if(!stand.validGeometry() || !standIds.add(stand.id()))throw new IllegalArgumentException("Invalid or duplicate stand");
        for(var route:taxiRoutes)if(!route.validGeometry() || !routeIds.add(route.id()) || !connections.add(route.direction()+"/"+route.standId()+"/"+route.runwayId()))
            throw new IllegalArgumentException("Invalid or duplicate taxi route");
    }
    public static AirportGroundLayout empty(){return new AirportGroundLayout(0,List.of(),List.of());}
    public Optional<AirportParkingStand> stand(String id){return stands.stream().filter(s->s.id().equals(id)).findFirst();}
    public Optional<AirportTaxiRoute> route(AirportTaxiRoute.Direction direction,String standId,String runwayId){return taxiRoutes.stream().filter(r->r.direction()==direction&&r.standId().equals(standId)&&r.runwayId().equals(runwayId)).findFirst();}
    public boolean departureReady(String standId,String runwayId){return stand(standId).filter(AirportParkingStand::cargoEnabled).isPresent()&&route(AirportTaxiRoute.Direction.OUTBOUND,standId,runwayId).isPresent();}
    public boolean arrivalReady(String standId,String runwayId){return stand(standId).filter(AirportParkingStand::cargoEnabled).isPresent()&&route(AirportTaxiRoute.Direction.INBOUND,standId,runwayId).isPresent();}
    public AirportGroundLayout withRevision(long value){return new AirportGroundLayout(value,stands,taxiRoutes);}
    public CompoundTag save(){CompoundTag tag=new CompoundTag();tag.putLong("Revision",revision);ListTag ss=new ListTag(),rr=new ListTag();for(var stand:stands)ss.add(stand.save());for(var route:taxiRoutes)rr.add(route.save());tag.put("Stands",ss);tag.put("TaxiRoutes",rr);return tag;}
    public static AirportGroundLayout load(CompoundTag tag){ListTag ss=tag.getList("Stands",Tag.TAG_COMPOUND),rr=tag.getList("TaxiRoutes",Tag.TAG_COMPOUND);if(ss.size()>MAX_STANDS||rr.size()>MAX_ROUTES)throw new IllegalArgumentException("Ground layout too large");List<AirportParkingStand> stands=new ArrayList<>();List<AirportTaxiRoute> routes=new ArrayList<>();for(Tag raw:ss)stands.add(AirportParkingStand.load((CompoundTag)raw));for(Tag raw:rr)routes.add(AirportTaxiRoute.load((CompoundTag)raw));return new AirportGroundLayout(tag.getLong("Revision"),stands,routes);}
    static boolean validId(String id){return id!=null&&id.matches("[a-zA-Z0-9_-]{1,48}");}
    static boolean validPoint(Vec3 point){return point!=null&&Double.isFinite(point.x)&&Double.isFinite(point.y)&&Double.isFinite(point.z)&&Math.abs(point.x)<=29_999_900&&Math.abs(point.z)<=29_999_900&&point.y>=-2048&&point.y<=2047&&Math.abs(point.y-Math.rint(point.y))<1e-7;}
    static boolean finiteHull(AABB hull){return hull!=null&&Double.isFinite(hull.minX)&&Double.isFinite(hull.maxX)&&Double.isFinite(hull.minY)&&Double.isFinite(hull.maxY)&&Double.isFinite(hull.minZ)&&Double.isFinite(hull.maxZ)&&hull.getXsize()>0&&hull.getYsize()>0&&hull.getZsize()>0;}
    public static double angleDifference(double first,double second){double delta=(first-second)%360;return Math.abs(delta>180?delta-360:delta< -180?delta+360:delta);}
    static void putPoint(CompoundTag tag,Vec3 p){tag.putDouble("X",p.x);tag.putDouble("Y",p.y);tag.putDouble("Z",p.z);}
    static Vec3 getPoint(CompoundTag tag){return new Vec3(tag.getDouble("X"),tag.getDouble("Y"),tag.getDouble("Z"));}
}
