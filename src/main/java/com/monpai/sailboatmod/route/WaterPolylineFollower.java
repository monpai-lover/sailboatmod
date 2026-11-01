package com.monpai.sailboatmod.route;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.function.BiPredicate;

/** Monotonic polyline tracking, independent of who is sitting in the boat.
 * Passing a waypoint's plane inside the corridor advances the voyage; it is never
 * necessary to orbit backwards and touch the exact vertex. Connections are live-checked.
 */
public final class WaterPolylineFollower {
    private WaterPolylineFollower() {}
    public record Projection(double t,double lateral,Vec3 point) {}
    public record Aim(Vec3 point,boolean blocked,double cornerDistance,double cornerDegrees) {}
    public static Projection project(Vec3 p,Vec3 a,Vec3 b) {
        double dx=b.x-a.x,dz=b.z-a.z,len=dx*dx+dz*dz;
        double t=len<1e-12?1:((p.x-a.x)*dx+(p.z-a.z)*dz)/len;
        Vec3 q=new Vec3(a.x+t*dx,b.y,a.z+t*dz);
        return new Projection(t,Math.hypot(p.x-q.x,p.z-q.z),q);
    }
    public static int advance(List<Vec3> route,int current,Vec3 last,Vec3 boat,double capture,
                              double corridor,BiPredicate<Vec3,Vec3> clear) {
        if(route==null||route.isEmpty())return 0;
        int index=Math.clamp(current,0,route.size()-1),checks=0;
        while(index<route.size()-1&&checks++<128) {
            Vec3 target=route.get(index),next=route.get(index+1);
            boolean reached=horizontal(boat,target)<=capture;
            if(last!=null&&horizontal(last,boat)<=32) {
                Projection sweep=project(target,last,boat);
                reached|=sweep.t>=0&&sweep.t<=1&&sweep.lateral<=capture;
            }
            boolean passed=false;
            if(index>0) {
                Projection p=project(boat,route.get(index-1),target);
                passed=p.t>=1.0&&p.lateral<=corridor;
                Projection n=project(boat,target,next);
                // Rejoin the immediate next leg even after a long overshoot. Only the short
                // boat-to-projected-forward connection is skipped, and it is live-checked below.
                passed|=p.t>=.85&&n.t>0&&n.t<=1&&n.lateral<=corridor;
            }
            if(!reached&&!passed)break;
            Projection n=project(boat,target,next);double len=horizontal(target,next);
            double t=Math.max(0,Math.min(1,n.t+(len<1e-6?1:Math.min(4,len)/len)));
            Vec3 reconnect=lerp(target,next,t);
            if(horizontal(boat,reconnect)>32||!clear.test(boat,reconnect))break;
            index++;
        }
        return index;
    }
    public static Aim aim(List<Vec3> route,int targetIndex,Vec3 boat,double lookahead,
                          BiPredicate<Vec3,Vec3> clear) {
        if(route==null||route.isEmpty())return new Aim(boat,true,0,0);
        int i=Math.clamp(targetIndex,0,route.size()-1);
        Vec3 end=route.get(i),begin=i>0?route.get(i-1):boat;
        Projection projection=project(boat,begin,end);
        Vec3 on=lerp(begin,end,Math.max(0,Math.min(1,projection.t)));
        double remaining=horizontal(on,end),turn=i+1<route.size()?turn(begin,end,route.get(i+1)):0;
        for(double distance=Math.max(1,Math.min(16,lookahead));distance>=.5;distance*=.5) {
            double left=distance;Vec3 at=on;int cursor=i,steps=0;
            while(steps++<64) {
                Vec3 to=route.get(cursor);double length=horizontal(at,to);
                if(left<=length||cursor==route.size()-1) {
                    Vec3 candidate=length<1e-8?to:lerp(at,to,Math.min(1,left/length));
                    if(clear.test(boat,candidate))return new Aim(candidate,false,remaining,turn);
                    break;
                }
                left-=length;at=to;cursor++;
            }
        }
        return new Aim(boat,true,remaining,turn);
    }
    public static double speedCap(Aim aim,double yawError,double cruise) {
        if(aim.blocked)return 0;
        double limit=cruise;
        if(Math.abs(yawError)>70)limit=Math.min(limit,.035);
        else if(Math.abs(yawError)>35)limit=Math.min(limit,.12);
        if(aim.cornerDegrees>35&&aim.cornerDistance<10)
            limit=Math.min(limit,Math.max(.05,Math.min(.24,aim.cornerDistance*.035)));
        return limit;
    }
    public static double progress(List<Vec3> route,int index,Vec3 position) {
        if(route==null||route.size()<2)return 0;
        int target=Math.clamp(index,0,route.size()-1);double total=0,complete=0;
        for(int i=1;i<route.size();i++) {
            double d=horizontal(route.get(i-1),route.get(i));total+=d;
            if(i<target)complete+=d;
            else if(i==target)complete+=d*Math.max(0,Math.min(1,project(position,route.get(i-1),route.get(i)).t));
        }
        return total<1e-9?0:Math.max(0,Math.min(1,complete/total));
    }
    public static double horizontal(Vec3 a,Vec3 b){return Math.hypot(a.x-b.x,a.z-b.z);}
    private static Vec3 lerp(Vec3 a,Vec3 b,double t){return new Vec3(a.x+(b.x-a.x)*t,b.y,a.z+(b.z-a.z)*t);}
    private static double turn(Vec3 a,Vec3 p,Vec3 b) {
        double ax=p.x-a.x,az=p.z-a.z,bx=b.x-p.x,bz=b.z-p.z;
        double length=Math.hypot(ax,az)*Math.hypot(bx,bz);
        return length<1e-8?0:Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(ax*bx+az*bz)/length))));
    }
}
