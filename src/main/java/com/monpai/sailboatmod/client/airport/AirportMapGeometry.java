package com.monpai.sailboatmod.client.airport;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapView;
import com.monpai.sailboatmod.client.screen.ClaimMapViewport;
import com.monpai.sailboatmod.transport.air.airport.AirportScreenData;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Pure sub-block projection and pixel based airport editing geometry. */
public final class AirportMapGeometry {
    public enum Kind { RUNWAY,TAXI,STAND }
    public enum Handle { POINT,SEGMENT,BODY,HEADING }
    public record Hit(Kind kind,String id,Handle handle,int index) {}
    public record ScreenPoint(double x,double y) {public boolean finite(){return Double.isFinite(x)&&Double.isFinite(y);}}
    public record ScreenLine(ScreenPoint start,ScreenPoint end) {}
    public static final double POINT_TOLERANCE=7,SEGMENT_TOLERANCE=6;
    private AirportMapGeometry() {}
    public static ScreenPoint project(Vec3 point,RoadPlannerMapView view,ClaimMapViewport viewport) {
        return new ScreenPoint(viewport.x()+viewport.width()/2D+(point.x-view.centerX())*view.scale(),
                viewport.y()+viewport.height()/2D+(point.z-view.centerZ())*view.scale());
    }
    public static Optional<Vec3> unproject(double x,double y,double planeY,RoadPlannerMapView view,ClaimMapViewport viewport) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(planeY)||!viewport.contains(x,y)
                ||!Double.isFinite(view.scale())||view.scale()<=0)return Optional.empty();
        double worldX=view.centerX()+(x-(viewport.x()+viewport.width()/2D))/view.scale();
        double worldZ=view.centerZ()+(y-(viewport.y()+viewport.height()/2D))/view.scale();
        if(!Double.isFinite(worldX)||!Double.isFinite(worldZ)||Math.abs(worldX)>30_000_000||Math.abs(worldZ)>30_000_000)return Optional.empty();
        return Optional.of(new Vec3(worldX,planeY,worldZ));
    }
    public static List<Vec3> rectangle(Vec3 center,double yaw,double length,double width) {
        if(center==null||!Double.isFinite(yaw)||!Double.isFinite(length)||!Double.isFinite(width)||length<0||width<0)return List.of();
        double radians=Math.toRadians(yaw),fx=-Math.sin(radians)*length/2,fz=Math.cos(radians)*length/2;
        double rx=Math.cos(radians)*width/2,rz=Math.sin(radians)*width/2;
        return List.of(center.add(fx+rx,0,fz+rz),center.add(fx-rx,0,fz-rz),
                center.add(-fx-rx,0,-fz-rz),center.add(-fx+rx,0,-fz+rz));
    }
    public static List<Vec3> runwayFootprint(Vec3 start,Vec3 end,double width) {
        double length=Math.hypot(end.x-start.x,end.z-start.z);
        return rectangle(start.add(end).scale(.5),headingDegrees(start,end),length+width,width);
    }
    /**
     * The server validates the circle sweep of each directed segment. A rectangle per segment plus a disk at
     * every node is exactly that union. Circumscribed disk polygons never understate its rounded boundary.
     * Tessellation is pixel adaptive and bounded; entirely off-map components are skipped before allocation.
     */
    public static List<List<Vec3>> taxiFootprint(List<Vec3> points,double width,RoadPlannerMapView view,ClaimMapViewport viewport) {
        if(points==null||points.size()>64||!Double.isFinite(width)||width<=0||view==null||viewport==null)return List.of();
        for(Vec3 point:points)if(point==null||!Double.isFinite(point.x)||!Double.isFinite(point.y)||!Double.isFinite(point.z))return List.of();
        double radius=width/2,pixels=radius*view.scale();
        if(!Double.isFinite(pixels)||pixels<=0)return List.of();
        List<List<Vec3>> result=new ArrayList<>(Math.max(1,points.size()*2-1));
        for(int i=0;i+1<points.size();i++) {
            Vec3 start=points.get(i),end=points.get(i+1);
            double length=Math.hypot(end.x-start.x,end.z-start.z);
            List<Vec3> strip=rectangle(start.add(end).scale(.5),headingDegrees(start,end),length,width);
            if(visiblePolygon(strip,view,viewport))result.add(strip);
        }
        double ratio=pixels/(pixels+.25);
        int count=Math.max(12,Math.min(96,(int)Math.ceil(Math.PI/Math.acos(ratio))));
        double circumradius=radius/Math.cos(Math.PI/count);
        for(Vec3 center:points) {
            ScreenPoint projected=project(center,view,viewport);double screenRadius=circumradius*view.scale();
            if(!projected.finite()||!Double.isFinite(screenRadius)||projected.x+screenRadius<viewport.x()
                    ||projected.x-screenRadius>=viewport.right()||projected.y+screenRadius<viewport.y()
                    ||projected.y-screenRadius>=viewport.bottom())continue;
            List<Vec3> disk=new ArrayList<>(count);
            for(int i=0;i<count;i++) {
                double angle=2*Math.PI*i/count;
                disk.add(center.add(Math.cos(angle)*circumradius,0,Math.sin(angle)*circumradius));
            }
            if(visiblePolygon(disk,view,viewport))result.add(List.copyOf(disk));
        }
        return List.copyOf(result);
    }
    private static boolean visiblePolygon(List<Vec3> polygon,RoadPlannerMapView view,ClaimMapViewport viewport) {
        if(polygon.size()<3)return false;
        double minX=Double.POSITIVE_INFINITY,minY=minX,maxX=Double.NEGATIVE_INFINITY,maxY=maxX;
        for(Vec3 point:polygon) {
            ScreenPoint projected=project(point,view,viewport);if(!projected.finite())return false;
            minX=Math.min(minX,projected.x);maxX=Math.max(maxX,projected.x);minY=Math.min(minY,projected.y);maxY=Math.max(maxY,projected.y);
        }
        return maxX>=viewport.x()&&minX<viewport.right()&&maxY>=viewport.y()&&minY<viewport.bottom();
    }
    public static double headingDegrees(Vec3 center,Vec3 target) {
        return Math.toDegrees(Math.atan2(-(target.x-center.x),target.z-center.z));
    }
    public static ScreenPoint headingHandle(Vec3 center,double yaw,double length,RoadPlannerMapView view,ClaimMapViewport viewport) {
        ScreenPoint projected=project(center,view,viewport);
        double distance=Math.max(24,length*view.scale()/2+12),radians=Math.toRadians(yaw);
        return new ScreenPoint(projected.x-Math.sin(radians)*distance,projected.y+Math.cos(radians)*distance);
    }
    public static int nearestPoint(List<Vec3> points,double x,double y,double tolerance,RoadPlannerMapView view,ClaimMapViewport viewport) {
        if(!validPointer(x,y,tolerance,viewport))return -1;
        double best=tolerance*tolerance;int result=-1;
        for(int i=0;i<points.size();i++) {
            ScreenPoint p=project(points.get(i),view,viewport);double distance=distanceSquared(p,x,y);
            if(distance<=best){best=distance;result=i;}
        }
        return result;
    }
    public static int nearestSegment(List<Vec3> points,double x,double y,double tolerance,RoadPlannerMapView view,ClaimMapViewport viewport) {
        if(!validPointer(x,y,tolerance,viewport))return -1;
        double best=tolerance*tolerance;int result=-1;
        for(int i=0;i+1<points.size();i++) {
            double distance=segmentDistanceSquared(project(points.get(i),view,viewport),project(points.get(i+1),view,viewport),x,y);
            if(distance<best){best=distance;result=i;}
        }
        return result;
    }
    /** Draft handles take priority over its footprint, so edge dragging never selects another facility. */
    public static Optional<Hit> hitDraft(AirportPlannerModel.Draft draft,double x,double y,RoadPlannerMapView view,ClaimMapViewport viewport) {
        if(draft==null||!validPointer(x,y,0,viewport))return Optional.empty();
        Kind kind=switch(draft.kind()){case RUNWAY,HELIPAD,MOORING->Kind.RUNWAY;case STAND->Kind.STAND;default->Kind.TAXI;};
        return hit(kind,draft.id(),draft.points(),draft.yaw(),draft.length(),draft.width(),x,y,view,viewport,true);
    }
    /** Match visual stacking: parking areas are above paths, and paths are above runways. */
    public static Optional<Hit> hitSaved(AirportScreenData snapshot,double x,double y,RoadPlannerMapView view,ClaimMapViewport viewport) {
        if(snapshot==null||!validPointer(x,y,0,viewport))return Optional.empty();
        for(int i=snapshot.groundLayout().stands().size()-1;i>=0;i--) {
            var stand=snapshot.groundLayout().stands().get(i);
            var hit=hit(Kind.STAND,stand.id(),List.of(stand.position()),stand.yawDegrees(),stand.length(),stand.width(),x,y,view,viewport,false);
            if(hit.isPresent())return hit;
        }
        for(int i=snapshot.groundLayout().taxiRoutes().size()-1;i>=0;i--) {
            var route=snapshot.groundLayout().taxiRoutes().get(i);
            var hit=hit(Kind.TAXI,route.id(),route.points(),0,0,route.width(),x,y,view,viewport,false);
            if(hit.isPresent())return hit;
        }
        for(int i=snapshot.facilities().size()-1;i>=0;i--) {
            var runway=snapshot.facilities().get(i);
            if(runway.type()!=com.monpai.sailboatmod.transport.air.airport.AirportFacility.Type.RUNWAY
                    && !snapshot.supportsRoutePublication()) continue;
            var points=List.of(new Vec3(runway.start().getX()+.5,runway.start().getY(),runway.start().getZ()+.5),
                    new Vec3(runway.end().getX()+.5,runway.end().getY(),runway.end().getZ()+.5));
            var hit=hit(Kind.RUNWAY,runway.id(),points,0,0,runway.width(),x,y,view,viewport,false);
            if(hit.isPresent())return hit;
        }
        return Optional.empty();
    }
    private static Optional<Hit> hit(Kind kind,String id,List<Vec3> points,double yaw,double length,double width,
                                     double x,double y,RoadPlannerMapView view,ClaimMapViewport viewport,boolean headingEnabled) {
        if(kind==Kind.STAND&&!points.isEmpty()&&headingEnabled
                &&distanceSquared(headingHandle(points.getFirst(),yaw,length,view,viewport),x,y)<=POINT_TOLERANCE*POINT_TOLERANCE)
            return Optional.of(new Hit(kind,id,Handle.HEADING,0));
        int point=nearestPoint(points,x,y,POINT_TOLERANCE,view,viewport);
        if(point>=0)return Optional.of(new Hit(kind,id,Handle.POINT,point));
        int segment=nearestSegment(points,x,y,SEGMENT_TOLERANCE,view,viewport);
        if(segment>=0)return Optional.of(new Hit(kind,id,Handle.SEGMENT,segment));
        if(kind==Kind.STAND&&!points.isEmpty()&&containsPolygon(rectangle(points.getFirst(),yaw,length,width),x,y,view,viewport))
            return Optional.of(new Hit(kind,id,Handle.BODY,0));
        if(kind==Kind.RUNWAY&&points.size()==2&&containsPolygon(runwayFootprint(points.get(0),points.get(1),width),x,y,view,viewport))
            return Optional.of(new Hit(kind,id,Handle.BODY,-1));
        if(kind==Kind.TAXI) {
            double halfWidth=Math.max(SEGMENT_TOLERANCE,Math.abs(width*view.scale()/2));
            int wideSegment=nearestSegment(points,x,y,halfWidth,view,viewport);
            if(wideSegment>=0)return Optional.of(new Hit(kind,id,Handle.BODY,wideSegment));
        }
        return Optional.empty();
    }
    public static boolean containsPolygon(List<Vec3> polygon,double x,double y,RoadPlannerMapView view,ClaimMapViewport viewport) {
        if(!validPointer(x,y,0,viewport)||polygon.size()<3)return false;
        boolean inside=false;
        for(int i=0,j=polygon.size()-1;i<polygon.size();j=i++) {
            ScreenPoint a=project(polygon.get(i),view,viewport),b=project(polygon.get(j),view,viewport);
            if(!a.finite()||!b.finite())return false;
            if(segmentDistanceSquared(a,b,x,y)<1e-10)return true;
            if((a.y>y)!=(b.y>y)&&x<(b.x-a.x)*(y-a.y)/(b.y-a.y)+a.x)inside=!inside;
        }
        return inside;
    }
    public static Optional<ScreenLine> clipLine(ScreenPoint a,ScreenPoint b,ClaimMapViewport viewport) {
        if(!a.finite()||!b.finite())return Optional.empty();
        double dx=b.x-a.x,dy=b.y-a.y;double[] interval={0,1};
        if(!Double.isFinite(dx)||!Double.isFinite(dy)||!clip(-dx,a.x-viewport.x(),interval)
                ||!clip(dx,viewport.right()-1-a.x,interval)||!clip(-dy,a.y-viewport.y(),interval)
                ||!clip(dy,viewport.bottom()-1-a.y,interval))return Optional.empty();
        return Optional.of(new ScreenLine(new ScreenPoint(a.x+dx*interval[0],a.y+dy*interval[0]),
                new ScreenPoint(a.x+dx*interval[1],a.y+dy*interval[1])));
    }
    private static boolean clip(double p,double q,double[] interval) {
        if(Math.abs(p)<1e-12)return q>=0;
        double ratio=q/p;
        if(p<0){if(ratio>interval[1])return false;interval[0]=Math.max(interval[0],ratio);}
        else {if(ratio<interval[0])return false;interval[1]=Math.min(interval[1],ratio);}
        return true;
    }
    private static boolean validPointer(double x,double y,double tolerance,ClaimMapViewport viewport) {
        return Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(tolerance)&&tolerance>=0&&viewport.contains(x,y);
    }
    private static double distanceSquared(ScreenPoint p,double x,double y) {
        return p.finite()?(p.x-x)*(p.x-x)+(p.y-y)*(p.y-y):Double.POSITIVE_INFINITY;
    }
    private static double segmentDistanceSquared(ScreenPoint a,ScreenPoint b,double x,double y) {
        if(!a.finite()||!b.finite())return Double.POSITIVE_INFINITY;
        double dx=b.x-a.x,dy=b.y-a.y,length=dx*dx+dy*dy;
        if(!Double.isFinite(length))return Double.POSITIVE_INFINITY;
        double t=length<1e-16?0:Math.max(0,Math.min(1,((x-a.x)*dx+(y-a.y)*dy)/length));
        return distanceSquared(new ScreenPoint(a.x+dx*t,a.y+dy*t),x,y);
    }
}
