package com.monpai.sailboatmod.transport.air.airport;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Bounded conservative swept-circle corridors. Unknown chunks are checked before all terrain reads. */
public final class AirportGroundValidation {
    public enum Result {VALID,WAITING,INVALID_GEOMETRY,MISSING_REFERENCE,DISCONNECTED_ROUTE,TOO_LARGE,UNLOADED,OBSTRUCTED,UNSUPPORTED}
    public static final int MAX_CELLS=1048576,MAX_BUILD_STEPS=2097152;
    public static final int MAX_SCAN_STEPS=MAX_BUILD_STEPS+2*MAX_CELLS+1;
    public static final int ORDINARY_RADIUS=512, LINKED_MARGIN=96, LINKED_PERMISSION_SPAN=3136;
    private AirportGroundValidation(){}
    public static boolean linkedEndpoint(AirportTaxiRoute route,List<AirportFacility> facilities,List<AirportParkingStand> stands){
        if(route==null||!route.validGeometry())return false;
        var runway=facilities.stream().filter(f->f.type()==AirportFacility.Type.RUNWAY&&f.validGeometry()&&f.id().equals(route.runwayId())).findFirst().orElse(null);
        var stand=stands.stream().filter(s->s.id().equals(route.standId())&&s.validGeometry()).findFirst().orElse(null);
        if(runway==null||stand==null)return false;
        Vec3 standEnd=route.direction()==AirportTaxiRoute.Direction.OUTBOUND?route.points().getFirst():route.points().getLast();
        Vec3 runwayEnd=route.direction()==AirportTaxiRoute.Direction.OUTBOUND?route.points().getLast():route.points().getFirst();
        BlockPos expected=route.direction()==AirportTaxiRoute.Direction.OUTBOUND?runway.start():runway.end();
        return standEnd.distanceTo(stand.position())<=1&&Math.abs(standEnd.y-stand.position().y)<1e-7
                &&Math.abs(runwayEnd.y-expected.getY())<1e-7&&runwayEnd.subtract(Vec3.atBottomCenterOf(expected)).horizontalDistanceSqr()<=.75*.75;
    }
    public static boolean withinManagementDistance(AirportTaxiRoute route,List<AirportFacility> facilities,List<AirportParkingStand> stands,BlockPos airport){
        if(route==null||airport==null||!route.validGeometry())return false;
        Vec3 center=airport.getCenter();double radiusSquared=(double)ORDINARY_RADIUS*ORDINARY_RADIUS;
        if(route.length()<=AirportTaxiRoute.ORDINARY_MAX_LENGTH&&route.points().stream().allMatch(p->p.distanceToSqr(center)<=radiusSquared))return true;
        if(!linkedEndpoint(route,facilities,stands))return false;
        var stand=stands.stream().filter(s->s.id().equals(route.standId())).findFirst().orElseThrow();
        if(stand.position().distanceToSqr(center)>radiusSquared)return false;
        var runway=facilities.stream().filter(f->f.id().equals(route.runwayId())).findFirst().orElseThrow();
        for(int i=1;i<route.points().size();i++){
            Vec3 a=route.points().get(i-1),b=route.points().get(i);
            if(a.distanceToSqr(center)<=radiusSquared&&b.distanceToSqr(center)<=radiusSquared)continue;
            // Each allowed domain is convex. Cross-domain paths need an explicit waypoint
            // in their overlap, so no unexamined remote part cuts outside the runway corridor.
            if(!runway.containsHorizontal(a.x,a.z,-LINKED_MARGIN)||!runway.containsHorizontal(b.x,b.z,-LINKED_MARGIN))return false;
        }
        return true;
    }
    public static Result references(AirportGroundLayout layout,List<AirportFacility> facilities){
        if(layout==null||facilities==null)return Result.INVALID_GEOMETRY;
        for(var route:layout.taxiRoutes()){
            var stand=layout.stand(route.standId()).orElse(null);
            var runway=facilities.stream().filter(f->f.id().equals(route.runwayId())&&f.type()==AirportFacility.Type.RUNWAY&&f.validGeometry()).findFirst().orElse(null);
            if(stand==null||runway==null)return Result.MISSING_REFERENCE;
            Vec3 standEnd=route.direction()==AirportTaxiRoute.Direction.OUTBOUND?route.points().getFirst():route.points().getLast();
            Vec3 runwayEnd=route.direction()==AirportTaxiRoute.Direction.OUTBOUND?route.points().getLast():route.points().getFirst();
            if(Math.abs(standEnd.y-stand.position().y)>1e-7 || standEnd.distanceTo(stand.position())>1 || Math.abs(runwayEnd.y-runway.start().getY())>1e-7 || !runway.containsHorizontal(runwayEnd.x,runwayEnd.z,0))return Result.DISCONNECTED_ROUTE;
            if(route.length()>AirportTaxiRoute.ORDINARY_MAX_LENGTH&&!linkedEndpoint(route,facilities,layout.stands()))return Result.DISCONNECTED_ROUTE;
            if(route.direction()==AirportTaxiRoute.Direction.OUTBOUND&&runway.bounds().inflate(route.width()/2,0,route.width()/2).contains(route.points().get(route.holdPointIndex())))return Result.DISCONNECTED_ROUTE;
        }
        return Result.VALID;
    }
    public static Result validate(AirportGroundLayout layout,List<AirportFacility> facilities,Predicate<BlockPos> loaded,Predicate<BlockPos> clear,Predicate<BlockPos> supported){
        Scan scan=new Scan(layout,facilities);return scan.advance(MAX_SCAN_STEPS,loaded,clear,supported);
    }
    /** Explicit transient ingress corridor; shares the same incremental generation and two terrain passes. */
    public static Scan ingressScan(Vec3 start,Vec3 end,double width,int height) { return new Scan(start,end,width,height); }
    static double segmentDistance(double x,double z,Vec3 a,Vec3 b){double dx=b.x-a.x,dz=b.z-a.z;double t=Math.max(0,Math.min(1,((x-a.x)*dx+(z-a.z)*dz)/(dx*dx+dz*dz)));return Math.hypot(x-a.x-t*dx,z-a.z-t*dz);}
    public static final class Scan {
        private record Cell(BlockPos pos,boolean support){}
        private record Area(AABB bounds,Vec3 start,Vec3 end,double radius){}
        private final List<Cell> cells=new ArrayList<>();private final Set<Cell> seen=new HashSet<>();
        private final List<Area> areas=new ArrayList<>();
        private Result result;private int cursor,phase,steps,buildSteps;
        private int areaIndex,x,z,y,minX,maxX,minZ,maxZ,minY,maxY;
        private boolean building=true,areaReady,columnReady;
        public Scan(AirportGroundLayout layout,List<AirportFacility> facilities){
            result=references(layout,facilities);if(result!=Result.VALID)return;result=Result.WAITING;
            for(var stand:layout.stands())areas.add(new Area(stand.bounds(),null,null,0));
            for(var route:layout.taxiRoutes())for(int i=1;i<route.points().size();i++){
                Vec3 a=route.points().get(i-1),b=route.points().get(i);double radius=route.width()/2;
                areas.add(new Area(new AABB(Math.min(a.x,b.x)-radius,a.y,Math.min(a.z,b.z)-radius,Math.max(a.x,b.x)+radius,a.y+route.clearanceHeight(),Math.max(a.z,b.z)+radius),a,b,radius));
            }
        }
        private Scan(Vec3 a,Vec3 b,double width,int height){
            result=Result.INVALID_GEOMETRY;
            if(a==null||b==null||!Double.isFinite(a.x)||!Double.isFinite(a.y)||!Double.isFinite(a.z)
                    ||!Double.isFinite(b.x)||!Double.isFinite(b.y)||!Double.isFinite(b.z)
                    ||a.y!=b.y||!Double.isFinite(width)||width<2||width>64||height<4||height>64)return;
            double length=Math.hypot(b.x-a.x,b.z-a.z);
            if(!Double.isFinite(length)||length<.1||length>256)return;
            double radius=width/2;
            areas.add(new Area(new AABB(Math.min(a.x,b.x)-radius,a.y,Math.min(a.z,b.z)-radius,
                    Math.max(a.x,b.x)+radius,a.y+height,Math.max(a.z,b.z)+radius),a,b,radius));
            result=Result.WAITING;
        }
        private void buildStep(){
            if(++buildSteps>MAX_BUILD_STEPS){result=Result.TOO_LARGE;return;}
            if(areaIndex>=areas.size()){building=false;if(cells.isEmpty())result=Result.VALID;return;}
            Area area=areas.get(areaIndex);
            if(!areaReady){
                minX=x=(int)Math.floor(area.bounds.minX);maxX=(int)Math.ceil(area.bounds.maxX)-1;
                minZ=z=(int)Math.floor(area.bounds.minZ);maxZ=(int)Math.ceil(area.bounds.maxZ)-1;
                minY=(int)area.bounds.minY-1;maxY=(int)area.bounds.maxY-1;areaReady=true;return;
            }
            if(!columnReady){
                if(area.start!=null&&segmentDistance(x+.5,z+.5,area.start,area.end)>area.radius+Math.sqrt(.5)){nextColumn();return;}
                y=minY;columnReady=true;return;
            }
            // Preserve distinct support and clear requirements even at the same position.
            Cell cell=new Cell(new BlockPos(x,y,z),y==minY);
            if(seen.add(cell)){if(cells.size()>=MAX_CELLS){result=Result.TOO_LARGE;return;}cells.add(cell);}
            if(++y>maxY)nextColumn();
        }
        private void nextColumn(){
            columnReady=false;
            if(++z>maxZ){z=minZ;if(++x>maxX){areaIndex++;areaReady=false;}}
        }
        public int stepsUsed(){return steps;}
        public Result advance(int budget,Predicate<BlockPos> loaded,Predicate<BlockPos> clear,Predicate<BlockPos> supported){
            steps=0;if(loaded==null||clear==null||supported==null)return result=Result.INVALID_GEOMETRY;
            while(result==Result.WAITING&&steps<Math.max(0,budget)){
                if(building){steps++;buildStep();continue;}
                Cell cell=cells.get(cursor);steps++;
                if(!loaded.test(cell.pos))return result=Result.UNLOADED;
                if(phase==1){if(cell.support&&!supported.test(cell.pos))return result=Result.UNSUPPORTED;if(!cell.support&&!clear.test(cell.pos))return result=Result.OBSTRUCTED;}
                if(++cursor==cells.size()){cursor=0;if(++phase==2)result=Result.VALID;}
            }
            return result;
        }
    }
}
