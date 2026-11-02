package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class WaterRouteDeferredEdgesTest {
    private static final WaterRoutePolicy POLICY=new WaterRoutePolicy(8,2,1,3,128,10_000,0,64,0,1800);
    @Test void permanentUnknownFirstNeighbourCannotBlockAnAlreadyKnownEastwardRoute() {
        var world=world((x,z)->x==0&&z==8?null:water(x,z));
        var path=new WaterRoutePathfinder(world,new BlockPos(0,63,0),new BlockPos(80,63,0),POLICY,1);
        complete(path);assertEquals(WaterRoutePathfinder.Status.SUCCESS,path.status());
        assertFalse(path.path().contains(new BlockPos(0,63,8)));
    }
    @Test void aSuspendedForwardEdgeDoesNotStarveTheReverseFront() {
        var path=new WaterRoutePathfinder(world((x,z)->x==8?null:water(x,z)),
                new BlockPos(0,63,0),new BlockPos(80,63,0),POLICY,1);
        for(int i=0;i<10;i++)path.step(16,0);
        assertTrue(path.backwardExpansions()>0);assertTrue(path.forwardExpansions()>0);
        assertEquals(path.expandedNodes(),path.forwardExpansions()+path.backwardExpansions());
    }
    @Test void allUnknownExitsWaitWithoutRecountingTheSameCompletedNodes() {
        var path=new WaterRoutePathfinder(world((x,z)->z==0&&(x==0||x==80)?water(x,z):null),
                new BlockPos(0,63,0),new BlockPos(80,63,0),POLICY,1);
        assertEquals(WaterRoutePathfinder.Status.WAITING,path.step(16,0));
        int before=path.expandedNodes();assertEquals(2,before);
        for(int i=0;i<100;i++)assertEquals(WaterRoutePathfinder.Status.WAITING,path.step(16,0));
        assertEquals(before,path.expandedNodes());assertTrue(path.path().isEmpty());
    }
    @Test void pendingCorridorCanResumeAndNeverBeSavedBeforeTheDataArrives() {
        AtomicBoolean ready=new AtomicBoolean();
        var path=new WaterRoutePathfinder(world((x,z)->z!=0?WaterColumn.blocked():x>=32&&x<=48&&!ready.get()?null:water(x,z)),
                new BlockPos(0,63,0),new BlockPos(80,63,0),POLICY,1);
        for(int i=0;i<30;i++)path.step(16,0);
        assertEquals(WaterRoutePathfinder.Status.WAITING,path.status());assertTrue(path.path().isEmpty());
        ready.set(true);complete(path);assertEquals(WaterRoutePathfinder.Status.SUCCESS,path.status());
    }
    @Test void cancellationStillStopsDeferredEdgeRetries() {
        var path=new WaterRoutePathfinder(world((x,z)->x==0||x==80?water(x,z):null),
                new BlockPos(0,63,0),new BlockPos(80,63,0),POLICY,1);
        path.step(16,0);
        assertThrows(java.util.concurrent.CancellationException.class,()->WaterRouteCancellation.run(()->true,()->path.step(16,0)));
    }
    private static WaterColumn water(int x,int z){return WaterColumn.passable(new BlockPos(x,63,z),0);}
    interface Sample{WaterColumn at(int x,int z);}
    static WaterRouteWorld world(Sample sample){return new WaterRouteWorld(){
        public WaterColumn sample(int x,int z,WaterRoutePolicy p){return sample.at(x,z);}
        public boolean canLoadMoreChunks(int n){throw new AssertionError("must not force chunks");}
        public int consumedChunkLoads(){return 0;}
    };}
    private static void complete(WaterRoutePathfinder p){for(int i=0;i<1000&&p.status()!=WaterRoutePathfinder.Status.SUCCESS&&p.status()!=WaterRoutePathfinder.Status.FAILED;i++)p.step(32,0);}
}
