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

    @Test void longColdRouteCanKeepMakingProgressBeyondCumulativeWaitAllowance() {
        AtomicLong clock=new AtomicLong();var budget=new RouteStageBudget(1000,10,clock::get);
        for(int i=1;i<=30;i++) {
            budget.advance(true);clock.addAndGet(8_000_000);
            assertEquals(WaterRouteFailureReason.NONE,budget.advance(false));
            clock.addAndGet(1_000_000);budget.completedExpansions(i);
            assertEquals(WaterRouteFailureReason.NONE,budget.advance(true));
        }
        // 240ms of successful async waits is not a 10ms stalled frontier.
    }
    @Test void repeatedlyPollingWithoutNewExpansionCannotRenewIdleBudget() {
        AtomicLong clock=new AtomicLong();var budget=new RouteStageBudget(1000,10,clock::get);
        budget.completedExpansions(1);budget.advance(true);
        clock.addAndGet(8_000_000);budget.completedExpansions(1);
        assertEquals(WaterRouteFailureReason.NONE,budget.advance(true));
        clock.addAndGet(3_000_000);budget.completedExpansions(1);
        assertEquals(WaterRouteFailureReason.TERRAIN_NOT_READY,budget.advance(true));
    }
    @Test void progressDoesNotIncreaseCpuAllowance() {
        AtomicLong clock=new AtomicLong();var budget=new RouteStageBudget(10,100,clock::get);
        clock.addAndGet(6_000_000);budget.completedExpansions(1);
        assertEquals(WaterRouteFailureReason.NONE,budget.advance(false));
        clock.addAndGet(6_000_000);budget.completedExpansions(2);
        assertEquals(WaterRouteFailureReason.TIMEOUT,budget.advance(false));
    }
    @Test void tricklingDataStillCannotExceedOriginalWallClockCeiling() {
        AtomicLong clock=new AtomicLong();var budget=new RouteStageBudget(100,10,clock::get);
        for(int i=1;i<=21;i++) {
            budget.advance(true);clock.addAndGet(5_000_000);
            assertEquals(WaterRouteFailureReason.NONE,budget.advance(false));
            budget.completedExpansions(i);
        }
        budget.advance(true);clock.addAndGet(5_000_000);budget.completedExpansions(22);
        assertEquals(WaterRouteFailureReason.TIMEOUT,budget.advance(false));
    }
}
