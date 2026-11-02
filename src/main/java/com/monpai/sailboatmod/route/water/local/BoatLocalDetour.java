package com.monpai.sailboatmod.route.water.local;

import com.monpai.sailboatmod.route.WaterPolylineFollower;
import com.monpai.sailboatmod.route.water.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Small, ephemeral traffic/shape detours, NOT new persistent routes. The exact live hull,
 * block collision shapes and other vessels participate in every edge and shortcut check.
 */
public final class BoatLocalDetour {
    private record Target(Vec3 point,int tail) { }
    private record Node(int x,int z,double cost,double estimate,Node parent) { }
    private BoatLocalDetour() { }
    public static WaterRouteResult<WaterRescuePlanner.Splice> plan(BoatCollisionField field,Vec3 position,
                                                                   List<Vec3> route,int index) {
        if(route==null||route.size()<2||index<0||index>=route.size())return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        Vec3 start=new Vec3(position.x,route.get(index).y,position.z);
        boolean[] unknown={false};int[] budget={10_000};
        for(Target target:targets(start,route,index)) {
            WaterRouteCancellation.check();
            if(budget[0]<=0)break;
            if(target.tail<route.size()) {
                Vec3 next=route.get(target.tail);double distance=WaterPolylineFollower.horizontal(target.point,next);
                Vec3 check=target.point.lerp(next,distance<1e-8?1:Math.min(1,6/distance));
                BoatCollisionField.Check c=field.segment(target.point,check);
                if(c!=BoatCollisionField.Check.CLEAR){unknown[0]|=c==BoatCollisionField.Check.UNKNOWN;continue;}
            }
            List<Vec3> path=search(field,start,target.point,budget,unknown);
            if(!path.isEmpty())return WaterRouteResult.success(new WaterRescuePlanner.Splice(path,target.tail));
        }
        return WaterRouteResult.failure(unknown[0]?WaterRouteFailureReason.TERRAIN_NOT_READY:WaterRouteFailureReason.NO_WATER_PATH);
    }
    private static List<Target> targets(Vec3 start,List<Vec3> route,int index) {
        List<Target> out=new ArrayList<>();double walked=0;
        for(int i=index;i<route.size()&&i<index+32&&walked<=48;i++) {
            Vec3 begin=i==0?start:route.get(i-1),end=route.get(i);
            Vec3 on=i==index?begin.lerp(end,Math.clamp(WaterPolylineFollower.project(start,begin,end).t(),0,1)):begin;
            double length=WaterPolylineFollower.horizontal(on,end);
            for(double advance:new double[]{8,16,24,32,40}) {
                double left=advance-walked;
                if(left>0&&left<length) {
                    Vec3 p=on.lerp(end,left/length);
                    if(inWindow(start,p))out.add(new Target(p,i));
                }
            }
            if(length+walked>=2&&inWindow(start,end))out.add(new Target(end,i+1));
            walked+=length;
        }
        // Prefer a reconnect past the whole obstacle, not repeatedly inserting a point in front of it.
        out.sort(Comparator.comparingDouble((Target t)->WaterPolylineFollower.horizontal(start,t.point)).reversed());
        return out.stream().distinct().limit(12).toList();
    }
    private static boolean inWindow(Vec3 a,Vec3 b){return Math.max(Math.abs(a.x-b.x),Math.abs(a.z-b.z))<=LoadedBoatCollisionField.RADIUS-3;}
    private static List<Vec3> search(BoatCollisionField field,Vec3 start,Vec3 goal,int[] budget,boolean[] unknown) {
        if(field.segment(start,goal)==BoatCollisionField.Check.CLEAR)return List.of(start,goal);
        int radius=LoadedBoatCollisionField.RADIUS-2,side=radius*2+1;
        double[] best=new double[side*side];Arrays.fill(best,Double.POSITIVE_INFINITY);
        PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::estimate).thenComparingInt(Node::x).thenComparingInt(Node::z));
        open.add(new Node(0,0,0,WaterPolylineFollower.horizontal(start,goal),null));best[radius*side+radius]=0;
        while(!open.isEmpty()&&budget[0]>0) {
            WaterRouteCancellation.check();Node n=open.poll();int cell=(n.z+radius)*side+n.x+radius;
            if(n.cost>best[cell]+1e-8)continue;
            budget[0]--;Vec3 at=start.add(n.x,0,n.z);
            if(WaterPolylineFollower.horizontal(at,goal)<=1.5&&field.segment(at,goal)==BoatCollisionField.Check.CLEAR) {
                List<Vec3> path=new ArrayList<>();path.add(goal);
                for(Node p=n;p!=null;p=p.parent)path.add(start.add(p.x,0,p.z));
                Collections.reverse(path);return pull(field,path);
            }
            for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++) {
                if(dx==0&&dz==0)continue;
                int x=n.x+dx,z=n.z+dz;if(Math.abs(x)>radius||Math.abs(z)>radius)continue;
                double cost=n.cost+Math.hypot(dx,dz);int dest=(z+radius)*side+x+radius;
                if(cost>=best[dest]-1e-8)continue;
                Vec3 next=start.add(x,0,z);var check=field.segment(at,next);
                if(check!=BoatCollisionField.Check.CLEAR){unknown[0]|=check==BoatCollisionField.Check.UNKNOWN;continue;}
                best[dest]=cost;open.add(new Node(x,z,cost,cost+WaterPolylineFollower.horizontal(next,goal),n));
            }
        }
        return List.of();
    }
    private static List<Vec3> pull(BoatCollisionField field,List<Vec3> raw) {
        List<Vec3> out=new ArrayList<>();out.add(raw.getFirst());
        for(int i=0;i<raw.size()-1;) {
            WaterRouteCancellation.check();int chosen=i+1;
            for(int j=raw.size()-1;j>i+1;j--)if(field.segment(raw.get(i),raw.get(j))==BoatCollisionField.Check.CLEAR){chosen=j;break;}
            if(WaterPolylineFollower.horizontal(out.getLast(),raw.get(chosen))>1e-8)out.add(raw.get(chosen));i=chosen;
        }
        return List.copyOf(out);
    }
}
