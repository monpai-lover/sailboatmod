package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic tests of actual tracker/budget/planner; no sleeps or external network. */
class AdaptiveRouteBudgetTest {
    static void ms(AtomicLong c, long t) { c.set(t * 1_000_000L); }
    static RouteStageBudget budget(AtomicLong c) { return RouteStageBudget.adaptive(90_000, 60_000, c::get); }
    static RouteSearchProgress progress() { return new RouteSearchProgress(0,0,7623,0); }

    @Test void rootsCannotEarnProgress() {
        var p=progress();p.expanded(true,0,0);p.expanded(false,7623,0);
        assertEquals(0,p.snapshot().rewardMillis());
    }
    @Test void eachNew128BlocksEarnsEightSeconds() {
        var p=progress();p.expanded(true,128,0);
        assertEquals(8_000,p.snapshot().approachRewardMillis());
    }
    @Test void subThresholdMovementAccumulatesWithoutRoundingUp() {
        var p=progress();p.expanded(true,15,0);assertEquals(0,p.snapshot().approachRewardMillis());
        p.expanded(true,16,0);assertEquals(1_000,p.snapshot().approachRewardMillis());
    }
    @Test void bothRootedDirectionsCanEarnCredit() {
        var p=progress();p.expanded(true,128,0);p.expanded(false,7623-128,0);
        assertEquals(16_000,p.snapshot().approachRewardMillis());
    }
    @Test void movingAwayThenRevisitingCannotFarmMoreCredit() {
        var p=progress();p.expanded(true,256,0);long reward=p.snapshot().rewardMillis();
        for(int i=0;i<100_000;i++) {p.expanded(true,0,0);p.expanded(true,128,0);p.expanded(true,256,0);}
        assertEquals(reward,p.snapshot().rewardMillis());
    }
    @Test void refinementsReuseTheSameMilestones() {
        var p=progress();for(int x=0;x<=1024;x+=128)p.expanded(true,x,0);
        long before=p.snapshot().rewardMillis();
        for(int retry=0;retry<5;retry++)for(int x=0;x<=1024;x+=8)p.expanded(true,x,0);
        assertEquals(before,p.snapshot().rewardMillis());
    }
    @Test void necessaryAwayFromGoalDetourGetsFiniteExplorationCredit() {
        var p=progress();for(int i=1;i<=8;i++)p.expanded(true,-128*i,0);
        assertEquals(0,p.snapshot().approachRewardMillis());assertEquals(5_000,p.snapshot().detourRewardMillis());
    }
    @Test void explorationCannotExpandMemoryOrCreditForever() {
        var p=progress();for(int i=1;i<=100_000;i++)p.expanded(true,-128*i,0);
        assertEquals(96,p.snapshot().newRegions());assertEquals(60_000,p.snapshot().rewardMillis());
    }
    @Test void negativeCoordinateTilesUseFloorDivision() {
        var p=progress();p.expanded(true,-1,0);p.expanded(true,-128,0);
        assertEquals(1,p.snapshot().newRegions());p.expanded(true,-129,0);assertEquals(2,p.snapshot().newRegions());
    }
    @Test void forwardAndBackwardCannotEarnTheSameRegionTwice() {
        var p=progress();p.expanded(true,128,0);p.expanded(false,128,0);
        assertEquals(1,p.snapshot().newRegions());
    }
    @Test void approachCreditCannotExceedInitialSeparation() {
        var p=progress();p.expanded(true,7623,0);p.expanded(false,0,0);
        assertEquals(7623.0,p.snapshot().approachBlocks(),1e-9);
    }
    @Test void validAdvanceIncreasesWallAndActiveComputeAllowance() {
        AtomicLong c=new AtomicLong();var b=budget(c);var p=progress();p.expanded(true,128,0);b.reward(p.snapshot());
        assertEquals(158_000,b.wallLimitMillis());assertEquals(94_000,b.searchLimitMillis());assertEquals(8_000,b.awardedMillis());
    }
    @Test void healthyColdProgressSurvivesThePrevious150SecondDeadline() {
        AtomicLong c=new AtomicLong();var b=budget(c);var p=progress();
        for(int i=1;i<=30;i++) {
            assertEquals(WaterRouteFailureReason.NONE,b.advance(true));ms(c,i*8_000L);
            assertEquals(WaterRouteFailureReason.NONE,b.advance(false));p.expanded(true,i*128,0);b.reward(p.snapshot());
        }
        assertEquals(240_000,b.elapsedMillis());assertTrue(b.wallLimitMillis()>240_000);
    }
    @Test void nodeCountersAndPollingAloneCannotEarnTimeOrRenewAdaptiveIdle() {
        AtomicLong c=new AtomicLong();var b=budget(c);b.advance(true);
        for(int i=0;i<1_000_000;i++)b.completedExpansions(i);
        assertEquals(0,b.awardedMillis());ms(c,60_000);
        assertEquals(WaterRouteFailureReason.TERRAIN_NOT_READY,b.advance(true));
    }
    @Test void staleProgressReportCannotRenewIdleAllowance() {
        AtomicLong c=new AtomicLong();var b=budget(c);var p=progress();p.expanded(true,128,0);b.reward(p.snapshot());b.advance(true);
        ms(c,59_000);b.reward(p.snapshot());ms(c,60_000);
        assertEquals(WaterRouteFailureReason.TERRAIN_NOT_READY,b.advance(true));
    }
    @Test void lateResultsCannotResurrectAnExpiredBudget() {
        AtomicLong c=new AtomicLong();var b=budget(c);var p=progress();ms(c,151_000);p.expanded(true,7600,0);b.reward(p.snapshot());
        assertEquals(0,b.awardedMillis());assertEquals(WaterRouteFailureReason.TIMEOUT,b.advance(false));
    }
    @Test void earnedTimeStillHasAnEightMinuteStageCeiling() {
        AtomicLong c=new AtomicLong();var b=budget(c);var p=progress();p.expanded(true,7623,0);b.reward(p.snapshot());
        assertEquals(480_000,b.wallLimitMillis());assertEquals(330_000,b.awardedMillis());
        b.advance(true);ms(c,480_000);assertEquals(WaterRouteFailureReason.TIMEOUT,b.advance(true));
        assertTrue(b.diagnostic().contains("hard_stage_limit"));
    }
    @Test void activeComputeCanAtMostDoubleItsInitialAllowance() {
        AtomicLong c=new AtomicLong();var b=budget(c);var p=progress();p.expanded(true,7623,0);b.reward(p.snapshot());
        assertEquals(180_000,b.searchLimitMillis());ms(c,180_000);
        assertEquals(WaterRouteFailureReason.TIMEOUT,b.advance(false));assertTrue(b.diagnostic().contains("search_budget_exhausted"));
    }
    @Test void unusedGrantedTimeDoesNotMakeMissingTerrainLiveForever() {
        AtomicLong c=new AtomicLong();var b=budget(c);var p=progress();p.expanded(true,2048,0);b.reward(p.snapshot());b.advance(true);
        ms(c,61_000);assertEquals(WaterRouteFailureReason.TERRAIN_NOT_READY,b.advance(true));
        assertTrue(b.diagnostic().contains("no_geometric_progress"));
    }
    @Test void separatePlayersHaveIndependentBudgetsAndMilestones() {
        AtomicLong c=new AtomicLong();var a=budget(c);var b=budget(c);var p=progress();p.expanded(true,1024,0);a.reward(p.snapshot());
        assertTrue(a.awardedMillis()>0);assertEquals(0,b.awardedMillis());
    }
    @Test void listenerOnlyObservesActuallyExpandedReachableNodes() {
        var hits=new ArrayList<BlockPos>();
        WaterRouteWorld w=world((x,z)->z==0&&x>=0&&x<=7000);
        var pf=new WaterRoutePathfinder(w,new BlockPos(0,65,0),new BlockPos(7000,65,0),WaterRoutePolicy.nbtRefine(),1,
                (forward,pos)->{hits.add(pos);return WaterRouteFailureReason.NONE;});
        for(int i=0;i<1000&&(pf.status()==WaterRoutePathfinder.Status.RUNNING||pf.status()==WaterRoutePathfinder.Status.WAITING);i++)pf.step(64,0);
        assertEquals(WaterRoutePathfinder.Status.SUCCESS,pf.status());assertEquals(pf.expandedNodes(),hits.size());
        assertTrue(hits.stream().allMatch(p->p.getZ()==0&&p.getX()>=0&&p.getX()<=7000));
    }
    @Test void observerCanStopBeforeExpandingPastBudget() {
        var pf=new WaterRoutePathfinder(world((x,z)->true),new BlockPos(0,65,0),new BlockPos(7000,65,0),WaterRoutePolicy.nbtRefine(),1,
                (f,p)->WaterRouteFailureReason.TIMEOUT);
        assertEquals(WaterRoutePathfinder.Status.FAILED,pf.step(64,0));assertEquals(1,pf.expandedNodes());assertTrue(pf.path().isEmpty());
    }
    @Test void permanentUnknownHasZeroRewardAndNoFabricatedRoute() {
        AtomicLong c=new AtomicLong();WaterRouteWorld w=new WaterRouteWorld(){
            public WaterColumn sample(int x,int z,WaterRoutePolicy p){return null;}
            public boolean canLoadMoreChunks(int n){return false;}public int consumedChunkLoads(){return 0;}};
        var plan=new HierarchicalWaterRoutePlanner(w,w,new BlockPos(0,65,0),new BlockPos(7000,65,0),WaterRoutePolicy.nbtRefine(),p->p,c::get);
        assertEquals(HierarchicalWaterRoutePlanner.Status.WAITING,plan.step(64));ms(c,61_000);
        assertEquals(HierarchicalWaterRoutePlanner.Status.FAILED,plan.step(64));assertTrue(plan.path().isEmpty());
        assertTrue(plan.budgetDiagnostic().contains("grantedMs=0"));
    }
    @Test void entireTaskStillStopsAtTenMinutes() {
        AtomicLong c=new AtomicLong();var w=world((x,z)->true);
        var plan=new HierarchicalWaterRoutePlanner(w,w,new BlockPos(0,65,0),new BlockPos(7000,65,0),WaterRoutePolicy.nbtRefine(),p->p,c::get);
        plan.step(1);ms(c,600_000);assertEquals(HierarchicalWaterRoutePlanner.Status.FAILED,plan.step(1));assertTrue(plan.path().isEmpty());
    }
    @Test void cancellationRemainsImmediateEvenWithCredit() {
        var w=world((x,z)->true);var plan=new HierarchicalWaterRoutePlanner(w,w,new BlockPos(0,65,0),new BlockPos(7000,65,0),WaterRoutePolicy.nbtRefine());
        plan.step(4);plan.cancel();assertEquals(HierarchicalWaterRoutePlanner.Status.CANCELLED,plan.step(64));assertTrue(plan.path().isEmpty());
    }
    @Test void progressUiContainsGrantedAndRemainingTimeWithoutChangingPacket() {
        var w=world((x,z)->true);var plan=new HierarchicalWaterRoutePlanner(w,w,new BlockPos(0,65,0),new BlockPos(7000,65,0),WaterRoutePolicy.nbtRefine());
        plan.step(4);String text=HierarchicalWaterRoutePlanner.withBudget("Tellus粗寻",plan);
        assertTrue(text.contains("奖励+"));assertTrue(text.contains("余量"));assertTrue(text.startsWith("Tellus粗寻"));
    }
    @Test void realPlannerCompletesSevenKilometresWithSlowColdFrontierBeyond150Seconds() {
        AtomicLong c=new AtomicLong();
        var oldFixed=new RouteStageBudget(90_000,60_000,c::get);
        WaterRouteWorld w=new WaterRouteWorld(){
            public WaterColumn sample(int x,int z,WaterRoutePolicy p){
                // An irrelevant side strip remains unknown, so time between coarse slices
                // is true asynchronous waiting. The verified east-west corridor stays open.
                if(z==128)return null;
                return x>=-128&&x<=7200&&z>=-256&&z<=256
                        ? WaterColumn.passable(new BlockPos(x,65,z),0):WaterColumn.blocked();
            }
            public boolean canLoadMoreChunks(int n){return false;}public int consumedChunkLoads(){return 0;}
        };
        var plan=new HierarchicalWaterRoutePlanner(w,w,new BlockPos(0,65,0),new BlockPos(7000,65,0),WaterRoutePolicy.nbtRefine(),p->p,c::get);
        var status=HierarchicalWaterRoutePlanner.Status.RUNNING;
        WaterRouteFailureReason oldFailure=WaterRouteFailureReason.NONE;
        long maxGranted=0;int slices=0;
        while((status==HierarchicalWaterRoutePlanner.Status.RUNNING||status==HierarchicalWaterRoutePlanner.Status.WAITING)&&slices++<10000){
            if(oldFailure==WaterRouteFailureReason.NONE)oldFailure=oldFixed.advance(false);
            status=plan.step(plan.isCoarseStage()?1:64);
            if(plan.isCoarseStage()){
                oldFixed.completedExpansions(plan.progress().expandedNodes());
                if(oldFailure==WaterRouteFailureReason.NONE)oldFailure=oldFixed.advance(status==HierarchicalWaterRoutePlanner.Status.WAITING);
                c.addAndGet(3_000_000_000L);
            }
        }
        System.out.println("SLOW_7000_REPLAY elapsedMs="+c.get()/1_000_000+" status="+status+" legacy="+oldFailure+" "+plan.progress());
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS,status);
        assertTrue(c.get()>150_000_000_000L);assertTrue(c.get()<600_000_000_000L);
        assertEquals(WaterRouteFailureReason.TIMEOUT,oldFailure);
        assertEquals(new BlockPos(0,65,0),plan.path().getFirst());assertEquals(new BlockPos(7000,65,0),plan.path().getLast());
        for(int i=1;i<plan.path().size();i++){
            var a=plan.path().get(i-1);var b=plan.path().get(i);
            int n=Math.max(1,(int)Math.ceil(Math.sqrt(a.distSqr(b))*4));
            for(int j=0;j<=n;j++){
                int x=(int)Math.floor(a.getX()+.5+(b.getX()-a.getX())*(double)j/n);
                int z=(int)Math.floor(a.getZ()+.5+(b.getZ()-a.getZ())*(double)j/n);
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++){
                    var column=w.sample(x+dx,z+dz,WaterRoutePolicy.nbtRefine());
                    assertNotNull(column);assertTrue(column.passable());
                }
            }
        }
    }
    interface Wet {boolean at(int x,int z);}
    static WaterRouteWorld world(Wet wet) {return new WaterRouteWorld(){
        public WaterColumn sample(int x,int z,WaterRoutePolicy p){return wet.at(x,z)?WaterColumn.passable(new BlockPos(x,65,z),0):WaterColumn.blocked();}
        public boolean canLoadMoreChunks(int n){return false;}public int consumedChunkLoads(){return 0;}};}
}
