package com.monpai.sailboatmod.transport.air.airport;

import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Directed ground corridor. Every orientation of a hull must fit its width, including at bends. */
public record AirportTaxiRoute(String id, Direction direction, String runwayId, String standId, List<Vec3> points,
                               double width, int clearanceHeight, double speedLimitMetersPerSecond, int holdPointIndex) {
    public enum Direction { OUTBOUND, INBOUND }
    public static final int MAX_POINTS=64;
    public static final int ORDINARY_MAX_LENGTH=1024, MAX_LINKED_LENGTH=3072;
    public AirportTaxiRoute {
        if (!AirportGroundLayout.validId(id) || !AirportGroundLayout.validId(runwayId) || !AirportGroundLayout.validId(standId))
            throw new IllegalArgumentException("Invalid route id");
        Objects.requireNonNull(direction); points=List.copyOf(points);
        if (points.size()>MAX_POINTS) throw new IllegalArgumentException("Too many taxi points");
    }
    public boolean validGeometry() {
        if (points.size()<2 || !Double.isFinite(width) || width<2 || width>64 || clearanceHeight<4 || clearanceHeight>64
                || !Double.isFinite(speedLimitMetersPerSecond) || speedLimitMetersPerSecond<=0 || speedLimitMetersPerSecond>5) return false;
        if (direction==Direction.OUTBOUND ? holdPointIndex<0 || holdPointIndex>=points.size()-1 : holdPointIndex!=-1) return false;
        double total=0;
        for (int i=0;i<points.size();i++) {
            Vec3 point=points.get(i);
            if (!AirportGroundLayout.validPoint(point) || Math.abs(point.y-points.getFirst().y)>1e-7) return false;
            if (i>0) { double segment=point.distanceTo(points.get(i-1)); if (segment<.1 || segment>256) return false; total+=segment; }
        }
        return total<=MAX_LINKED_LENGTH;
    }
    public double length(){double total=0;for(int i=1;i<points.size();i++)total+=points.get(i).distanceTo(points.get(i-1));return total;}
    /** Conservative circumscribed circle covers body sweep at any heading. */
    public boolean fits(AABB hull) {
        return validGeometry() && AirportGroundLayout.finiteHull(hull) && Math.hypot(hull.getXsize(),hull.getZsize())<=width
                && hull.getYsize()<=clearanceHeight;
    }
    public AABB bounds() {
        double minX=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,minZ=minX,maxZ=maxX;
        for (Vec3 point:points) {minX=Math.min(minX,point.x);maxX=Math.max(maxX,point.x);minZ=Math.min(minZ,point.z);maxZ=Math.max(maxZ,point.z);}
        double y=points.isEmpty()?0:points.getFirst().y;
        return new AABB(minX-width/2,y,minZ-width/2,maxX+width/2,y+clearanceHeight,maxZ+width/2);
    }
    public CompoundTag save() {
        CompoundTag tag=new CompoundTag(); tag.putString("Id",id);tag.putString("Direction",direction.name());
        tag.putString("RunwayId",runwayId);tag.putString("StandId",standId);tag.putDouble("Width",width);
        tag.putInt("ClearanceHeight",clearanceHeight);tag.putDouble("SpeedLimit",speedLimitMetersPerSecond);tag.putInt("HoldPointIndex",holdPointIndex);
        ListTag list=new ListTag();for(Vec3 point:points){CompoundTag item=new CompoundTag();AirportGroundLayout.putPoint(item,point);list.add(item);}tag.put("Points",list);return tag;
    }
    public static AirportTaxiRoute load(CompoundTag tag) {
        ListTag list=tag.getList("Points",Tag.TAG_COMPOUND);if(list.size()>MAX_POINTS)throw new IllegalArgumentException("Too many taxi points");
        List<Vec3> points=new ArrayList<>();for(Tag raw:list)points.add(AirportGroundLayout.getPoint((CompoundTag)raw));
        AirportTaxiRoute value=new AirportTaxiRoute(tag.getString("Id"),Direction.valueOf(tag.getString("Direction")),tag.getString("RunwayId"),tag.getString("StandId"),
                points,tag.getDouble("Width"),tag.getInt("ClearanceHeight"),tag.getDouble("SpeedLimit"),tag.getInt("HoldPointIndex"));
        if(!value.validGeometry())throw new IllegalArgumentException("Invalid taxi geometry");return value;
    }
}
