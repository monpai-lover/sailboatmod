package com.monpai.sailboatmod.route.water;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RouteStageBudgetTest {
    @Test void terrainWaitingDoesNotUseTheSearchAllowance() {
        AtomicLong clock = new AtomicLong();
        var budget = new RouteStageBudget(10,100,clock::get);
        clock.set(5_000_000); assertEquals(WaterRouteFailureReason.NONE,budget.advance(true));
        clock.addAndGet(90_000_000); assertEquals(WaterRouteFailureReason.NONE,budget.advance(false));
        clock.addAndGet(4_000_000); assertEquals(WaterRouteFailureReason.NONE,budget.advance(false));
        clock.addAndGet(2_000_000); assertEquals(WaterRouteFailureReason.TIMEOUT,budget.advance(false));
    }
    @Test void unavailableDataReportsTerrainNotReadyNotAnUnreachableOcean() {
        AtomicLong clock = new AtomicLong(); var budget=new RouteStageBudget(100,10,clock::get);
        budget.advance(true);clock.addAndGet(11_000_000);
        assertEquals(WaterRouteFailureReason.TERRAIN_NOT_READY,budget.advance(false));
    }
    @Test void aFreshDetailedStageGetsItsOwnBudget() {
        AtomicLong clock=new AtomicLong();var first=new RouteStageBudget(10,10,clock::get);
        clock.set(20_000_000);assertEquals(WaterRouteFailureReason.TIMEOUT,first.advance(false));
        var next=new RouteStageBudget(10,10,clock::get);
        assertEquals(WaterRouteFailureReason.NONE,next.advance(false));
    }
}
