package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WaterRouteFrontierTest {
    @Test void higherCostReverseFrontCannotStarveForThousandsOfNodes() {
        WaterRouteWorld world = world((x,z) -> x > 8000 ? 10000 : 0, (x,z) -> x < 4990 || x > 5010);
        var policy = new WaterRoutePolicy(8, 2, 1, 3, 20000, 50000, 0, 128, 0, 1800);
        var search = new WaterRoutePathfinder(world, new BlockPos(0,63,0), new BlockPos(10000,63,0), policy);
        search.step(2000, 0);
        assertTrue(search.forwardExpansions() > 100);
        assertTrue(search.backwardExpansions() > 100, "higher-f side must still get service");
        assertEquals(search.expandedNodes(), search.forwardExpansions() + search.backwardExpansions());
    }

    @Test void shorelineEndpointUsesAReachableGridConnectorInsteadOfAnUncheckedSnap() {
        var world = world((x,z) -> 0, (x,z) -> z == 0 && (x == 0 || x >= 7 && x <= 40));
        var policy = new WaterRoutePolicy(8,2,1,3,256,5000,0,128,0,600);
        var search = new WaterRoutePathfinder(world, new BlockPos(7,63,0), new BlockPos(32,63,0), policy, 1);
        for (int i=0; i<100 && search.status()==WaterRoutePathfinder.Status.RUNNING; i++) search.step(64,0);
        assertEquals(WaterRoutePathfinder.Status.SUCCESS, search.status());
        assertEquals(new BlockPos(7,63,0), search.path().getFirst());
        assertTrue(search.path().stream().allMatch(p -> p.getX()>=7));
    }

    @Test void cancellationStillStopsBothFrontiers() {
        var search = new WaterRoutePathfinder(world((x,z)->0,(x,z)->true),
                new BlockPos(0,63,0),new BlockPos(10000,63,0),WaterRoutePolicy.nbtRefine());
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> WaterRouteCancellation.run(() -> true, () -> search.step(256,0)));
    }
    interface Cost { double at(int x,int z); }
    interface Water { boolean at(int x,int z); }
    static WaterRouteWorld world(Cost cost, Water water) {
        return new WaterRouteWorld() {
            public WaterColumn sample(int x,int z,WaterRoutePolicy policy) {
                return water.at(x,z)?WaterColumn.passable(new BlockPos(x,63,z),cost.at(x,z)):WaterColumn.blocked();
            }
            public boolean canLoadMoreChunks(int n){return false;}
            public int consumedChunkLoads(){return 0;}
        };
    }
}
