package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

/** Reconcile mask guidance with actual harbour columns BEFORE optional geometry optimization.
 * A postprocessor accepts valid input; it is deliberately not an obstacle search fallback.
 */
final class NearPortRouteRepair {
    record Repaired(List<BlockPos> path,List<Boolean> keep,int repairs) { }
    private NearPortRouteRepair() { }
    static Repaired repair(RealBlockWaterMap map,List<BlockPos> path,List<Boolean> keep,int halfWidth,
                            WaterRoutePostProcessor.AwaitTerrain await,
                            WaterRoutePostProcessor.Progress progress,int radius) {
        if(path==null||path.size()<2)throw new StrictWaterDetour.Failure(WaterRouteFailureReason.NO_WATER_PATH,"Missing harbour route");
        WaterRouteWorld world=StrictWaterDetour.world(map,halfWidth);
        List<BlockPos> out=new ArrayList<>(path);
        List<Boolean> flags=keep!=null&&keep.size()==path.size()-1?new ArrayList<>(keep)
                :new ArrayList<>(java.util.Collections.nCopies(path.size()-1,false));
        long deadline=System.nanoTime()+60_000_000_000L;
        BlockPos source=path.getFirst(),target=path.getLast();int repairs=0;
        for(int i=0;i+1<out.size();i++) {
            StrictWaterDetour.checkDeadline(deadline);
            BlockPos a=out.get(i),b=out.get(i+1);
            if(!near(a,b,source,radius)&&!near(a,b,target,radius))continue;
            if(StrictWaterDetour.awaitEdge(world,a,b,await,deadline)==StrictWaterDetour.Check.CLEAR)continue;
            if(++repairs>32)throw new StrictWaterDetour.Failure(WaterRouteFailureReason.NODE_BUDGET_EXCEEDED,"Too many harbour repairs");
            if(progress!=null)progress.update(89,"近港接驳 · 真实水域局部绕行 "+repairs);
            // A saved/generated intermediate hint may be land or lie in another basin. Try a
            // bounded number of later hints, but never move the actual starting/final berth.
            List<BlockPos> detour=List.of();int end=i+1;
            for(int j=i+1;j<out.size()&&j<=i+8;j++) {
                BlockPos candidate=out.get(j);
                if(Math.hypot((double)candidate.getX()-a.getX(),(double)candidate.getZ()-a.getZ())>4096)break;
                WaterColumn c=StrictWaterDetour.awaitColumn(world,candidate,await,deadline);
                if(!c.passable()||c.surfacePos()==null||c.surfacePos().getY()!=candidate.getY())continue;
                detour=StrictWaterDetour.find(world,a,candidate,await,deadline,60_000);
                if(!detour.isEmpty()){end=j;break;}
            }
            if(detour.isEmpty())throw new StrictWaterDetour.Failure(WaterRouteFailureReason.NO_WATER_PATH,"Blocked harbour connector "+a+" -> "+b);
            var next=new ArrayList<BlockPos>(out.subList(0,i));next.addAll(detour);next.addAll(out.subList(end+1,out.size()));
            var nextFlags=new ArrayList<Boolean>(flags.subList(0,i));
            nextFlags.addAll(java.util.Collections.nCopies(detour.size()-1,false));nextFlags.addAll(flags.subList(end,flags.size()));
            if(nextFlags.size()!=next.size()-1)throw new IllegalStateException("Harbour repair edge metadata mismatch");
            LogUtils.getLogger().info("[WaterPath] 近港接驳已局部重寻 {} -> {} 航点={} 跳过引导点={}",a,out.get(end),detour.size(),end-i-1);
            out=next;flags=nextFlags;i+=detour.size()-2;
        }
        if(!out.getFirst().equals(source)||!out.getLast().equals(target))throw new IllegalStateException("Harbour repair moved a berth");
        return new Repaired(List.copyOf(out),List.copyOf(flags),repairs);
    }
    static boolean near(BlockPos a,BlockPos b,BlockPos p,int radius) {
        double dx=(double)b.getX()-a.getX(),dz=(double)b.getZ()-a.getZ(),den=dx*dx+dz*dz;
        double t=den==0?0:Math.clamp(((p.getX()-a.getX())*dx+(p.getZ()-a.getZ())*dz)/den,0,1);
        double x=a.getX()+dx*t-p.getX(),z=a.getZ()+dz*t-p.getZ();
        return x*x+z*z<=(double)radius*radius;
    }
}
