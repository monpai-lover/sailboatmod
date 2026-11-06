package com.monpai.sailboatmod.transport.air.ground;

import java.util.*;

/** Conservative authored-path preflight; never a dynamics or live-aircraft certificate. */
public final class TaxiRouteGeometry {
    private static final double EPS=1e-7;
    private static final double HEADING_TOLERANCE=Math.toRadians(5);
    public record Point(double x,double z) { }
    public record Capabilities(boolean liveVerified,double hullRadius,double hullHeight,
                               double minimumTurnRadius,double brakingDeceleration,double taxiSpeed) { }
    public record Request(List<Point> points,double width,int clearanceHeight,int holdPointIndex,
                          Point initialPosition,double initialYawDegrees,double terminalYawDegrees,
                          Capabilities capabilities) {
        public Request { points=points==null?null:Collections.unmodifiableList(new ArrayList<>(points)); }
    }
    public record Assessment(boolean admitted,String reason,int pointIndex,double required,double available) { }
    private TaxiRouteGeometry() { }

    /** Conservative geometry only: no path rewriting, dynamics prediction, world or native calls. */
    public static Assessment assess(Request request) {
        if(request==null||request.points()==null||request.points().size()<2||request.points().size()>64
                ||!Double.isFinite(request.width())||request.width()<2||request.width()>64
                ||request.clearanceHeight()<4||request.clearanceHeight()>64
                ||request.holdPointIndex()<-1||request.holdPointIndex()>=request.points().size()-1
                ||!finite(request.initialPosition())||!Double.isFinite(request.initialYawDegrees())
                ||!Double.isFinite(request.terminalYawDegrees()))return reject("taxi_invalid_geometry",-1,0,0);
        Capabilities c=request.capabilities();
        if(c==null||!c.liveVerified()||!positive(c.hullRadius())||!positive(c.hullHeight())
                ||!positive(c.minimumTurnRadius())||c.minimumTurnRadius()>128||!positive(c.brakingDeceleration())
                ||c.brakingDeceleration()>30||!positive(c.taxiSpeed())||c.taxiSpeed()>5)
            return reject("taxi_capability_unverified",-1,0,0);
        double total=0;
        for(int i=0;i<request.points().size();i++) {
            if(!finite(request.points().get(i)))return reject("taxi_invalid_geometry",i,0,0);
            if(i>0) {
                double distance=distance(request.points().get(i-1),request.points().get(i));
                if(distance<.1||distance>256)return reject("taxi_invalid_geometry",i,0,0);
                total+=distance;
            }
        }
        if(total>3072)return reject("taxi_invalid_geometry",-1,total,3072);
        double corridor=request.width()/2-c.hullRadius();
        if(corridor<.5)return reject("facility_too_narrow",-1,finiteMetric(2*c.hullRadius()+1),request.width());
        if(c.hullHeight()>request.clearanceHeight())return reject("facility_too_low",-1,c.hullHeight(),request.clearanceHeight());
        List<Point> points=request.points();int count=points.size();
        double[] lengths=new double[count-1],ux=new double[count-1],uz=new double[count-1];
        for(int i=0;i<count-1;i++) {
            lengths[i]=distance(points.get(i),points.get(i+1));
            ux[i]=(points.get(i+1).x()-points.get(i).x())/lengths[i];
            uz[i]=(points.get(i+1).z()-points.get(i).z())/lengths[i];
        }
        double radius=c.minimumTurnRadius(),capture=Math.min(1.5,corridor),allowedMiss=capture/2;
        double[] tangent=new double[count];
        for(int i=1;i<count-1;i++) {
            double dot=ux[i-1]*ux[i]+uz[i-1]*uz[i];
            double cross=ux[i-1]*uz[i]-uz[i-1]*ux[i];
            double turn=Math.atan2(Math.abs(cross),dot);
            if(i==request.holdPointIndex()) {
                // A hard stop cannot borrow the rolling fillet on either side of the hold.
                if(turn>HEADING_TOLERANCE+EPS)
                    return reject("taxi_hold_heading_not_proven",i,Math.toDegrees(turn),5);
                continue;
            }
            if(turn>=Math.PI-EPS)return reject("taxi_reversal_not_proven",i,180,0);
            double miss=radius*(1/Math.cos(turn/2)-1);
            if(miss>allowedMiss+EPS)return reject("taxi_turn_capture_not_proven",i,miss,allowedMiss);
            tangent[i]=radius*Math.tan(turn/2);
        }
        for(int i=0;i<count-1;i++) {
            double needed=tangent[i]+tangent[i+1];
            if(needed>lengths[i]+EPS)return reject("taxi_turn_segment_not_proven",i,needed,lengths[i]);
        }

        Point start=request.initialPosition(),first=points.getFirst();
        double startDx=start.x()-first.x(),startDz=start.z()-first.z();
        double startDistance=Math.hypot(startDx,startDz);
        double along=startDx*ux[0]+startDz*uz[0];
        double initialYaw=Math.toRadians(wrap(request.initialYawDegrees()));
        double hx=-Math.sin(initialYaw),hz=Math.cos(initialYaw);
        if(!Double.isFinite(startDistance)||!(startDistance<=capture+EPS
                ||startDistance<=corridor+EPS&&along>=-EPS)) {
            // A not-yet-passed first point remains the follower's target. Prove only an
            // entry into its unchanged capture circle, inside its current radial corridor.
            double forwardToFirst=-(startDx*hx+startDz*hz);
            double rayMiss=Math.abs(startDx*hz-startDz*hx);
            if(!Double.isFinite(startDistance)||startDistance>corridor+EPS
                    ||forwardToFirst<=0||rayMiss>capture)
                return reject("taxi_initial_position_not_proven",0,finiteMetric(startDistance),corridor);
            double entryDistance=forwardToFirst-Math.sqrt(Math.max(0,capture*capture-rayMiss*rayMiss));
            if(!Double.isFinite(entryDistance)||entryDistance<0)
                return reject("taxi_initial_position_not_proven",0,finiteMetric(startDistance),corridor);
            // Only the geometric check starts at the first capture intersection; the Plan,
            // point index and follower are unchanged. Apply all original correction bounds.
            startDx+=entryDistance*hx;
            startDz+=entryDistance*hz;
            along=startDx*ux[0]+startDz*uz[0];
        }
        double initialTurn=Math.atan2(Math.abs(hx*uz[0]-hz*ux[0]),hx*ux[0]+hz*uz[0]);
        // Keep the initial correction forward within the first authored segment.
        if(initialTurn>Math.PI/2+EPS)
            return reject("taxi_initial_heading_not_proven",0,Math.toDegrees(initialTurn),90);
        double offset=Math.abs(startDx*uz[0]-startDz*ux[0]);
        double lateral=offset+radius*(1-Math.cos(initialTurn));
        if(lateral>corridor+EPS||offset>2*radius+EPS)
            return reject("taxi_initial_corridor_not_proven",0,lateral,Math.min(corridor,2*radius));
        // Two tangent circular arcs correct heading without net lateral translation; another
        // two-arc offset correction reaches the authored line. Only their bounds are evaluated.
        double headingBeta=Math.acos(clamp((1+Math.cos(initialTurn))/2));
        double offsetBeta=Math.acos(clamp(1-offset/(2*radius)));
        double correction=radius*(Math.sin(initialTurn)+2*Math.sin(headingBeta))+2*radius*Math.sin(offsetBeta);
        double stopping=c.taxiSpeed()*.25+c.taxiSpeed()*c.taxiSpeed()/(2*c.brakingDeceleration())+.75;
        double firstAvailable=lengths[0]-Math.max(0,along)-tangent[1];
        double firstRequired=correction+((request.holdPointIndex()==1||count==2)?stopping:0);
        if(!Double.isFinite(firstRequired)||firstRequired>firstAvailable+EPS)
            return reject("taxi_initial_correction_not_proven",0,finiteMetric(firstRequired),firstAvailable);

        double terminalYaw=Math.toRadians(wrap(request.terminalYawDegrees()));
        double tx=-Math.sin(terminalYaw),tz=Math.cos(terminalYaw);
        double terminalTurn=Math.atan2(Math.abs(tx*uz[count-2]-tz*ux[count-2]),tx*ux[count-2]+tz*uz[count-2]);
        if(terminalTurn>HEADING_TOLERANCE+EPS)
            return reject("taxi_terminal_heading_not_proven",count-1,Math.toDegrees(terminalTurn),5);
        for(int stop:new int[]{request.holdPointIndex(),count-1}) {
            if(stop<=0)continue;
            double available=lengths[stop-1]-tangent[stop-1];
            if(!Double.isFinite(stopping)||stopping>available+EPS)
                return reject("taxi_stopping_distance_not_proven",stop,finiteMetric(stopping),available);
        }
        return new Assessment(true,"ok",-1,0,corridor);
    }
    private static Assessment reject(String reason,int point,double required,double available) {
        return new Assessment(false,reason,point,required,available);
    }
    private static boolean finite(Point p){return p!=null&&Double.isFinite(p.x())&&Double.isFinite(p.z());}
    private static boolean positive(double value){return Double.isFinite(value)&&value>0;}
    private static double distance(Point a,Point b){return Math.hypot(a.x()-b.x(),a.z()-b.z());}
    private static double wrap(double degrees){double value=degrees%360;return value>180?value-360:value< -180?value+360:value;}
    private static double clamp(double value){return Math.max(-1,Math.min(1,value));}
    private static double finiteMetric(double value){return Double.isFinite(value)?value:Double.MAX_VALUE;}
}
