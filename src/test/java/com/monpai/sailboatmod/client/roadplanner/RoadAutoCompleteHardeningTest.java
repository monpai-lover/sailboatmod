package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RoadAutoCompleteHardeningTest {
    static BlockPos p(int x,int z){return new BlockPos(x,72,z);}
    static FarTownRoadTerrain flat(){return new FarTownRoadTerrain(Integer.MAX_VALUE-2,Integer.MAX_VALUE-1,1,0);}
    @Test void missingConfiguredPathDoesNotInventStraightRoad() {
        var service=new RoadPlannerAutoCompleteService((a,b)->List.of());
        var result=service.complete(p(0,0),p(50000,0),List.of(),8);
        assertFalse(result.success());assertTrue(result.nodes().isEmpty());assertTrue(result.message().contains("未生成直线"));
    }
    @Test void cancelledSearchCannotTurnIntoFallback() {
        var service=new RoadPlannerAutoCompleteService((a,b)->{throw new CancellationException();});
        assertThrows(CancellationException.class,()->service.complete(p(0,0),p(5000,0),List.of(),8));
    }
    @Test void outputSamplingDoesNotSwallowCancellation() {
        var service=new RoadPlannerAutoCompleteService((a,b)->List.of(a,b),null,null,n->{throw new CancellationException();});
        assertThrows(CancellationException.class,()->service.complete(p(0,0),p(100,0),List.of(),8));
    }
    @Test void nestedBudgetsCannotExtendTheOuterDeadlineAndDoNotLeak() {
        var now=new AtomicLong();
        assertThrows(RoadSearchBudget.DeadlineExceeded.class,()->RoadSearchBudget.run(5,now::get,()->
                RoadSearchBudget.run(500,now::get,()->{now.set(6_000_000);RoadSearchBudget.check();return 0;})));
        RoadSearchBudget.check();
        assertEquals(1,RoadSearchBudget.run(5,now::get,()->1));
    }
    @Test void shortRouteAlsoHonoursDeadlineInsideTheFirstSearch() {
        var now=new AtomicLong();var terrain=new FarTownRoadTerrain(Integer.MAX_VALUE-2,Integer.MAX_VALUE-1,1,0) {
            @Override public int getHeight(int x,int z){now.addAndGet(1_000_000);return super.getHeight(x,z);}
        };
        var result=new LongRangeRoadPathfinder(new PathfindingConfig(),now::get).findPath(p(0,0),p(1000,0),terrain,10);
        assertTrue(result.deadlineHit());assertTrue(result.path().isEmpty());assertTrue(now.get()<100_000_000);
    }
    @Test void coarseStageHasTheSameInternalDeadline() {
        var now=new AtomicLong();var terrain=new FarTownRoadTerrain(Integer.MAX_VALUE-2,Integer.MAX_VALUE-1,1,0) {
            @Override public int getHeight(int x,int z){now.addAndGet(1_000_000);return super.getHeight(x,z);}
        };
        var result=new LongRangeRoadPathfinder(new PathfindingConfig(),now::get).findPath(p(0,0),p(50000,0),terrain,10);
        assertTrue(result.deadlineHit());assertTrue(result.path().isEmpty());assertTrue(now.get()<100_000_000);
    }
    @Test void allSelectableAlgorithmsRespondToInterruption() {
        for(var algorithm:PathfindingConfig.Algorithm.values()) {
            var c=new PathfindingConfig();c.setAlgorithm(algorithm);
            Thread.currentThread().interrupt();
            try {assertThrows(CancellationException.class,()->new LongRangeRoadPathfinder(c).findPath(p(0,0),p(5000,0),flat(),1000));}
            finally {Thread.interrupted();}
        }
    }
    @Test void previewSamplesRemainBoundedAndIncludeTheFarEndpoint() {
        var samples=RoadPlannerTerrainSampleCollector.collect(flat(),List.of(p(0,0),p(50000,0)));
        assertTrue(samples.size()<=4096);assertEquals(0,samples.getFirst().x());assertEquals(50000,samples.getLast().x());
    }
    @Test void manualInputIsBoundedBeforePathfinding() {
        var service=new RoadPlannerAutoCompleteService((a,b)->{fail("should not search");return List.of();});
        assertFalse(service.complete(p(0,0),p(100,0),Collections.nCopies(1025,p(0,0)),8).success());
    }
    @Test void terminalSnapDoesNotJumpAcrossAHiddenSeaStrip() {
        var c=new PathfindingConfig();c.setAStarStep(32);c.setElevationWeight(0);c.setStabilityWeight(0);
        var t=new FarTownRoadTerrain(50,70,100,0);
        var r=PathfinderFactory.create(c).findPath(p(0,0),p(96,0),t);assertTrue(r.success());
        for(int i=1;i<r.path().size();i++) {
            var a=r.path().get(i-1);var b=r.path().get(i);int n=(int)Math.ceil(Math.hypot(b.getX()-a.getX(),b.getZ()-a.getZ()));
            for(int j=0;j<=n;j++)assertFalse(t.isWater((int)Math.round(a.getX()+(b.getX()-(double)a.getX())*j/Math.max(1,n)),
                    (int)Math.round(a.getZ()+(b.getZ()-(double)a.getZ())*j/Math.max(1,n))));
        }
    }
    @Test void excludedEndpointIsNeverPublishedAsSuccessful() {
        var t=new FarTownRoadTerrain(20000,21000,1,0){@Override public boolean isBlocked(int x,int z){return x==96&&z==0;}};
        assertFalse(PathfinderFactory.create(new PathfindingConfig()).findPath(p(0,0),p(96,0),t).success());
    }
    @Test void staleMainThreadDeliveryCannotOverwriteNewRequest() throws Exception {
        try(var engine=new RoadPlannerAutoCompleteExecutor.Engine(1,2)) {
            var delivery=new LinkedBlockingQueue<Runnable>();var results=new CopyOnWriteArrayList<String>();var id=UUID.randomUUID();
            engine.submit(id,()->"old",delivery::add,results::add,()->fail("busy"));
            Runnable old=delivery.poll(5,TimeUnit.SECONDS);assertNotNull(old);
            engine.submit(id,()->"new",delivery::add,results::add,()->fail("busy"));
            Runnable next=delivery.poll(5,TimeUnit.SECONDS);assertNotNull(next);
            old.run();next.run();assertEquals(List.of("new"),results);assertEquals(0,engine.retained());
        }
    }
    @Test void manyQueuedReplacementsDoNotFillTheQueue() throws Exception {
        try(var engine=new RoadPlannerAutoCompleteExecutor.Engine(1,1)) {
            var started=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
            engine.submit(UUID.randomUUID(),()->{started.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}return 0;},Runnable::run,r->{},()->fail("busy"));
            assertTrue(started.await(5,TimeUnit.SECONDS));var id=UUID.randomUUID();var results=new CopyOnWriteArrayList<Integer>();
            for(int i=0;i<100;i++){int value=i;engine.submit(id,()->value,Runnable::run,r->{results.add(r);done.countDown();},()->fail("cancelled entries filled the queue"));}
            release.countDown();assertTrue(done.await(5,TimeUnit.SECONDS));assertEquals(List.of(99),results);
        }
    }
    @Test void logoutCancellationSuppressesAlreadyQueuedDelivery() throws Exception {
        try(var engine=new RoadPlannerAutoCompleteExecutor.Engine(1,1)) {
            var q=new LinkedBlockingQueue<Runnable>();var id=UUID.randomUUID();
            engine.submit(id,()->1,q::add,r->fail("stale delivery"),()->fail("busy"));
            var callback=q.poll(5,TimeUnit.SECONDS);assertNotNull(callback);engine.cancel(id);callback.run();assertEquals(0,engine.retained());
        }
    }
    @Test void searchConfigCopyKeepsCustomWeights() throws Exception {
        var c=new PathfindingConfig();c.setElevationWeight(9);c.setWaterDepthWeight(3);c.setHeuristicWeight(1.2);
        var p=new LongRangeRoadPathfinder(c);var method=LongRangeRoadPathfinder.class.getDeclaredMethod("pathfinder",int.class,int.class);method.setAccessible(true);
        var pf=method.invoke(p,32,1000);var field=pf.getClass().getDeclaredField("config");field.setAccessible(true);
        var copy=(PathfindingConfig)field.get(pf);assertEquals(9,copy.getElevationWeight());assertEquals(3,copy.getWaterDepthWeight());assertEquals(1.2,copy.getHeuristicWeight());assertEquals(32,copy.getAStarStep());
    }
}
