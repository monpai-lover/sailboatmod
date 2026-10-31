package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic adversarial geometry, explicitly separate from the opt-in live Tellus tests. */
class LongWaterGeometryTest {
    interface Wet { boolean at(int x, int z); }
    static long key(int x,int z) { return ((long)x<<32)|(z&0xffffffffL); }
    static WaterRouteWorld world(Wet wet) {
        return new WaterRouteWorld() {
            public WaterColumn sample(int x,int z,WaterRoutePolicy p) {
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)if(!wet.at(x+dx,z+dz))return WaterColumn.blocked();
                return WaterColumn.passable(new BlockPos(x,65,z),0);
            }
            public boolean canLoadMoreChunks(int n){return false;}
            public int consumedChunkLoads(){return 0;}
        };
    }
    static List<BlockPos> run(String name,Wet wet,BlockPos start,BlockPos goal) throws Exception {
        long begun=System.nanoTime();
        var world=world(wet);
        var planner=new HierarchicalWaterRoutePlanner(world,world,start,goal,WaterRoutePolicy.nbtRefine());
        var status=HierarchicalWaterRoutePlanner.Status.RUNNING;
        for(int i=0;i<20_000&&(status==HierarchicalWaterRoutePlanner.Status.RUNNING||status==HierarchicalWaterRoutePlanner.Status.WAITING);i++) status=planner.step(64);
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS,status,name+": "+planner.failureReason()+" "+planner.progress());
        assertEquals(start,planner.path().getFirst());assertEquals(goal,planner.path().getLast());
        double length=0;
        for(int i=1;i<planner.path().size();i++) {
            var a=planner.path().get(i-1);var b=planner.path().get(i);
            double distance=Math.sqrt(a.distSqr(b));length+=distance;
            int samples=Math.max(1,(int)Math.ceil(distance*4));
            for(int j=0;j<=samples;j++) {
                int x=(int)Math.floor(a.getX()+0.5+(b.getX()-a.getX())*(double)j/samples);
                int z=(int)Math.floor(a.getZ()+0.5+(b.getZ()-a.getZ())*(double)j/samples);
                assertTrue(world.sample(x,z,WaterRoutePolicy.defaults()).passable(),name+" hull collision at "+x+","+z);
            }
        }
        assertTrue(length>=6999,"long-route coverage must not collapse to a short fixture");
        Path out=Path.of("build/route-regression");Files.createDirectories(out);
        Files.writeString(out.resolve("geometry-matrix.txt"),name+" SUCCESS lengthBlocks="+length+" "+planner.progress()+" ms="+(System.nanoTime()-begun)/1_000_000.0+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        return planner.path();
    }
    @Test void sevenKilometresThroughThirtyFourAlternatingIslands() throws Exception {
        run("7000m-multiple-islands",(x,z)-> {
            if(x<0||x>7010||z<-180||z>180)return false;
            for(int i=1;i<35;i++)if(Math.abs(x-i*200)<35&&Math.abs(z-(i%2==0?35:-35))<100)return false;
            return true;
        },new BlockPos(1,65,0),new BlockPos(7000,65,0));
    }
    @Test void twoBlockWideWaterwayOffEveryEvenGridLine() throws Exception {
        run("7000m-two-wide-odd-grid",(x,z)->x>=0&&x<=7010&&(z==3||z==4),new BlockPos(0,65,3),new BlockPos(7000,65,3));
    }
    @Test void twoBlockWideReverseNegativeCoordinateWaterway() throws Exception {
        run("7000m-two-wide-negative-reverse",(x,z)->x>=-7010&&x<=2&&(z==-3||z==-2),new BlockPos(0,65,-3),new BlockPos(-7000,65,-3));
    }
    @Test void sevenKilometresOfIrregularCurvedNarrowChannel() throws Exception {
        Set<Long> water=new HashSet<>();int previous=0;
        for(int x=-4;x<=7010;x++) {
            int z=(int)Math.round(40*Math.sin(x/120.0));
            for(int zz=Math.min(previous,z);zz<=Math.max(previous,z)+3;zz++)for(int dx=0;dx<3;dx++)water.add(key(x+dx,zz));
            previous=z;
        }
        run("7000m-irregular-curved-channel",(x,z)->water.contains(key(x,z)),new BlockPos(0,65,0),new BlockPos(7000,65,(int)Math.round(40*Math.sin(7000/120.0))));
    }
    @Test void sevenKilometresOfTwoWideRightAngleChannel() throws Exception {
        run("7000m-two-wide-right-angle", (x,z) ->
                (x>=0 && x<=3501 && z>=3 && z<=4)
                || (x>=3500 && x<=3501 && z>=3 && z<=3504),
                new BlockPos(0,65,3), new BlockPos(3500,65,3503));
    }
    @Test void genuineClosedBarrierCannotBeMadePassableForTheBenchmark() {
        var world=world((x,z)->x>=0&&x<=7100&&z>=0&&z<=30&&(x<3400||x>3600));
        var planner=new HierarchicalWaterRoutePlanner(world,world,new BlockPos(1,65,3),new BlockPos(7000,65,3),WaterRoutePolicy.nbtRefine());
        var status=HierarchicalWaterRoutePlanner.Status.RUNNING;
        for(int i=0;i<20_000&&(status==HierarchicalWaterRoutePlanner.Status.RUNNING||status==HierarchicalWaterRoutePlanner.Status.WAITING);i++)status=planner.step(64);
        assertEquals(HierarchicalWaterRoutePlanner.Status.FAILED,status);
        assertTrue(planner.path().isEmpty());
    }
    @Test void exactSupercoverMatchesIndependentRectangleIntersectionAndIsSymmetric() {
        for(int ex=-12;ex<=12;ex++)for(int ez=-12;ez<=12;ez++) {
            Set<Long> actual=new HashSet<>(),reverse=new HashSet<>(),expected=new HashSet<>();
            WaterGridTraversal.all(0,0,ex,ez,(x,z)->actual.add(key(x,z))||true);
            WaterGridTraversal.all(ex,ez,0,0,(x,z)->reverse.add(key(x,z))||true);
            for(int x=Math.min(0,ex)-1;x<=Math.max(0,ex)+1;x++)for(int z=Math.min(0,ez)-1;z<=Math.max(0,ez)+1;z++) {
                double[] t={0,1};
                if(axis(ex,x-0.5,x+0.5,t)&&axis(ez,z-0.5,z+0.5,t))expected.add(key(x,z));
            }
            assertEquals(expected,actual,"slope "+ex+","+ez);assertEquals(actual,reverse);
        }
    }
    static boolean axis(double direction,double lo,double hi,double[] t) {
        if(direction==0)return lo<=0&&hi>=0;
        double a=lo/direction,b=hi/direction;
        t[0]=Math.max(t[0],Math.min(a,b));t[1]=Math.min(t[1],Math.max(a,b));
        return t[0]<=t[1]+1e-12;
    }
    @Test void waterAtDifferentHeightsCannotBeConnectedByStrictFineSearch() {
        var world=new WaterRouteWorld() {
            public WaterColumn sample(int x,int z,WaterRoutePolicy p){return WaterColumn.passable(new BlockPos(x,x<32?65:66,z),0);}
            public boolean canLoadMoreChunks(int n){return false;}public int consumedChunkLoads(){return 0;}
        };
        var search=new WaterRoutePathfinder(world,new BlockPos(0,65,0),new BlockPos(64,66,0),new WaterRoutePolicy(8,1,1,3,128,2000,0,64,0,1800),1);
        for(int i=0;i<1000&&search.status()==WaterRoutePathfinder.Status.RUNNING;i++)search.step(64,0);
        assertNotEquals(WaterRoutePathfinder.Status.SUCCESS,search.status());assertTrue(search.path().isEmpty());
    }
}
