package com.monpai.sailboatmod.transport;

import com.monpai.sailboatmod.transport.control.GroundTerrainWindow;
import com.monpai.sailboatmod.transport.control.SweptTerrainProbe.Result;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GroundTerrainWindowTest {
    private static final UUID MISSION = UUID.randomUUID(), BODY = UUID.randomUUID();
    private static AABB vibrating(double noise) {
        return new AABB(78.169406448, 82.00121657 + noise, 507.47664667 + noise,
                96.27104380, 87.71872647 + noise, 524.40745631 + noise);
    }
    private Result scan(GroundTerrainWindow window, long tick, AABB area, int budget,
            Predicate<BlockPos> loaded, BiPredicate<BlockPos,AABB> collision) {
        return window.scan(MISSION, BODY, "minecraft:overworld", tick, area, budget, loaded, collision);
    }
    private Result clear(GroundTerrainWindow window, long tick, AABB area, int budget) {
        return scan(window,tick,area,budget,p -> true,(p,b) -> false);
    }
    @Test void actualLargeAircraftMicroVibrationDoesNotRestartEveryPartialScan() {
        GroundTerrainWindow window=new GroundTerrainWindow(); Result result=Result.WAITING;
        for(int i=0;i<10 && result==Result.WAITING;i++) result=clear(window,i*5,vibrating(i*1e-8),2048);
        assertEquals(Result.CLEAR,result,"2052 actual cells must not get stuck at cursor 2048");
    }
    @Test void scansTheWholeConservativeWindowBeforeItsFirstClearAndKeepsTheBudget() {
        GroundTerrainWindow window=new GroundTerrainWindow();AtomicInteger calls=new AtomicInteger();
        Result result=scan(window,0,vibrating(0),2048,p -> {calls.incrementAndGet();return true;},(p,b) -> false);
        assertEquals(Result.WAITING,result);assertEquals(2048,calls.get());
        result=scan(window,5,vibrating(1e-8),2048,p -> {calls.incrementAndGet();return true;},(p,b) -> false);
        assertEquals(Result.CLEAR,result);assertTrue(calls.get()>2052,"Scan the full enclosing window, including its padding");
        assertTrue(calls.get()<=4096);
    }
    @Test void collisionPredicatesSeeOneStableEnvelopeIncludingUnrequestedCornerCells() {
        GroundTerrainWindow window=new GroundTerrainWindow();AABB request=vibrating(0);
        Result r=scan(window,0,request,32768,p -> true,(p,b) -> {
            assertTrue(b.minX<=request.minX && b.maxX>=request.maxX);
            return p.equals(new BlockPos(76,82,504));
        });
        assertEquals(Result.BLOCKED,r,"Clearance padding must not be treated as already checked");
    }
    @Test void completedWindowCanBridgeOnlyOneNormalFiveTickRefreshInterval() {
        GroundTerrainWindow window=new GroundTerrainWindow();AABB a=vibrating(0);
        assertEquals(Result.CLEAR,clear(window,0,a,32768));
        assertEquals(Result.CLEAR,clear(window,5,vibrating(1e-8),1));
        assertEquals(Result.WAITING,clear(window,6,vibrating(2e-8),1));
    }
    @Test void unknownChunkExceptionAndNewCollisionRevokeEvenAFreshPreviousClear() {
        GroundTerrainWindow window=new GroundTerrainWindow();AABB a=vibrating(0);
        assertEquals(Result.CLEAR,clear(window,0,a,32768));
        assertEquals(Result.WAITING,scan(window,5,a,1,p -> false,(p,b) -> false));
        assertEquals(Result.WAITING,clear(window,5,a,1));
        assertEquals(Result.CLEAR,clear(window,10,a,32768));
        assertEquals(Result.WAITING,scan(window,15,a,1,p -> true,(p,b) -> {throw new IllegalStateException("unavailable");}));
        assertEquals(Result.WAITING,clear(window,15,a,1));
        assertEquals(Result.CLEAR,clear(window,20,a,32768));
        assertEquals(Result.BLOCKED,scan(window,25,a,1,p -> true,(p,b) -> true));
    }
    @Test void movingOutsideChangingMissionBodyDimensionOrClockCannotReuseClearance() {
        GroundTerrainWindow window=new GroundTerrainWindow();AABB a=vibrating(0);
        assertEquals(Result.CLEAR,clear(window,20,a,32768));
        assertEquals(Result.WAITING,clear(window,25,a.move(-10,0,0),1));
        assertEquals(Result.CLEAR,clear(window,30,a,32768));
        assertEquals(Result.WAITING,window.scan(UUID.randomUUID(),BODY,"minecraft:overworld",35,a,1,p -> true,(p,b) -> false));
        assertEquals(Result.CLEAR,clear(window,40,a,32768));
        assertEquals(Result.WAITING,window.scan(MISSION,UUID.randomUUID(),"minecraft:overworld",45,a,1,p -> true,(p,b) -> false));
        assertEquals(Result.CLEAR,clear(window,50,a,32768));
        assertEquals(Result.WAITING,window.scan(MISSION,BODY,"minecraft:the_nether",55,a,1,p -> true,(p,b) -> false));
        assertEquals(Result.CLEAR,clear(window,60,a,32768));
        assertEquals(Result.WAITING,clear(window,59,a,1));
    }
    @Test void overlargeAndNonfiniteRequestsNeverBorrowAnEarlierClear() {
        GroundTerrainWindow window=new GroundTerrainWindow();
        assertEquals(Result.CLEAR,clear(window,0,vibrating(0),32768));
        assertEquals(Result.WAITING,clear(window,5,new AABB(0,81,0,2000,100,2000),32768));
        assertEquals(Result.WAITING,clear(window,5,new AABB(Double.NaN,81,0,10,100,10),32768));
        assertEquals(Result.WAITING,clear(window,5,vibrating(0),1));
    }
    @Test void changedContextRechecksTheAlreadyScannedPrefixEvenWithIdenticalBounds() {
        for (int changed=0; changed<4; changed++) {
            GroundTerrainWindow window=new GroundTerrainWindow();
            assertEquals(Result.WAITING,clear(window,20,vibrating(0),2048));
            Result result=window.scan(changed==0?UUID.randomUUID():MISSION,
                    changed==1?UUID.randomUUID():BODY,changed==2?"minecraft:the_nether":"minecraft:overworld",
                    changed==3?19:25,vibrating(0),2048,p -> true,
                    (p,b) -> p.equals(new BlockPos(76,82,504)));
            assertEquals(Result.BLOCKED,result,"A new context must not inherit its old checked prefix");
        }
    }
    @Test void expiryDoesNotStarveARefreshThatNeedsMoreThanTwoBatches() {
        GroundTerrainWindow window=new GroundTerrainWindow();AABB a=new AABB(1,81.1,1,31,89.1,31);
        assertEquals(Result.CLEAR,clear(window,0,a,32768));
        assertEquals(Result.CLEAR,clear(window,5,a,2048));
        assertEquals(Result.WAITING,clear(window,10,a,2048));
        assertEquals(Result.WAITING,clear(window,15,a,2048));
        assertEquals(Result.WAITING,clear(window,20,a,2048));
        assertEquals(Result.CLEAR,clear(window,25,a,2048));
    }
}
