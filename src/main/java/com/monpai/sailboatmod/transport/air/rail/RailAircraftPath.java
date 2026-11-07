package com.monpai.sailboatmod.transport.air.rail;

import com.monpai.sailboatmod.road.pathfinding.post.SplineHelper;
import java.io.*;
import java.security.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;

/** Immutable visual geometry. No world, native controller, inventory or aircraft dynamics are queried. */
public final class RailAircraftPath {
    public enum Phase { TAXI_OUT,HOLD_SHORT,LINE_UP,TAKEOFF,CLIMB,CRUISE,APPROACH,LANDING_ROLL,TAXI_IN,STAND_STABILIZING,COMPLETE }
    public record Leg(Phase phase,List<Vec3> points,double speed,double cornerRadius,boolean bankAllowed) {
        public Leg {
            if(phase==null||points==null||points.isEmpty()||points.size()>4096||!finite(speed)||speed<0||speed>100
                    ||!finite(cornerRadius)||cornerRadius<0||cornerRadius>100_000)throw invalid("invalid_leg");
            points=List.copyOf(points);
            if(points.stream().anyMatch(p->!validPoint(p)))throw invalid("invalid_point");
            if(points.size()==1) {
                if(!marker(phase)||speed!=0||cornerRadius!=0||bankAllowed)throw invalid("invalid_stationary_marker");
            } else {
                if(speed<=0||marker(phase))throw invalid("invalid_moving_leg");
                for(int i=1;i<points.size();i++) {
                    Vec3 delta=points.get(i).subtract(points.get(i-1));
                    if(delta.length()<EPS||Math.hypot(delta.x,delta.z)<EPS)throw invalid("zero_or_vertical_segment");
                    if(ground(phase)&&Math.abs(delta.y)>EPS)throw invalid("sloping_ground_leg");
                    if(i+1<points.size()&&delta.normalize().dot(points.get(i+1).subtract(points.get(i)).normalize())<-.999999)
                        throw invalid("reversing_corner");
                    if(i+1<points.size()&&cornerRadius<=EPS&&delta.normalize().dot(points.get(i+1).subtract(points.get(i)).normalize())<1-1e-10)
                        throw invalid("corner_radius_required");
                }
            }
            if(bankAllowed&&ground(phase))throw invalid("ground_bank");
        }
    }
    /** Tangent is the visual nose direction, including rotation pitch. Bank is right-hand about it: +X toward +Z has positive bank. */
    public record Sample(Vec3 position,Vec3 tangent,double bankRadians,Phase phase,double speed,double pitchRadians) { }
    public record ControlHull(int legIndex,Phase phase,double startDistance,double endDistance,List<Vec3> points) {
        public ControlHull { points=List.copyOf(points); }
    }
    private record LegRange(Leg leg,double start,double end) { }
    private record Located(Curve curve,double local) { }
    private static final int VERSION=1,MAX_LEGS=64,MAX_TOTAL_POINTS=8192,MAX_TABLE_NODES=262144;
    private static final double EPS=1e-8,MAX_LENGTH=10_000_000,MAX_BANK=Math.PI/6,MAX_PITCH=Math.PI/6;
    private final List<Leg> legs;
    private final List<Curve> curves;
    private final List<LegRange> ranges;
    private final double length,finalYawDegrees,rotationRadius;
    private final String fingerprint;

    private RailAircraftPath(List<Leg> input,double yaw,double radius) {
        if(input==null||input.isEmpty()||input.size()>MAX_LEGS||!finite(yaw)||Math.abs(yaw)>360_000
                ||!finite(radius)||radius<0||radius>64)throw invalid("invalid_path");
        legs=List.copyOf(input);finalYawDegrees=wrap(yaw);rotationRadius=radius;
        int count=0;Vec3 previous=null;
        for(Leg leg:legs) {
            if(leg==null||(count+=leg.points().size())>MAX_TOTAL_POINTS)throw invalid("path_too_large");
            if(previous!=null&&previous.distanceTo(leg.points().getFirst())>EPS)throw invalid("disconnected_legs");
            previous=leg.points().getLast();
        }
        List<Curve> built=new ArrayList<>();List<LegRange> intervals=new ArrayList<>();double at=0;int nodes=0;
        for(int index=0;index<legs.size();index++) {
            Leg leg=legs.get(index);double begin=at;List<Vec3> p=leg.points();
            if(p.size()>1) {
                int last=p.size()-1;double[] trim=new double[p.size()];
                for(int i=1;i<last;i++) {
                    Vec3 a=p.get(i).subtract(p.get(i-1)).normalize(),b=p.get(i+1).subtract(p.get(i)).normalize();
                    if(a.dot(b)<1-1e-10)trim[i]=Math.min(leg.cornerRadius(),.45*Math.min(p.get(i).distanceTo(p.get(i-1)),p.get(i).distanceTo(p.get(i+1))));
                }
                Vec3 cursor=p.getFirst();
                for(int i=0;i<last;i++) {
                    Vec3 endpoint=p.get(i+1),direction=endpoint.subtract(p.get(i)).normalize();
                    Vec3 before=endpoint.subtract(direction.scale(trim[i+1]));
                    if(cursor.distanceTo(before)>EPS) {
                        Vec3 startTangent=direction,endTangent=direction;
                        if(i==0)startTangent=boundaryTangent(index,true,direction);
                        if(i==last-1)endTangent=boundaryTangent(index,false,direction);
                        if(i==last-1&&leg.phase()==Phase.TAXI_IN&&lastMovingLeg(index)) {
                            double yawRadians=Math.toRadians(finalYawDegrees);endTangent=new Vec3(-Math.sin(yawRadians),0,Math.cos(yawRadians));
                            if(direction.dot(endTangent)<-.999999||direction.dot(endTangent)<1-1e-12&&leg.cornerRadius()<=EPS)
                                throw invalid("stand_heading_does_not_fit");
                        }
                        Curve curve=lineOrCubic(cursor,before,startTangent,endTangent,leg,at);
                        built.add(curve);at+=curve.length;nodes+=curve.ts.length;
                    }
                    if(trim[i+1]>EPS) {
                        Vec3 after=endpoint.add(p.get(i+2).subtract(endpoint).normalize().scale(trim[i+1]));
                        Curve corner=new Curve(new Vec3[]{before,endpoint,after},leg,at);
                        built.add(corner);at+=corner.length;nodes+=corner.ts.length;cursor=after;
                    } else cursor=endpoint;
                    if(!finite(at)||at>MAX_LENGTH||nodes>MAX_TABLE_NODES)throw invalid("path_too_large");
                }
            }
            intervals.add(new LegRange(leg,begin,at));
        }
        if(at<=EPS||built.isEmpty())throw invalid("empty_path");
        curves=List.copyOf(built);ranges=List.copyOf(intervals);length=at;
        if(rotationRadius>0)for(Curve curve:curves)if(rotationPhase(curve.leg.phase()))
            for(Vec3 point:curve.control)if(point.y+rotationRadius>2047)throw invalid("flight_outside_height");
        fingerprint=hash();
    }
    public static RailAircraftPath compile(List<Leg> legs,double finalYawDegrees) { return new RailAircraftPath(legs,finalYawDegrees,0); }
    public static RailAircraftPath compile(List<Leg> legs,double finalYawDegrees,double rotationRadius) { return new RailAircraftPath(legs,finalYawDegrees,rotationRadius); }
    public List<Leg> legs(){return legs;}
    public double length(){return length;}
    public double rotationRadius(){return rotationRadius;}
    public String fingerprint(){return fingerprint;}
    /** Nominal compiled controls. Ground prefix controls remain exact: rotation clearance never changes taxi geometry. */
    public List<ControlHull> controlPolygons() {
        List<ControlHull> result=new ArrayList<>(curves.size());int index=0;
        for(Curve curve:curves) {
            while(index<ranges.size()-1&&curve.start>=ranges.get(index).end())index++;
            List<Vec3> points=new ArrayList<>(curve.control.length);
            for(Vec3 p:curve.control)points.add(new Vec3(p.x,p.y,p.z));
            result.add(new ControlHull(index,curve.leg.phase(),curve.start,curve.start+curve.length,points));
        }
        return List.copyOf(result);
    }

    private Vec3 boundaryTangent(int index,boolean start,Vec3 own) {
        int other=index+(start?-1:1);boolean hold=false;
        while(other>=0&&other<legs.size()&&legs.get(other).points().size()==1){hold|=legs.get(other).phase()==Phase.HOLD_SHORT;other+=start?-1:1;}
        if(other<0||other>=legs.size())return own;
        Leg current=legs.get(index),adjacent=legs.get(other);List<Vec3> p=adjacent.points();
        Vec3 direction=start?p.getLast().subtract(p.get(p.size()-2)).normalize():p.get(1).subtract(p.getFirst()).normalize();
        if(ground(current.phase())&&!ground(adjacent.phase()))return own;
        if(!ground(current.phase())&&ground(adjacent.phase()))return direction;
        if(ground(current.phase())) {
            Vec3 incoming=start?direction:own,outgoing=start?own:direction;
            Phase before=start?adjacent.phase():current.phase(),after=start?current.phase():adjacent.phase();
            if(hold||before==Phase.LANDING_ROLL)return incoming;
            if(after==Phase.TAKEOFF)return outgoing;
        }
        Vec3 sum=own.add(direction);if(sum.length()<EPS)throw invalid("reversing_phase_boundary");return sum.normalize();
    }
    private boolean lastMovingLeg(int index){for(int i=index+1;i<legs.size();i++)if(legs.get(i).points().size()>1)return false;return true;}
    private static Curve lineOrCubic(Vec3 a,Vec3 b,Vec3 first,Vec3 last,Leg leg,double start) {
        Vec3 along=b.subtract(a).normalize();
        if(first.dot(along)>1-1e-12&&last.dot(along)>1-1e-12)return new Curve(new Vec3[]{a,b},leg,start);
        double handle=ground(leg.phase())?Math.min(a.distanceTo(b)/3,leg.cornerRadius()):a.distanceTo(b)/3;
        if(handle<=EPS)throw invalid("phase_heading_does_not_fit");
        return new Curve(new Vec3[]{a,a.add(first.scale(handle)),b.subtract(last.scale(handle)),b},leg,start);
    }
    public double nextBoundary(double distance) {
        double s=clamped(distance);for(LegRange range:ranges)if(range.end()>s)return range.end();return length;
    }
    public double speedForAdvance(double distance) {
        double s=clamped(distance);if(s>=length)return 0;
        for(LegRange range:ranges)if(range.end()>s)return speedAt(s,range);return 0;
    }
    public Sample sample(double distance) {
        double s=clamped(distance);Located located=locate(s);Curve curve=located.curve();
        Vec3 position=curve.pointAtLength(located.local()),tangent=tangentAt(s);LegRange range=rangeAt(s);
        if(s>=length) {
            double yaw=Math.toRadians(finalYawDegrees);tangent=new Vec3(-Math.sin(yaw),0,Math.cos(yaw));
        }
        Phase phase=range.leg().phase();double geometricPitch=Math.atan2(tangent.y,Math.hypot(tangent.x,tangent.z));
        double pitch=ground(phase)&&phase!=Phase.TAKEOFF?0:
                Math.max(-MAX_PITCH,Math.min(MAX_PITCH,geometricPitch+rotationPitch(s,phase)));
        if(rotationRadius>0) {
            if(phase==Phase.TAKEOFF)pitch=rotationPitch(s,phase);
            else if(phase==Phase.CLIMB||phase==Phase.APPROACH) {
                double blend=Math.min(20,(range.end()-range.start())*.5);
                double envelope=smooth((s-range.start())/blend)*smooth((range.end()-s)/blend);
                pitch=geometricPitch*envelope+(phase==Phase.CLIMB?rotationPitch(s,phase):0);
            } else pitch=0;
            pitch=Math.max(-MAX_PITCH,Math.min(MAX_PITCH,pitch));
        }
        if(s>=length)pitch=0;
        if(rotationRadius>0&&rotationPhase(phase))position=position.add(0,rotationRadius*Math.abs(Math.sin(pitch)),0);
        if(!validPoint(position))throw invalid("flight_outside_height");
        double horizontal=Math.hypot(tangent.x,tangent.z);
        if(horizontal>EPS)tangent=new Vec3(tangent.x/horizontal*Math.cos(pitch),Math.sin(pitch),tangent.z/horizontal*Math.cos(pitch));
        double bank=range.leg().bankAllowed()?bankAt(s,range):0;
        return new Sample(position,tangent,bank,range.leg().phase(),speedAt(s,range),pitch);
    }
    private double speedAt(double s,LegRange range) {
        Leg leg=range.leg();double span=range.end()-range.start();if(span<=EPS)return 0;
        double fraction=smooth((s-range.start())/span),taxi=Math.min(3,leg.speed());
        if(leg.phase()==Phase.TAKEOFF)return taxi+(leg.speed()-taxi)*fraction;
        if(leg.phase()==Phase.LANDING_ROLL)return leg.speed()+(taxi-leg.speed())*fraction;
        if(leg.phase()==Phase.TAXI_IN&&Math.abs(range.end()-length)<=EPS) {
            double floor=Math.min(.25,leg.speed()),braking=Math.min(8,span*.5);
            return floor+(leg.speed()-floor)*smooth((range.end()-s)/braking);
        }
        return leg.speed();
    }
    private double rotationPitch(double s,Phase phase) {
        if(phase!=Phase.TAKEOFF&&phase!=Phase.CLIMB)return 0;
        for(int i=0;i<ranges.size()-1;i++) {
            LegRange rotation=ranges.get(i);if(rotation.leg().phase()!=Phase.TAKEOFF||ranges.get(i+1).leg().phase()!=Phase.CLIMB)continue;
            double blend=Math.min(20,Math.max(5,(rotation.end()-rotation.start())*.2));
            if(rotationRadius>0)blend=Math.min(blend,(ranges.get(i+1).end()-ranges.get(i+1).start())*.5);
            if(Math.abs(s-rotation.end())<blend)return Math.toRadians(3)*smooth(1-Math.abs(s-rotation.end())/blend);
        }
        return 0;
    }
    private double bankAt(double s,LegRange range) {
        double speed=range.leg().speed(),window=Math.max(5,speed*.75);
        double a=Math.max(0,s-window),b=Math.min(length,s+window);if(b-a<EPS)return 0;
        Vec3 first=tangentAt(a),last=tangentAt(b);
        double horizontal=(b-a)*(Math.hypot(first.x,first.z)+Math.hypot(last.x,last.z))*.5;
        if(horizontal<EPS)return 0;
        double angle=Math.atan2(first.x*last.z-first.z*last.x,first.x*last.x+first.z*last.z);
        double bank=Math.max(-MAX_BANK,Math.min(MAX_BANK,Math.atan(speed*speed*angle/(horizontal*9.81))));
        double begin=range.start(),end=range.end();int index=ranges.indexOf(range);
        for(int i=index-1;i>=0&&ranges.get(i).leg().bankAllowed();i--)begin=ranges.get(i).start();
        for(int i=index+1;i<ranges.size()&&ranges.get(i).leg().bankAllowed();i++)end=ranges.get(i).end();
        return bank*smooth((s-begin)/window)*smooth((end-s)/window);
    }
    private LegRange rangeAt(double s) {
        for(LegRange range:ranges)if(range.start()==range.end()&&s==range.start())return range;
        for(LegRange range:ranges)if(s<range.end())return range;
        return ranges.getLast();
    }
    private Located locate(double s) {
        int lo=0,hi=curves.size()-1;
        while(lo<hi){int mid=(lo+hi)/2;Curve c=curves.get(mid);if(s>=c.start+c.length)lo=mid+1;else hi=mid;}
        Curve c=curves.get(lo);return new Located(c,Math.max(0,Math.min(c.length,s-c.start)));
    }
    private Vec3 tangentAt(double s) { Located at=locate(s);return at.curve().tangentAtLength(at.local()); }
    private double clamped(double s){if(!finite(s))throw invalid("invalid_distance");return Math.max(0,Math.min(length,s));}

    /** The input trim radius must already fit the aircraft's inset ground corridor. */
    private static final class Curve {
        final Vec3[] control;final double[][] numeric;final Leg leg;final double start,length;final double[] ts,distances;
        Curve(Vec3[] points,Leg leg,double start) {
            for(Vec3 point:points)if(!validPoint(point))throw invalid("curve_out_of_bounds");
            control=points.clone();numeric=new double[points.length][3];this.leg=leg;this.start=start;
            for(int i=0;i<points.length;i++)numeric[i]=new double[]{points[i].x,points[i].y,points[i].z};
            ArrayList<Double> times=new ArrayList<>();ArrayList<Vec3> values=new ArrayList<>();times.add(0D);values.add(points[0]);
            subdivide(0,points[0],1,points[points.length-1],0,times,values);
            ts=new double[times.size()];distances=new double[times.size()];double total=0;
            for(int i=0;i<times.size();i++){ts[i]=times.get(i);if(i>0)total+=values.get(i).distanceTo(values.get(i-1));distances[i]=total;}
            if(!finite(total)||total<=EPS)throw invalid("invalid_curve");length=total;
        }
        Vec3 point(double t){double[] p=SplineHelper.bezierDeCasteljau(numeric,t);return new Vec3(p[0],p[1],p[2]);}
        void subdivide(double a,Vec3 pa,double b,Vec3 pb,int depth,List<Double> times,List<Vec3> values) {
            double mid=(a+b)*.5;Vec3 q1=point((3*a+b)*.25),qm=point(mid),q3=point((a+3*b)*.25);
            double polygon=pa.distanceTo(q1)+q1.distanceTo(qm)+qm.distanceTo(q3)+q3.distanceTo(pb);
            if(depth<12&&polygon-pa.distanceTo(pb)>1e-6*(b-a)) {
                subdivide(a,pa,mid,qm,depth+1,times,values);subdivide(mid,qm,b,pb,depth+1,times,values);
            } else {times.add(b);values.add(pb);}
        }
        double parameter(double s) {
            if(s<=0)return 0;if(s>=length)return 1;int i=Arrays.binarySearch(distances,s);if(i>=0)return ts[i];
            i=-i-1;double span=distances[i]-distances[i-1];return ts[i-1]+(ts[i]-ts[i-1])*(s-distances[i-1])/span;
        }
        Vec3 pointAtLength(double s){if(s<=0)return control[0];if(s>=length)return control[control.length-1];return point(parameter(s));}
        Vec3 tangentAtLength(double s) {
            double t=parameter(s);Vec3 derivative=Vec3.ZERO;int degree=control.length-1;
            if(degree==1)derivative=control[1].subtract(control[0]);
            else if(degree==2)derivative=control[1].subtract(control[0]).scale(2*(1-t)).add(control[2].subtract(control[1]).scale(2*t));
            else derivative=control[1].subtract(control[0]).scale(3*(1-t)*(1-t)).add(control[2].subtract(control[1]).scale(6*t*(1-t))).add(control[3].subtract(control[2]).scale(3*t*t));
            if(derivative.length()<EPS)throw invalid("degenerate_tangent");return derivative.normalize();
        }
    }

    public CompoundTag save() {
        CompoundTag t=new CompoundTag();t.putInt("Version",VERSION);t.putDouble("FinalYaw",finalYawDegrees);t.putDouble("RotationRadius",rotationRadius);t.putString("Hash",fingerprint);
        ListTag list=new ListTag();for(Leg leg:legs){CompoundTag raw=new CompoundTag();raw.putString("Phase",leg.phase().name());raw.putDouble("Speed",leg.speed());raw.putDouble("CornerRadius",leg.cornerRadius());raw.putBoolean("BankAllowed",leg.bankAllowed());
            ListTag points=new ListTag();for(Vec3 p:leg.points()){CompoundTag xyz=new CompoundTag();xyz.putDouble("X",p.x);xyz.putDouble("Y",p.y);xyz.putDouble("Z",p.z);points.add(xyz);}raw.put("Points",points);list.add(raw);}t.put("Legs",list);return t;
    }
    public static RailAircraftPath load(CompoundTag t) {
        require(t,"Version",Tag.TAG_INT);if(t.getInt("Version")!=VERSION)throw invalid("unknown_path_version");
        require(t,"FinalYaw",Tag.TAG_DOUBLE);require(t,"Hash",Tag.TAG_STRING);require(t,"Legs",Tag.TAG_LIST);
        ListTag list=t.getList("Legs",Tag.TAG_COMPOUND);if(list.isEmpty()||list.size()>MAX_LEGS)throw invalid("invalid_legs");
        ArrayList<Leg> legs=new ArrayList<>();int count=0;
        for(Tag item:list){CompoundTag raw=(CompoundTag)item;require(raw,"Phase",Tag.TAG_STRING);require(raw,"Speed",Tag.TAG_DOUBLE);require(raw,"CornerRadius",Tag.TAG_DOUBLE);require(raw,"BankAllowed",Tag.TAG_BYTE);require(raw,"Points",Tag.TAG_LIST);
            ListTag points=raw.getList("Points",Tag.TAG_COMPOUND);if(points.isEmpty()||points.size()>4096||(count+=points.size())>MAX_TOTAL_POINTS)throw invalid("invalid_points");
            ArrayList<Vec3> copied=new ArrayList<>();for(Tag point:points){CompoundTag xyz=(CompoundTag)point;require(xyz,"X",Tag.TAG_DOUBLE);require(xyz,"Y",Tag.TAG_DOUBLE);require(xyz,"Z",Tag.TAG_DOUBLE);copied.add(new Vec3(xyz.getDouble("X"),xyz.getDouble("Y"),xyz.getDouble("Z")));}
            legs.add(new Leg(Phase.valueOf(raw.getString("Phase")),copied,raw.getDouble("Speed"),raw.getDouble("CornerRadius"),raw.getBoolean("BankAllowed")));}
        if(t.contains("RotationRadius"))require(t,"RotationRadius",Tag.TAG_DOUBLE);
        RailAircraftPath result=compile(legs,t.getDouble("FinalYaw"),t.contains("RotationRadius")?t.getDouble("RotationRadius"):0);
        if(!result.fingerprint.equals(t.getString("Hash")))throw invalid("path_shape_changed");return result;
    }
    private String hash() {
        try {ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);out.writeInt(VERSION);out.writeDouble(finalYawDegrees);out.writeInt(legs.size());
            for(Leg leg:legs){out.writeUTF(leg.phase().name());out.writeDouble(leg.speed());out.writeDouble(leg.cornerRadius());out.writeBoolean(leg.bankAllowed());out.writeInt(leg.points().size());for(Vec3 p:leg.points()){out.writeDouble(p.x);out.writeDouble(p.y);out.writeDouble(p.z);}}
            if(rotationRadius>0){out.writeUTF("rotation-clearance-v1");out.writeDouble(rotationRadius);}
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(IOException|NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static void require(CompoundTag t,String name,int type){if(t==null||!t.contains(name,type))throw invalid("missing_or_invalid_"+name);}
    static boolean validPoint(Vec3 p){return p!=null&&finite(p.x)&&finite(p.y)&&finite(p.z)&&Math.abs(p.x)<=30_000_000&&Math.abs(p.z)<=30_000_000&&p.y>=-2048&&p.y<=2047;}
    static boolean finite(double x){return Double.isFinite(x);}
    static IllegalArgumentException invalid(String reason){return new IllegalArgumentException(reason);}
    private static boolean marker(Phase p){return p==Phase.HOLD_SHORT||p==Phase.STAND_STABILIZING||p==Phase.COMPLETE;}
    private static boolean rotationPhase(Phase p){return p==Phase.TAKEOFF||p==Phase.CLIMB||p==Phase.APPROACH;}
    private static boolean ground(Phase p){return p!=Phase.CLIMB&&p!=Phase.CRUISE&&p!=Phase.APPROACH;}
    private static double smooth(double t){t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);}
    private static double wrap(double yaw){double v=yaw%360;return v>180?v-360:v< -180?v+360:v;}
}
