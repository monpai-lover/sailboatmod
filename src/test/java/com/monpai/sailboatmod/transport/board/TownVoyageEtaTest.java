package com.monpai.sailboatmod.transport.board;

import com.monpai.sailboatmod.transport.air.fuel.RailFlightDuration;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftMission;
import java.util.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.board.TownVoyageEta.*;

class TownVoyageEtaTest {
    static Vec3 point(double x, double z) { return new Vec3(x, 64, z); }
    static List<ProgressSample> moving() { return List.of(new ProgressSample("g", 100, 0), new ProgressSample("g", 300, 60)); }
    static RailAircraftPath.Leg leg(RailAircraftPath.Phase phase, double from, double to, double speed) {
        return new RailAircraftPath.Leg(phase, List.of(point(from, 0), point(to, 0)), speed, 0, phase == RailAircraftPath.Phase.CRUISE);
    }
    static RailAircraftPath airPath() {
        return RailAircraftPath.compile(List.of(leg(RailAircraftPath.Phase.TAXI_OUT, 0, 3, 3),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.HOLD_SHORT, List.of(point(3, 0)), 0, 0, false),
                leg(RailAircraftPath.Phase.LINE_UP, 3, 4, 3), leg(RailAircraftPath.Phase.TAKEOFF, 4, 104, 25),
                leg(RailAircraftPath.Phase.CRUISE, 104, 204, 25), leg(RailAircraftPath.Phase.APPROACH, 204, 304, 15),
                leg(RailAircraftPath.Phase.LANDING_ROLL, 304, 404, 15), leg(RailAircraftPath.Phase.TAXI_IN, 404, 414, 3),
                new RailAircraftPath.Leg(RailAircraftPath.Phase.STAND_STABILIZING, List.of(point(414, 0)), 0, 0, false)), -90);
    }
    @Test void xzSuffixArcProjectsOnCurrentSegmentAndIgnoresY() {
        var path = preprocessGround("g", List.of(point(0, 0), new Vec3(300, 1000, 0), point(300, 300))).orElseThrow();
        assertEquals(600, path.lengthBlocks());
        assertEquals(550, remainingGroundBlocks(path, 1, new Vec3(50, -1000, 200)).orElseThrow(), 1e-9);
        assertEquals(200, remainingGroundBlocks(path, 2, point(1000, 100)).orElseThrow(), 1e-9);
        assertEquals(300, remainingGroundBlocks(path, 1, point(400, 0)).orElseThrow(), 1e-9);
    }
    @Test void lookaheadTargetKeepsUntravelledStraightRoadInTheRemainingDistance() {
        var path = preprocessGround("g", straightRoad()).orElseThrow();
        Vec3 confirmed = point(2.25, 0.05);
        assertEquals(7.75, remainingGroundBlocks(path, 6, confirmed).orElseThrow(), 1e-9);
        assertEquals(7.75, remainingGroundBlocks(path, 7, confirmed).orElseThrow(), 1e-9);
    }
    @Test void advancingGuidanceAloneCannotGiveAStationaryCarrierAnEta() {
        var tracker = new TownCarrierVoyageTracker();
        List<Vec3> road = straightRoad();
        TownCarrierVoyageTracker.GeometryBudget budget = (id, points) -> true;
        assertTrue(tracker.ground(road, 6, point(2, 0), 100, true, budget).isEmpty());
        assertTrue(tracker.ground(road, 7, point(2, 0), 120, true, budget).isEmpty(),
                "Changing the control target must not count as physical travel");
        assertEquals(140, tracker.ground(road, 8, point(3, 0), 140, true, budget).orElseThrow());
    }
    @Test void lookbehindCannotUseAnEarlierCrossingOrTheOppositeRunOfAReversal() {
        var crossing = preprocessGround("g", List.of(point(-4, 0), point(0, 0), point(0, 4),
                point(4, 4), point(0, 0), point(4, 0))).orElseThrow();
        assertTrue(remainingGroundBlocks(crossing, 5, point(-2, 0)).isEmpty(),
                "The earlier straight section on the same line is separated by a loop");
        var reversal = preprocessGround("g", List.of(point(0, 0), point(4, 0), point(0, 0))).orElseThrow();
        assertTrue(remainingGroundBlocks(reversal, 2, point(4.25, 0)).isEmpty(),
                "A reversed run cannot borrow the earlier outbound direction");
    }
    private static List<Vec3> straightRoad() {
        List<Vec3> points = new ArrayList<>();
        for (int x = 0; x <= 10; x++) points.add(point(x, 0));
        return points;
    }
    @Test void duplicateZeroSegmentsAreValidAndPathInputsAreCopied() {
        var points = new ArrayList<>(List.of(point(0, 0), point(0, 0), point(10, 0)));
        var path = preprocessGround("g", points).orElseThrow(); points.clear();
        assertEquals(10, path.lengthBlocks());
        assertEquals(10, remainingGroundBlocks(path, 1, point(0, 0)).orElseThrow());
        assertEquals(5, remainingGroundBlocks(path, 2, point(5, 99)).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> path.points().clear());
    }
    @Test void realProgressAndUnitConversionGiveSixHundredBlocksAtPointThreeBlocksPerTick() {
        var path = preprocessGround("g", List.of(point(0, 0), point(660, 0))).orElseThrow();
        assertEquals(.3, effectiveSpeed(moving(), "g", 300, false).orElseThrow(), 1e-12);
        assertEquals(.3, metresPerSecondToBlocksPerTick(6).orElseThrow(), 1e-12);
        assertEquals(2000, ground(path, 1, point(60, 0), moving(), 300, false).orElseThrow());
        assertTrue(ground(path, 1, point(660, 0), moving(), 300, false).isEmpty());
    }
    @Test void fewerThanTwoSamplesStallBackwardsGeometryResetAndPauseGiveUnknown() {
        List<List<ProgressSample>> invalid = List.of(List.of(), List.of(new ProgressSample("g", 100, 0)),
                List.of(new ProgressSample("g", 100, 1), new ProgressSample("g", 300, 1)),
                List.of(new ProgressSample("g", 100, 2), new ProgressSample("g", 300, 1)),
                List.of(new ProgressSample("old", 100, 0), new ProgressSample("g", 300, 60)),
                List.of(new ProgressSample("g", 100, 0), new ProgressSample("g", 100, 60)),
                List.of(new ProgressSample("g", 100, 0), new ProgressSample("g", 300, Double.NaN)),
                List.of(new ProgressSample("g", 100, 0), new ProgressSample("g", 200, 0), new ProgressSample("g", 300, 60)));
        for (var samples : invalid) assertTrue(effectiveSpeed(samples, "g", 300, false).isEmpty());
        assertTrue(effectiveSpeed(moving(), "g", 300, true).isEmpty());
        assertTrue(effectiveSpeed(moving(), "g", 501, false).isEmpty());
        assertTrue(effectiveSpeed(moving(), "g", 299, false).isEmpty());
        assertTrue(effectiveSpeed(Collections.nCopies(MAX_PROGRESS_SAMPLES + 1, moving().getFirst()), "g", 300, false).isEmpty());
    }
    @Test void invalidGroundGeometryAndUnboundedArithmeticFailClosed() {
        assertTrue(preprocessGround("g", List.of()).isEmpty());
        assertTrue(preprocessGround("g", List.of(point(0, 0))).isEmpty());
        assertTrue(preprocessGround("g", List.of(point(0, 0), point(Double.NaN, 0))).isEmpty());
        assertTrue(preprocessGround("g", List.of(point(0, 0), point(Double.MAX_VALUE, 0))).isEmpty());
        assertTrue(preprocessGround("g", Collections.nCopies(MAX_PATH_POINTS + 1, point(0, 0))).isEmpty());
        var path = preprocessGround("g", List.of(point(0, 0), point(660, 0))).orElseThrow();
        assertTrue(remainingGroundBlocks(path, 0, point(0, 0)).isEmpty());
        assertTrue(remainingGroundBlocks(path, 2, point(0, 0)).isEmpty());
        assertTrue(remainingGroundBlocks(path, 1, point(Double.NaN, 0)).isEmpty());
        assertTrue(ground(path, 1, point(60, 0), List.of(new ProgressSample("g", 100, 0), new ProgressSample("g", 300, 1e-9)), 300, false).isEmpty());
        for (double speed : new double[]{-1, 0, Double.NaN, Double.POSITIVE_INFINITY}) assertTrue(metresPerSecondToBlocksPerTick(speed).isEmpty());
    }
    @Test void airUsesTheRealCompiledDurationIncludingFinalTaxiPlusActualStandRemainder() {
        var path = airPath(); var before = path.save();
        assertEquals(RailFlightDuration.estimateTicks(path, 0) + RailAircraftMission.REQUIRED_STAND_TICKS, air(path, 0, 0).orElseThrow());
        assertEquals(RailFlightDuration.estimateTicks(path, 404) + 40, air(path, 404, 0).orElseThrow());
        assertTrue(air(path, 404, 0).orElseThrow() > 40, "taxi-in remains in the movement budget");
        assertEquals(40, air(path, path.length(), 0).orElseThrow());
        assertEquals(1, air(path, path.length(), 39).orElseThrow());
        assertTrue(air(path, path.length(), 40).isEmpty(), "zero is unknown; completed rows are removed by the producer/service");
        assertEquals(before, path.save(), "ETA does not propose, commit, observe stand, or mutate mission inputs");
    }
    @Test void invalidAirInputsAndEstimatorStepBoundReturnUnknown() {
        var path = airPath();
        for (double distance : new double[]{-1, path.length() + 1, Double.NaN, Double.POSITIVE_INFINITY}) assertTrue(air(path, distance, 0).isEmpty());
        assertTrue(air(null, 0, 0).isEmpty());
        assertTrue(air(path, 0, -1).isEmpty());
        assertTrue(air(path, path.length(), 41).isEmpty());
        assertTrue(air(path, 0, 1).isEmpty(), "stand evidence before the actual tail is invalid");
        var slow = RailAircraftPath.compile(List.of(leg(RailAircraftPath.Phase.CRUISE, 0, 6000, .1)), -90);
        assertTrue(air(slow, 0, 0).isEmpty());
    }
}
