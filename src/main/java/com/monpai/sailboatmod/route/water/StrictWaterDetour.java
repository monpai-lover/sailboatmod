package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/** Worker-only, same-height 2x2 local repair. Unknown terrain is retried, never relabelled water.
 * Shares the exact supercover used by final validation. No legacy noise/BFS repair, no world writes.
 */
public final class StrictWaterDetour {
    public enum Check { CLEAR, BLOCKED, UNKNOWN }
    public static final class Failure extends IllegalArgumentException {
        private final WaterRouteFailureReason reason;
        public Failure(WaterRouteFailureReason reason, String detail) { super(detail); this.reason=reason; }
        public WaterRouteFailureReason reason() { return reason; }
    }
    private StrictWaterDetour() { }

    public static WaterRouteWorld world(RealBlockWaterMap map, int halfWidth) {
        return new WaterRouteWorld() {
            @Override public WaterColumn sample(int x,int z,WaterRoutePolicy policy) { return map.sampleHullStrict(x,z,halfWidth); }
            @Override public boolean canLoadMoreChunks(int n) { return false; }
            @Override public int consumedChunkLoads() { return 0; }
        };
    }
    static WaterColumn column(WaterRouteWorld world, BlockPos point) {
        WaterRouteCancellation.check();
        return world.sample(point.getX(),point.getZ(),WaterRoutePolicy.nbtRefine());
    }
    public static Check edge(WaterRouteWorld world,BlockPos a,BlockPos b) {
        if(a==null||b==null||a.getY()!=b.getY())return Check.BLOCKED;
        boolean[] pending={false};
        boolean clear=WaterGridTraversal.all(a.getX(),a.getZ(),b.getX(),b.getZ(),(x,z)->{
            WaterColumn c=world.sample(x,z,WaterRoutePolicy.nbtRefine());
            if(c==null){pending[0]=true;return true;}
            return c.passable()&&c.surfacePos()!=null&&c.surfacePos().getY()==a.getY();
        });
        return !clear?Check.BLOCKED:pending[0]?Check.UNKNOWN:Check.CLEAR;
    }
    static Check awaitEdge(WaterRouteWorld world,BlockPos a,BlockPos b,
                           WaterRoutePostProcessor.AwaitTerrain await,long deadline) {
        while(true) {
            checkDeadline(deadline);
            Check c=edge(world,a,b);
            if(c!=Check.UNKNOWN)return c;
            waitTerrain(await,deadline);
        }
    }
    static WaterColumn awaitColumn(WaterRouteWorld world,BlockPos p,
                                    WaterRoutePostProcessor.AwaitTerrain await,long deadline) {
        while(true){checkDeadline(deadline);WaterColumn c=column(world,p);if(c!=null)return c;waitTerrain(await,deadline);}
    }
    static void checkDeadline(long deadline) {
        WaterRouteCancellation.check();
        if(System.nanoTime()>=deadline)throw new Failure(WaterRouteFailureReason.TIMEOUT,"Local water repair deadline exceeded");
    }
    static void waitTerrain(WaterRoutePostProcessor.AwaitTerrain await,long deadline) {
        checkDeadline(deadline);
        if(await!=null)await.await(10);else LockSupport.parkNanos(10_000_000L);
    }

    public static List<BlockPos> find(RealBlockWaterMap map,BlockPos start,BlockPos goal,int halfWidth,
                                      WaterRoutePostProcessor.AwaitTerrain await,long deadline,int maxNodes) {
        return find(world(map,halfWidth),start,goal,await,deadline,maxNodes);
    }
    static List<BlockPos> find(WaterRouteWorld terrain,BlockPos start,BlockPos goal,
                               WaterRoutePostProcessor.AwaitTerrain await,long deadline,int maxNodes) {
        checkDeadline(deadline);
        if(start==null||goal==null||start.getY()!=goal.getY())return List.of();
        double distance=Math.hypot((double)goal.getX()-start.getX(),(double)goal.getZ()-start.getZ());
        if(distance>4096)throw new Failure(WaterRouteFailureReason.RANGE_EXCEEDED,"Local repair window exceeds 4096 blocks");
        if(awaitEdge(terrain,start,start,await,deadline)==Check.BLOCKED
                ||awaitEdge(terrain,goal,goal,await,deadline)==Check.BLOCKED)return List.of();
        if(awaitEdge(terrain,start,goal,await,deadline)==Check.CLEAR)return List.of(start,goal);
        int margin=Math.max(96,Math.min(512,(int)Math.ceil(distance)));
        long minX=(long)Math.min(start.getX(),goal.getX())-margin,maxX=(long)Math.max(start.getX(),goal.getX())+margin;
        long minZ=(long)Math.min(start.getZ(),goal.getZ())-margin,maxZ=(long)Math.max(start.getZ(),goal.getZ())+margin;
        WaterRouteWorld bounded=new WaterRouteWorld() {
            public WaterColumn sample(int x,int z,WaterRoutePolicy p) {
                checkDeadline(deadline);
                return x<minX||x>maxX||z<minZ||z>maxZ?WaterColumn.blocked():terrain.sample(x,z,p);
            }
            public boolean canLoadMoreChunks(int n){return false;}
            public int consumedChunkLoads(){return 0;}
        };
        int used=0;
        for(int step:new int[]{8,4,2,1}) {
            if(used>=maxNodes)break;
            WaterRoutePolicy policy=new WaterRoutePolicy(step,1,1,3,(int)Math.ceil(distance+2*margin),maxNodes-used,0,64,0,1200);
            WaterRoutePathfinder search=new WaterRoutePathfinder(bounded,start,goal,policy,1);
            while(search.status()==WaterRoutePathfinder.Status.RUNNING||search.status()==WaterRoutePathfinder.Status.WAITING) {
                checkDeadline(deadline);
                WaterRoutePathfinder.Status status=search.step(64,0);
                if(status==WaterRoutePathfinder.Status.WAITING)waitTerrain(await,deadline);
            }
            used+=search.expandedNodes();
            if(search.status()==WaterRoutePathfinder.Status.SUCCESS) {
                List<BlockPos> path=search.path();
                if(!path.getFirst().equals(start)||!path.getLast().equals(goal))return List.of();
                for(int i=1;i<path.size();i++)if(awaitEdge(bounded,path.get(i-1),path.get(i),await,deadline)!=Check.CLEAR)return List.of();
                // Pull only already-known clear shortcuts; optional off-path IO cannot hold up repair.
                List<BlockPos> pulled=new ArrayList<>();pulled.add(start);
                for(int i=0;i<path.size()-1;) {
                    checkDeadline(deadline);int chosen=i+1;
                    for(int j=Math.min(path.size()-1,i+64);j>i+1;j--)
                        if(edge(bounded,path.get(i),path.get(j))==Check.CLEAR){chosen=j;break;}
                    pulled.add(path.get(chosen));i=chosen;
                }
                return List.copyOf(pulled);
            }
            if(search.failureReason()!=WaterRouteFailureReason.NO_WATER_PATH)break;
        }
        return List.of();
    }
}
