package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Local, forward-only rescue splices. A sparse 10km leg is not a demand to replan a 10km voyage.
 * No nearest-water teleport: the actual occupied hull must be water, or the boat stays stopped.
 */
public final class WaterRescuePlanner {
    public record Splice(List<Vec3> detour,int tailIndex) { }
    record Target(Vec3 point,int tailIndex,double ahead) { }
    private WaterRescuePlanner() { }

    static List<Target> targets(Vec3 boat,List<Vec3> route,int targetIndex) {
        List<Target> targets=new ArrayList<>();Set<BlockPos> used=new HashSet<>();
        int first=Math.max(0,targetIndex-1);double walked=0;
        for(int i=first;i+1<route.size()&&i<first+32&&walked<512;i++) {
            Vec3 a=route.get(i),b=route.get(i+1);double dx=b.x-a.x,dz=b.z-a.z,len=Math.hypot(dx,dz);
            if(len<1e-9)continue;
            double t=i==first?Math.clamp(((boat.x-a.x)*dx+(boat.z-a.z)*dz)/(len*len),0,1):0;
            double left=len*(1-t);
            for(double advance:new double[]{32,96,192,384,512}) {
                double inLeg=advance-walked;
                if(inLeg<=0||inLeg>=left)continue;
                double f=t+inLeg/len;
                Vec3 p=new Vec3(a.x+dx*f,a.y,a.z+dz*f);
                if(used.add(cell(p)))targets.add(new Target(p,i+1,advance));
            }
            if(walked+left<=512&&(walked+left>=8||i+2==route.size())&&used.add(cell(b)))
                targets.add(new Target(b,i+2,walked+left));
            walked+=left;
        }
        targets.sort(java.util.Comparator.comparingDouble(Target::ahead));return List.copyOf(targets);
    }
    public static WaterRouteResult<Splice> plan(RealBlockWaterMap map,Vec3 boat,List<Vec3> route,int targetIndex,
                                               WaterRoutePostProcessor.AwaitTerrain await) {
        if(boat==null||route==null||route.size()<2||targetIndex<0||targetIndex>=route.size())
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        long deadline=System.nanoTime()+45_000_000_000L;
        WaterRouteWorld world=StrictWaterDetour.world(map,1);
        try {
            WaterColumn startColumn=StrictWaterDetour.awaitColumn(world,cell(boat),await,deadline);
            if(!startColumn.passable()||startColumn.surfacePos()==null)return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
            BlockPos start=startColumn.surfacePos();
            int tried=0;
            for(Target target:targets(boat,route,targetIndex)) {
                if(tried++>=12)break;
                BlockPos goal=cell(target.point());
                WaterColumn at=StrictWaterDetour.awaitColumn(world,goal,await,deadline);
                if(!at.passable()||at.surfacePos()==null||!at.surfacePos().equals(goal)||goal.getY()!=start.getY())continue;
                List<BlockPos> repaired=StrictWaterDetour.find(world,start,goal,await,deadline,25_000);
                if(repaired.size()<2)continue;
                // Reject a reconnect which immediately heads into the same obstruction.
                if(target.tailIndex()<route.size()) {
                    Vec3 next=route.get(target.tailIndex());double d=Math.hypot(next.x-target.point().x,next.z-target.point().z);
                    double t=d==0?1:Math.min(1,32/d);
                    BlockPos check=cell(target.point().lerp(next,t));
                    if(StrictWaterDetour.awaitEdge(world,goal,check,await,deadline)!=StrictWaterDetour.Check.CLEAR)continue;
                }
                List<Vec3> path=new ArrayList<>();
                // Preserve the actual sub-block start. It occupies the verified start cell.
                path.add(new Vec3(boat.x,start.getY(),boat.z));
                for(BlockPos p:repaired){Vec3 v=Vec3.atBottomCenterOf(p);if(!v.equals(path.getLast()))path.add(v);}
                // The exact original-line reconnect lies in the checked final cell; retain it so
                // the unchanged suffix is not replaced by a slightly shifted kilometres-long line.
                if(!path.getLast().equals(target.point()))path.add(target.point());
                return WaterRouteResult.success(new Splice(List.copyOf(path),target.tailIndex()));
            }
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        } catch(StrictWaterDetour.Failure failure) { return WaterRouteResult.failure(failure.reason()); }
    }
    private static BlockPos cell(Vec3 p){return new BlockPos((int)Math.floor(p.x),(int)Math.floor(p.y),(int)Math.floor(p.z));}
}
