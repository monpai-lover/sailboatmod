package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TellusRoutePipelineTest {
    @Test void shortTellusRoutesNoLongerUseTheLegacyDenseCoarseLadder() {
        assertTrue(WaterAutoRouteService.useHierarchicalTerrain(true,7413));
        assertTrue(WaterAutoRouteService.useHierarchicalTerrain(true,64));
        assertFalse(WaterAutoRouteService.useHierarchicalTerrain(false,7413));
    }
    @Test void unavailableSavedNbtNeverFallsBackToPredictedOceanAndCanRetry() {
        AtomicBoolean ready=new AtomicBoolean();AtomicInteger predictions=new AtomicInteger();
        CoarseWaterRouteWorld world=CoarseWaterRouteWorld.realFirst((cx,cz)->ready.get()
                ?NbtChunkWaterReader.ReadResult.ready(columns(false)):NbtChunkWaterReader.ReadResult.unavailable(),
                (x,z,spacing)->{predictions.incrementAndGet();return Optional.empty();},63);
        assertNull(world.sample(1,1,WaterRoutePolicy.nbtCoarse()));
        ready.set(true);assertFalse(world.sample(1,1,WaterRoutePolicy.nbtCoarse()).passable());
        assertEquals(0,predictions.get());
    }
    @Test void streamingUnknownRemainsRetryableAfterOriginalPredictionBudgetExpires() throws Exception {
        AtomicBoolean ready=new AtomicBoolean();AtomicLong clock=new AtomicLong();
        var map=new RealBlockWaterMap(null,63).enableOnDemand().retryUnknownPrediction()
                .withPredictor(predictor((cx,cz)->ready.get()?Prediction.ready(columns(true)):Prediction.notReady()),
                        new PredictionBudget(1,clock::get));
        assertNull(map.sampleHullStrict(5,5,1));clock.set(10000);ready.set(true);Thread.sleep(70);
        assertTrue(map.sampleHullStrict(5,5,1).passable());
    }
    @Test void fineSearchNeverTrustsLandAroundABerth() {
        var map=new RealBlockWaterMap(null,63);map.seedChunk(0,0,columns(false));
        var world=RealBlockWaterWorld.streaming(map,63,1,new BlockPos(5,63,5),new BlockPos(12,63,12));
        assertFalse(world.sample(5,5,WaterRoutePolicy.nbtRefine()).passable());
    }
    @Test void strictFootprintDoesNotTreatUnknownNeighborAsNavigable() {
        var map=new RealBlockWaterMap(null,63);map.seedChunk(0,0,columns(true));
        assertNull(map.sampleHullStrict(15,5,1));
    }
    @Test void actualLandOverridesTellusOceanInFineSearch() {
        var map=new RealBlockWaterMap(null,63).enableOnDemand().retryUnknownPrediction()
                .withPredictor(predictor((cx,cz)->Prediction.ready(columns(true))),PredictionBudget.sixtySeconds());
        map.seedChunk(0,0,columns(false));assertFalse(map.sampleHullStrict(5,5,1).passable());
    }
    @Test void under8192RouteRunsBothCoarseAndDetailedStages() {
        AtomicInteger coarseCalls=new AtomicInteger(),fineCalls=new AtomicInteger();
        var coarse=WaterRouteFrontierTest.world((x,z)->{coarseCalls.incrementAndGet();return 0;},(x,z)->true);
        var fine=WaterRouteFrontierTest.world((x,z)->{fineCalls.incrementAndGet();return 0;},(x,z)->true);
        var planner=new HierarchicalWaterRoutePlanner(coarse,fine,new BlockPos(3,63,-17),new BlockPos(7416,63,-17),WaterRoutePolicy.nbtRefine());
        var status=HierarchicalWaterRoutePlanner.Status.RUNNING;
        for(int i=0;i<10000&&status==HierarchicalWaterRoutePlanner.Status.RUNNING;i++)status=planner.step(64);
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS,status);
        assertTrue(coarseCalls.get()>0);assertTrue(fineCalls.get()>0);
        assertEquals(new BlockPos(3,63,-17),planner.path().getFirst());
        assertEquals(new BlockPos(7416,63,-17),planner.path().getLast());
    }
    private static NbtChunkWaterReader.ChunkColumns columns(boolean water){boolean[] w=new boolean[256];Arrays.fill(w,water);int[] y=new int[256];Arrays.fill(y,62);return new NbtChunkWaterReader.ChunkColumns(w,y);}
    interface Answer {Prediction at(int x,int z);}
    private static TerrainPredictor predictor(Answer answer){return new TerrainPredictor(){
        public Prediction predictChunk(int x,int z,long wait){return answer.at(x,z);}
        public PrefetchGate gate(){return PrefetchGate.ALWAYS_READY;}
    };}
}
