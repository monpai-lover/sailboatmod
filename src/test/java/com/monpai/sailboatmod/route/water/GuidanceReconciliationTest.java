package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import static org.junit.jupiter.api.Assertions.*;

/** LOD hints are not authoritative precise endpoints. No test permits walking through land. */
class GuidanceReconciliationTest {
    private static WaterRouteWorld world(BiFunction<Integer,Integer,WaterColumn> sample) {
        return new WaterRouteWorld() {
            public WaterColumn sample(int x,int z,WaterRoutePolicy p){return sample.apply(x,z);}
            public boolean canLoadMoreChunks(int n){return false;}
            public int consumedChunkLoads(){return 0;}
        };
    }
    private static WaterColumn wet(int x,int z){return WaterColumn.passable(new BlockPos(x,65,z),0);}
    private static HierarchicalWaterRoutePlanner planner(WaterRouteWorld detail) {
        return new HierarchicalWaterRoutePlanner(world(GuidanceReconciliationTest::wet),detail,
                new BlockPos(0,65,0),new BlockPos(7000,65,0),WaterRoutePolicy.nbtRefine());
    }
    private static BlockPos firstHint(HierarchicalWaterRoutePlanner p) {
        for(int i=0;i<1000&&p.isCoarseStage();i++)p.step(64);
        assertFalse(p.isCoarseStage(),"coarse stage must succeed");
        var a=p.coarseGuidance().get(0);var b=p.coarseGuidance().get(1);
        int n=Math.max(1,(int)Math.ceil(Math.sqrt(a.distSqr(b))/HierarchicalWaterRoutePlanner.DETAIL_SEGMENT_BLOCKS));
        return new BlockPos(a.getX()+(int)Math.round((b.getX()-a.getX())/(double)n),a.getY(),
                a.getZ()+(int)Math.round((b.getZ()-a.getZ())/(double)n));
    }
    private static void finish(HierarchicalWaterRoutePlanner p) {
        for(int i=0;i<30000&&(p.progress().status()==HierarchicalWaterRoutePlanner.Status.RUNNING
                ||p.progress().status()==HierarchicalWaterRoutePlanner.Status.WAITING);i++)p.step(64);
    }
    @Test void preciseLandAtInternalLodHintIsSkippedAndDetouredNotForcedIntoRoute() {
        var blocked=new AtomicReference<BlockPos>();
        var detail=world((x,z)->blocked.get()!=null&&Math.abs(x-blocked.get().getX())<=2&&Math.abs(z)<=2
                ?WaterColumn.blocked():wet(x,z));
        var p=planner(detail);blocked.set(firstHint(p));finish(p);
        assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS,p.progress().status());
        assertTrue(p.skippedGuidanceAnchors()>0);
        assertEquals(new BlockPos(0,65,0),p.path().getFirst());assertEquals(new BlockPos(7000,65,0),p.path().getLast());
        for(int i=1;i<p.path().size();i++){
            var a=p.path().get(i-1);var b=p.path().get(i);
            assertTrue(WaterGridTraversal.all(a.getX(),a.getZ(),b.getX(),b.getZ(),
                    (x,z)->detail.sample(x,z,WaterRoutePolicy.nbtRefine()).passable()));
        }
    }
    @Test void unknownIntermediateHintWaitsAndResumesWithoutBeingDeleted() {
        var pending=new AtomicReference<BlockPos>();var ready=new AtomicBoolean();
        var p=planner(world((x,z)->pending.get()!=null&&x==pending.get().getX()&&z==pending.get().getZ()&&!ready.get()?null:wet(x,z)));
        pending.set(firstHint(p));assertEquals(HierarchicalWaterRoutePlanner.Status.WAITING,p.step(64));
        assertEquals(0,p.skippedGuidanceAnchors());assertTrue(p.path().isEmpty());
        ready.set(true);finish(p);assertEquals(HierarchicalWaterRoutePlanner.Status.SUCCESS,p.progress().status());
    }
    @Test void realDestinationOnLandIsNeverSkipped() {
        var p=planner(world((x,z)->x==7000&&z==0?WaterColumn.blocked():wet(x,z)));
        finish(p);assertEquals(HierarchicalWaterRoutePlanner.Status.FAILED,p.progress().status());assertTrue(p.path().isEmpty());
    }
    @Test void realDestinationHeightIsNeverSilentlyMoved() {
        var p=planner(world((x,z)->WaterColumn.passable(new BlockPos(x,x==7000?66:65,z),0)));
        finish(p);assertEquals(HierarchicalWaterRoutePlanner.Status.FAILED,p.progress().status());assertTrue(p.path().isEmpty());
    }
    @Test void unknownRealDestinationCannotPublishPartialRoute() {
        var p=planner(world((x,z)->x==7000&&z==0?null:wet(x,z)));
        for(int i=0;i<500;i++)p.step(64);
        assertEquals(HierarchicalWaterRoutePlanner.Status.WAITING,p.progress().status());assertTrue(p.path().isEmpty());
        p.cancel();assertEquals(HierarchicalWaterRoutePlanner.Status.CANCELLED,p.progress().status());
    }
}
