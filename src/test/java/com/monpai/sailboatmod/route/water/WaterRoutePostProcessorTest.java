package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class WaterRoutePostProcessorTest {
    interface Wet { boolean at(int x,int z); }
    static BlockPos p(int x,int z){return new BlockPos(x,65,z);}
    static WaterRoutePostProcessor.HullSampler hull(Wet wet) {
        return (x,z)->{for(int dx=0;dx<2;dx++)for(int dz=0;dz<2;dz++)if(!wet.at(x+dx,z+dz))return WaterColumn.blocked();
            return WaterColumn.passable(p(x,z),0);};
    }
    static WaterRoutePostProcessor.Result run(Wet wet,BlockPos... points) {
        var result=WaterRoutePostProcessor.optimize(hull(wet),List.of(points),null,null,WaterRoutePostProcessor.Options.defaults());
        assertEquals(points[0],result.path().getFirst());assertEquals(points[points.length-1],result.path().getLast());
        // Independent quarter-block checks rather than reusing WaterGridTraversal.
        check(result.path(),wet);return result;
    }
    static void check(List<BlockPos> route,Wet wet) {
        for(int i=1;i<route.size();i++){
            var a=route.get(i-1);var b=route.get(i);assertEquals(a.getY(),b.getY());
            int n=Math.max(1,(int)Math.ceil(Math.sqrt(a.distSqr(b))*4));
            for(int j=0;j<=n;j++){
                int x=(int)Math.floor(a.getX()+.5+(b.getX()-(double)a.getX())*j/n),z=(int)Math.floor(a.getZ()+.5+(b.getZ()-(double)a.getZ())*j/n);
                for(int dx=0;dx<2;dx++)for(int dz=0;dz<2;dz++)assertTrue(wet.at(x+dx,z+dz),"collision "+x+","+z);
            }
        }
    }
    @Test void openWaterZigzagsBecomeOneChord(){var r=run((x,z)->true,p(0,0),p(20,20),p(40,0),p(60,20),p(80,0));assertEquals(2,r.path().size());assertEquals(80,r.after().length(),1e-6);}
    @Test void exactClosedLoopRemoved(){var r=run((x,z)->true,p(0,0),p(20,0),p(20,20),p(40,20),p(40,0),p(20,0),p(100,0));assertTrue(r.loopsRemoved()>0);assertEquals(100,r.after().length(),1e-6);}
    @Test void geometricCrossingRemovedWithoutRequiringDuplicateVertex(){var r=run((x,z)->true,p(0,0),p(50,50),p(0,50),p(50,0),p(100,0));assertTrue(r.loopsRemoved()>0);assertEquals(0,r.after().hairpins());}
    @Test void repeatedAdjacentVerticesAreHarmless(){var r=run((x,z)->true,p(0,0),p(0,0),p(20,0),p(20,0));assertEquals(List.of(p(0,0),p(20,0)),r.path());}
    @Test void endpointReturningLoopPreservesEndpoints(){var r=run((x,z)->true,p(0,0),p(20,0),p(20,20),p(0,0));assertEquals(p(0,0),r.path().getLast());assertEquals(0,r.after().length(),1e-6);}
    @Test void islandIsNeverCut(){var r=run((x,z)->!(x>=40&&x<=60&&z>=-10&&z<=10),p(0,0),p(20,30),p(80,30),p(100,0));assertTrue(r.after().length()>100);}
    @Test void uShapedPeninsulaRequiresItsDetour(){Wet wet=(x,z)->!((x>=20&&x<=80&&z>=20&&z<=22)||(x>=20&&x<=22&&z>=-40&&z<=22)||(x>=78&&x<=80&&z>=-40&&z<=22));var r=run(wet,p(50,0),p(50,-50),p(90,-50),p(90,0));assertTrue(r.after().length()>110);}
    @Test void twoWideStraightCannotBePushedOntoLand(){var r=run((x,z)->z==3||z==4,p(0,3),p(32,3),p(100,3));assertEquals(List.of(p(0,3),p(100,3)),r.path());}
    @Test void twoWideRightAngleRetainsNecessaryCorner(){Wet wet=(x,z)->(x>=0&&x<=51&&z>=3&&z<=4)||(x>=50&&x<=51&&z>=3&&z<=100);var r=run(wet,p(0,3),p(50,3),p(50,99));assertTrue(r.after().maxTurnDegrees()>=89);}
    @Test void negativeCoordinatesRetainExactSafeWater(){var r=run((x,z)->z==-3||z==-2,p(0,-3),p(-100,-3),p(-7000,-3));assertEquals(7000,r.after().length(),1e-6);}
    @Test void shoreHuggingLineMovesOffshoreButDockEndsStayFixed(){var r=run((x,z)->z>=0,p(0,0),p(200,0));assertTrue(r.moved()>0);assertTrue(r.path().stream().mapToInt(BlockPos::getZ).max().orElseThrow()>=8);assertTrue(r.after().length()<=220);}
    @Test void shiftingCannotJumpOverThinParallelBank(){Wet wet=(x,z)->(z>=0&&z<=3)||z>=8;var r=run(wet,p(0,0),p(200,0));assertTrue(r.path().stream().allMatch(v->v.getZ()<=2));}
    @Test void heightDiscontinuityRejected(){assertThrows(IllegalArgumentException.class,()->WaterRoutePostProcessor.optimize((x,z)->WaterColumn.passable(new BlockPos(x,x<50?65:66,z),0),List.of(p(0,0),p(100,0)),null,null,WaterRoutePostProcessor.Options.defaults()));}
    @Test void actualStartAndGoalAreNotReassignedToNearestWater(){assertThrows(IllegalArgumentException.class,()->run((x,z)->x>0,p(0,0),p(100,0)));}
    @Test void unavailableShortcutRetainsKnownRoute(){var path=List.of(p(0,0),p(0,30),p(100,30),p(100,0));WaterRoutePostProcessor.HullSampler sampler=(x,z)->(x==0||x==100||z==30)?WaterColumn.passable(p(x,z),0):null;var r=WaterRoutePostProcessor.optimize(sampler,path,null,null,WaterRoutePostProcessor.Options.defaults());assertTrue(r.unknown()>0);assertTrue(r.after().length()>=159);}
    @Test void unknownNeverCachedAsWaterAfterRetry(){AtomicInteger waits=new AtomicInteger();WaterRoutePostProcessor.HullSampler sampler=(x,z)->waits.get()<2?null:WaterColumn.passable(p(x,z),0);var r=WaterRoutePostProcessor.optimize(sampler,List.of(p(0,0),p(30,0)),ms->waits.incrementAndGet(),null,WaterRoutePostProcessor.Options.defaults());assertEquals(30,r.after().length(),1e-6);assertTrue(waits.get()>=2);}
    @Test void permanentlyUnknownInputRejected(){assertThrows(IllegalArgumentException.class,()->WaterRoutePostProcessor.optimize((x,z)->null,List.of(p(0,0),p(30,0)),null,null,WaterRoutePostProcessor.Options.defaults()));}
    @Test void cancellationIsNeverConvertedToSafeFallback(){assertThrows(CancellationException.class,()->WaterRouteCancellation.run(()->true,()->run((x,z)->true,p(0,0),p(100,0))));}
    @Test void tinyBudgetDoesNotClaimInputWasValidated(){assertThrows(IllegalArgumentException.class,()->WaterRoutePostProcessor.optimize(hull((x,z)->true),List.of(p(0,0),p(100,0)),null,null,new WaterRoutePostProcessor.Options(8,24,1000,1)));}
    @Test void optionalBudgetKeepsCertifiedPath(){var input=List.of(p(0,0),p(20,20),p(40,0));var r=WaterRoutePostProcessor.optimize(hull((x,z)->true),input,null,null,new WaterRoutePostProcessor.Options(8,24,1000,200));assertTrue(r.budgetLimited());assertEquals(input.getFirst(),r.path().getFirst());check(r.path(),(x,z)->true);}
    @Test void invalidArgumentsRejected(){assertThrows(IllegalArgumentException.class,()->new WaterRoutePostProcessor.Options(17,24,100,10));assertThrows(IllegalArgumentException.class,()->run((x,z)->true,p(0,0)));}
    @Test void exactCompactionKeepsObliqueCorners(){assertEquals(3,WaterRoutePostProcessor.compact(List.of(p(0,0),p(10,1),p(20,5))).size());}
    @Test void outputIsImmutable(){var r=run((x,z)->true,p(0,0),p(100,0));assertThrows(UnsupportedOperationException.class,()->r.path().clear());}
    @Test void coldInputRequestsAheadInsteadOfStallingOnFirstMissingCell() {
        Set<Integer> requested=new HashSet<>(); boolean[] ready={false}; int[] waits={0};
        var result=WaterRoutePostProcessor.optimize((x,z)-> {
            if(!ready[0]){requested.add(x>>4);return null;}
            return WaterColumn.passable(p(x,z),0);
        },List.of(p(0,0),p(256,0)),millis->{
            waits[0]++;assertEquals(17,requested.size(),"must schedule missing chunks ahead, not just the first point");ready[0]=true;
        },null,WaterRoutePostProcessor.Options.defaults());
        assertEquals(1,waits[0]);assertEquals(256,result.after().length(),1e-6);
    }
    @Test void coldInputRetainsProgressAcrossBoundedIoBatches() {
        int[] ready={-1},waits={0};Set<Integer> requested=new HashSet<>();
        var result=WaterRoutePostProcessor.optimize((x,z)-> {
            if(x>=0&&x<=256&&x>ready[0]){requested.add(x>>4);return null;}
            return WaterColumn.passable(p(x,z),0);
        },List.of(p(0,0),p(128,0),p(256,0)),millis->{waits[0]++;ready[0]+=32;},null,
                WaterRoutePostProcessor.Options.defaults());
        assertEquals(17,requested.size());assertTrue(waits[0]>=8);assertEquals(256,result.after().length(),1e-6);
    }
    @Test void delayedInputLandIsRejectedNotHiddenByPreviouslyReadyCells() {
        boolean[] ready={false};
        assertThrows(IllegalArgumentException.class,()->WaterRoutePostProcessor.optimize((x,z)-> {
            if(x==12&&!ready[0])return null;
            return x==12?WaterColumn.blocked():WaterColumn.passable(p(x,z),0);
        },List.of(p(0,0),p(32,0)),millis->ready[0]=true,null,WaterRoutePostProcessor.Options.defaults()));
    }
    @Test void finalStageIsWiredAfterStitchingAndBeforeMetadata()throws Exception{String s=Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/route/water/WaterAutoRouteService.java"));int a=s.indexOf("path = finishWholeRoute(map, planner.path()");assertTrue(a>0);assertTrue(s.indexOf("metasOut.set(buildMetas(path",a)>a);assertTrue(s.contains("terrain == null ? null : terrain::awaitProgress"));}
}
