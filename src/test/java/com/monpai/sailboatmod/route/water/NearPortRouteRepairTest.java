package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BiPredicate;
import static org.junit.jupiter.api.Assertions.*;

class NearPortRouteRepairTest {
    static final BlockPos START=new BlockPos(32258,65,-50119),JOIN=new BlockPos(32264,65,-50120),END=new BlockPos(32072,65,-50040);
    static RealBlockWaterMap map(BiPredicate<Integer,Integer> water) {
        RealBlockWaterMap map=new RealBlockWaterMap(null,63).retryUnknownPrediction();
        for(int cz=-3138;cz<=-3118;cz++)for(int cx=1996;cx<=2024;cx++) {
            boolean[] w=new boolean[256];int[] h=new int[256];Arrays.fill(h,64);
            for(int i=0;i<256;i++)w[i]=water.test(cx*16+(i&15),cz*16+(i>>4));
            map.seedChunk(cx,cz,new NbtChunkWaterReader.ChunkColumns(w,h));
        }
        return map;
    }
    static void verify(RealBlockWaterMap map,List<BlockPos> points) {
        assertEquals(START,points.getFirst());assertEquals(END,points.getLast());
        for(int i=1;i<points.size();i++)assertTrue(map.segmentHullClear(points.get(i-1),points.get(i),1),"bad edge "+i);
    }
    @Test void loggedNearPortMaskShortcutIsReplannedNotSentAsBadOptimizerInput()throws Exception {
        // A synthetic quay at the exact logged rejection cell. Port point and the six-block
        // east connector are valid; the subsequent mask-approved westbound shortcut is not.
        RealBlockWaterMap map=map((x,z)->!(x>=32245&&x<=32260&&z>=-50117&&z<=-50100));
        List<BlockPos> input=List.of(START,JOIN,END);List<Boolean> flags=List.of(false,true);
        assertTrue(map.segmentHullClear(START,JOIN,1));assertFalse(map.segmentHullClear(JOIN,END,1));
        var result=NearPortRouteRepair.repair(map,input,flags,1,null,null,2048);
        verify(map,result.path());assertEquals(1,result.repairs());assertEquals(result.path().size()-1,result.keep().size());
        assertEquals(input,List.of(START,JOIN,END),"input is never mutated");
        // Exercise the actual production entrypoint, including final optimization and validation.
        var finalPath=WaterAutoRouteService.finishGuidedRoute(map,input,flags,1,null,null,true);
        verify(map,finalPath);
        Path dir=Path.of("build/harbor-evidence");Files.createDirectories(dir);
        StringBuilder csv=new StringBuilder("stage,x,y,z\n");
        for(var p:input)csv.append("before,").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append('\n');
        for(var p:finalPath)csv.append("after,").append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append('\n');
        Files.writeString(dir.resolve("logged-quay-repair.csv"),csv.toString());
    }
    @Test void invalidIntermediateMaskAnchorIsMergedNotForcedIntoLand() {
        RealBlockWaterMap map=map((x,z)->!(x>=32220&&x<=32235&&z>=-50130&&z<=-50100));
        var p=List.of(START,new BlockPos(32225,65,-50115),END);
        var r=NearPortRouteRepair.repair(map,p,List.of(true,true),1,null,null,2048);
        verify(map,r.path());assertFalse(r.path().contains(p.get(1)));
    }
    @Test void invalidActualDestinationIsNeverDeletedOrMoved() {
        RealBlockWaterMap map=map((x,z)->x!=END.getX());
        var ex=assertThrows(StrictWaterDetour.Failure.class,()->NearPortRouteRepair.repair(map,List.of(START,END),List.of(true),1,null,null,2048));
        assertEquals(WaterRouteFailureReason.NO_WATER_PATH,ex.reason());
    }
    @Test void sealedHarbourFailsInsteadOfPublishingUnverifiedRoute() {
        RealBlockWaterMap map=map((x,z)->x>32210);
        assertThrows(StrictWaterDetour.Failure.class,()->NearPortRouteRepair.repair(map,List.of(START,END),List.of(true),1,null,null,2048));
    }
    @Test void pendingTerrainIsNotReportedAsBlockedAndCanBecomeReady() {
        RealBlockWaterMap map=new RealBlockWaterMap(null,63).retryUnknownPrediction();
        int[] waits={0};
        var points=List.of(new BlockPos(2,65,2),new BlockPos(10,65,2));
        var result=NearPortRouteRepair.repair(map,points,List.of(true),1,millis->{
            waits[0]++;boolean[] w=new boolean[256];Arrays.fill(w,true);int[] y=new int[256];Arrays.fill(y,64);
            map.seedChunk(0,0,new NbtChunkWaterReader.ChunkColumns(w,y));
        },null,2048);
        assertEquals(points,result.path());assertEquals(0,result.repairs());assertTrue(waits[0]>0);
    }
    @Test void waterHeightMismatchIsNotFixedByForcingSeaLevel() {
        RealBlockWaterMap map=map((x,z)->true);
        assertThrows(StrictWaterDetour.Failure.class,()->NearPortRouteRepair.repair(map,
                List.of(START,END.above()),List.of(true),1,null,null,2048));
    }
    @Test void cancellationPropagatesAndDeadlineCannotReturnAnOldBadPath() {
        RealBlockWaterMap map=map((x,z)->true);
        assertThrows(CancellationException.class,()->WaterRouteCancellation.run(()->true,()->
                NearPortRouteRepair.repair(map,List.of(START,END),List.of(true),1,null,null,2048)));
        assertThrows(StrictWaterDetour.Failure.class,()->StrictWaterDetour.find(map,START,END,1,null,0,100));
    }
    @Test void aLongSegmentPassingThroughPortRegionIsNotMissedByEndpointOnlyDistance() {
        assertTrue(NearPortRouteRepair.near(new BlockPos(-5000,65,0),new BlockPos(5000,65,0),new BlockPos(0,65,10),20));
    }
    @Test void uploadedPortCropHasStrictDetourAroundItsQuay()throws Exception {
        // Real uploaded junshi structure surface, no synthetic water outside the exported crop.
        List<String> rows=AegeanRouteRegressionTest.resourceLines("junshi-surface.txt");
        WaterRouteWorld world=WaterRouteDeferredEdgesTest.world((x,z)-> {
            if(x<0||z<0||x+1>=rows.getFirst().length()||z+1>=rows.size())return WaterColumn.blocked();
            char top=rows.get(z).charAt(x);
            if(!Character.isDigit(top))return WaterColumn.blocked();
            for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)if(rows.get(z+dz).charAt(x+dx)!=top)return WaterColumn.blocked();
            return WaterColumn.passable(new BlockPos(x,top-'0'+1,z),0);
        });
        BlockPos a=new BlockPos(73,2,108),b=new BlockPos(100,2,108);
        var result=StrictWaterDetour.find(world,a,b,null,System.nanoTime()+5_000_000_000L,10000);
        assertFalse(result.isEmpty());assertEquals(a,result.getFirst());assertEquals(b,result.getLast());
        for(int i=1;i<result.size();i++)assertEquals(StrictWaterDetour.Check.CLEAR,StrictWaterDetour.edge(world,result.get(i-1),result.get(i)));
    }
}
