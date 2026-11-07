package com.monpai.sailboatmod.transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import com.monpai.sailboatmod.transport.control.SweptTerrainProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRouteProgressionTest {
    private static Vec3 at(double x, double z) { return new Vec3(x, 0, z); }
    private static NativeRouteProgression.Selection select(List<Vec3> route, int index, Vec3 position) {
        return NativeRouteProgression.select(route, index, position, at(1, 0), 4.5, target -> true);
    }

    @Test void denseStraightRoutePassesSeveralPointsWithoutStopping() {
        var route = new ArrayList<Vec3>();
        for (int x = 0; x <= 30; x++) route.add(at(x, 0));
        var selected = select(route, 1, at(5.2, 0));
        assertEquals(10, selected.index());
        assertTrue(selected.target().x > 5.2);
    }

    @Test void straightOvershootCannotSendTheVehicleBackToAnAlreadyPassedPoint() {
        var route = List.of(at(0, 0), at(10, 0), at(20, 0), at(40, 0));
        assertTrue(select(route, 1, at(16, 0.4)).index() >= 2);
        assertTrue(select(route, 1, at(16, 0.4)).target().x > 16);
        assertEquals(1, select(route, 1, at(16, 8)).index(), "Off-route position is not proof of passing the point");
    }

    @Test void sharpCornerIsNotDiscardedFromTheLargeVehicleArrivalRadius() {
        var route = List.of(at(0, 0), at(10, 0), at(10, 10), at(10, 20));
        assertEquals(1, select(route, 1, at(6, 0)).index());
        assertEquals(2, select(route, 1, at(9.6, 0)).index());
        assertEquals(2, select(route, 1, at(12, 0)).index(), "A passed corner must not cause reversing back");
    }

    @Test void closeParallelReturnLegIsNotAShortcutAcrossAnUnreachedCorner() {
        var route = List.of(at(0, 0), at(10, 0), at(10, 3), at(0, 3));
        assertEquals(1, select(route, 1, at(6, 0)).index());
    }

    @Test void actualStationDepartureAlreadyOnOutgoingStraightDoesNotReverseToCorner() {
        var route = new ArrayList<Vec3>();
        route.add(new Vec3(9.5, 5.05, 39.5));
        for (int x = 9; x <= 51; x++) route.add(new Vec3(x + 0.5, 6.05, 40.5));
        route.add(new Vec3(51.5, 5.05, 39.5));
        Vec3 position = new Vec3(13.65, 7.48, 40.626);
        var selected = NativeRouteProgression.select(route, 0, position, Vec3.ZERO, 4.5, p -> true);
        assertTrue(selected.index() > 1, "Already on outgoing road, beyond the short station corner");
        assertTrue(selected.target().x > position.x);
        var command = NativeNavigationDriver.drive(new NativeNavigationDriver.Input(LogisticsCarrier.Kind.LAND,
                position, at(1, 0), Vec3.ZERO, selected.target(), 1.4, 4.5,
                selected.terrainReady(), false, true), false, false);
        assertEquals(15, command.forwardGear());
        assertEquals(0, command.reverseGear());
        assertEquals(0, command.brake());
    }

    @Test void outgoingCornerRecoveryStillRequiresClearLoadedTerrain() {
        var route = List.of(at(0, -1), at(0, 0), at(1, 0), at(2, 0), at(10, 0));
        var selected = NativeRouteProgression.select(route, 1, at(4, 0.1), Vec3.ZERO, 4.5, p -> false);
        assertEquals(1, selected.index());
        assertFalse(selected.terrainReady());
        assertTrue(selected.target().x > 4, "Probe the onward road without committing the blocked advance");
    }

    @Test void outgoingProjectionCannotJumpBeyondItsStraightRunAcrossAUturn() {
        var route = List.of(at(0, 0), at(10, 0), at(10, 3), at(0, 3));
        assertEquals(1, select(route, 1, at(10, 7)).index());
        assertEquals(1, select(route, 1, at(6, 3)).index());
    }

    @Test void nearbyReturnLegAndSelfIntersectionAreNotEvidenceOfPassingCorner() {
        var returning = List.of(at(0, 0), at(10, 0), at(10, 10), at(10.4, 10), at(10.4, 0));
        assertEquals(1, select(returning, 1, at(10.4, 5)).index());
        var crossing = List.of(at(0, 0), at(10, 0), at(10, 10), at(15, 10), at(15, 5), at(5, 5));
        assertEquals(1, select(crossing, 1, at(10, 5)).index());
    }

    @Test void shortTailInsideActualDestinationCanFinishWithoutChasingStationCorner() {
        var tail = List.of(at(20.5, 40.5), at(12.5, 40.5), at(11.5, 40.5),
                at(10.5, 40.5), at(9.5, 40.5), at(9.5, 39.5));
        java.util.function.Predicate<Vec3> station = p -> p.x >= 2.5 && p.x <= 14.5 && p.z >= 38.5 && p.z <= 50.5;
        assertTrue(NativeRouteProgression.canFinishInsideTerminal(tail, 1, at(13.65, 40.626), 9, station));
        assertFalse(NativeRouteProgression.canFinishInsideTerminal(tail, 1, at(15, 40.5), 9, station));
        assertFalse(NativeRouteProgression.canFinishInsideTerminal(tail, 0, at(13.65, 40.626), 9, station));
    }

    @Test void terminalPromotionCannotSkipLongLoopsOrAnOutsideExcursion() {
        java.util.function.Predicate<Vec3> station = p -> Math.abs(p.x) <= 10 && Math.abs(p.z) <= 10;
        assertFalse(NativeRouteProgression.canFinishInsideTerminal(
                List.of(at(0, 0), at(5, 0), at(5, 5), at(0, 5), at(0, 0)), 0, at(0, 0), 9, station));
        assertFalse(NativeRouteProgression.canFinishInsideTerminal(
                List.of(at(9, 0), at(11, 0), at(9, 0)), 0, at(9, 0), 9, station));
        assertFalse(NativeRouteProgression.canFinishInsideTerminal(
                Arrays.asList(at(0, 0), null), 0, at(0, 0), 9, station));
        assertFalse(NativeRouteProgression.canFinishInsideTerminal(
                List.of(at(0, 0)), 0, at(0, 0), Double.NaN, station));
    }

    @Test void terminalTailLengthIncludesVerticalDetours() {
        var route = List.of(at(0, 0), new Vec3(1, 12, 0), at(2, 0));
        assertFalse(NativeRouteProgression.canFinishInsideTerminal(route, 0, at(0, 0), 9, p -> true));
    }

    @Test void blockedTerminalShortcutKeepsDrivingAlongTheClearOriginalBend() {
        var route = List.of(at(0, 4), at(1.9, 4));
        var obstacle = new BlockPos(2, 0, 0);
        var probe = new NativeRouteProgression.TerrainPaths();
        for (int tick = 0; tick < 3; tick++) {
            var checked = new ArrayList<Vec3>();
            var selected = NativeRouteProgression.select(route, 0, Vec3.ZERO, Vec3.ZERO, 3,
                    target -> {
                        checked.add(target);
                        var swept = new AABB(-1.5, 0, -1.5, 1.5, 1, 1.5)
                                .expandTowards(target.normalize().scale(2)).deflate(0.1);
                        return probe.scan(target.equals(route.getLast()), swept, 8192, p -> true,
                                p -> p.equals(obstacle) && new AABB(p).intersects(swept))
                                == SweptTerrainProbe.Result.CLEAR;
                    }, p -> p.x >= -3 && p.x <= 3 && p.z >= -3 && p.z <= 5);
            assertEquals(List.of(route.getLast(), route.getFirst()), checked);
            assertEquals(0, selected.index());
            assertEquals(route.getFirst(), selected.target());
            assertTrue(selected.terrainReady());
            boolean finalLeg = selected.index() == route.size() - 1;
            var command = NativeNavigationDriver.drive(new NativeNavigationDriver.Input(LogisticsCarrier.Kind.LAND,
                    Vec3.ZERO, at(0, 1), Vec3.ZERO, selected.target(), 1.4, 3,
                    selected.terrainReady(), false, true), finalLeg, finalLeg);
            assertTrue(command.throttle() > 0);
            assertEquals(15, command.forwardGear());
            assertEquals(0, command.brake());
            assertFalse(command.arrived());
        }
    }

    @Test void blockedTerminalAndOriginalPathsHoldOriginalIndexWithoutClaimingArrival() {
        var route = List.of(at(0, 4), at(1.9, 4));
        var selected = NativeRouteProgression.select(route, 0, Vec3.ZERO, Vec3.ZERO, 3, p -> false, p -> true);
        assertEquals(0, selected.index());
        assertEquals(route.getFirst(), selected.target());
        assertFalse(selected.terrainReady());
        var command = NativeNavigationDriver.drive(new NativeNavigationDriver.Input(LogisticsCarrier.Kind.LAND,
                Vec3.ZERO, at(0, 1), Vec3.ZERO, selected.target(), 1.4, 3,
                selected.terrainReady(), false, true), false, false);
        assertEquals(15, command.brake());
        assertEquals(0, command.throttle());
        assertFalse(command.arrived());
    }

    @Test void clearTerminalShortcutStillReachesFinalParkingConfirmation() {
        var route = List.of(at(0, 4), at(1.9, 4));
        var checked = new ArrayList<Vec3>();
        var selected = NativeRouteProgression.select(route, 0, Vec3.ZERO, Vec3.ZERO, 3,
                target -> { checked.add(target); return true; }, p -> true);
        assertEquals(List.of(route.getLast()), checked);
        assertEquals(1, selected.index());
        assertTrue(selected.terrainReady());
        var command = NativeNavigationDriver.drive(new NativeNavigationDriver.Input(LogisticsCarrier.Kind.LAND,
                Vec3.ZERO, at(0, 1), Vec3.ZERO, selected.target(), 1.4, 3,
                selected.terrainReady(), false, true), true, true);
        assertEquals(15, command.brake());
        assertTrue(command.arrived());
    }

    @Test void terminalAndOriginalTerrainScansRetainTheirOwnBudgetProgressAcrossTicks() {
        var paths = new NativeRouteProgression.TerrainPaths();
        var terminalArea = new AABB(0, 0, 0, 3, 1, 1);
        var originalArea = new AABB(0, 0, 0, 1, 1, 3);
        for (int tick = 0; tick < 3; tick++) {
            var expected = tick < 2 ? SweptTerrainProbe.Result.WAITING : SweptTerrainProbe.Result.CLEAR;
            assertEquals(expected, paths.scan(true, terminalArea, 1, p -> true, p -> false));
            assertEquals(expected, paths.scan(false, originalArea, 1, p -> true, p -> false));
        }
    }

    @Test void closelySpacedGentleTurnsCannotTogetherEraseAnEntireBend() {
        var route = List.of(at(0, 0), at(1.530734, 0.304482), at(2.828427, 1.171573),
                at(3.695518, 2.469266), at(4, 4), at(4, 10));
        assertEquals(3, select(route, 1, at(0, 0)).index());
    }

    @Test void unloadedOrBlockedOnwardPathHoldsTheIndexForRetry() {
        var route = List.of(at(0, 0), at(1, 0), at(2, 0), at(10, 0));
        var checked = new ArrayList<Vec3>();
        var selected = NativeRouteProgression.select(route, 1, at(1.5, 0), at(1, 0), 4.5,
                target -> { checked.add(target); return false; });
        assertEquals(1, selected.index());
        assertFalse(selected.terrainReady());
        assertEquals(List.of(at(10, 0)), checked);
        var held = NativeNavigationDriver.drive(new NativeNavigationDriver.Input(LogisticsCarrier.Kind.WATER,
                at(1.5, 0), at(1, 0), at(1, 0), selected.target(), 4, 4.5,
                selected.terrainReady(), false, false), false, false);
        assertEquals(15, held.brake());
        assertEquals(0, held.throttle());
        assertFalse(held.arrived());
    }

    @Test void finalPointIsNeverDiscardedEvenAfterOvershoot() {
        var route = List.of(at(0, 0), at(10, 0));
        assertEquals(1, select(route, 1, at(20, 0)).index());
        assertEquals(at(10, 0), select(route, 0, at(20, 0)).target());
    }

    @Test void consecutiveDuplicatePointsDoNotIntroduceIdleTicks() {
        var route = List.of(at(0, 0), at(0, 0), at(0, 0), at(10, 0));
        assertEquals(3, select(route, 0, at(0, 0)).index());
    }

    @Test void malformedRouteAndPoseFailClosedBeforeRequestingTerrain() {
        var route = List.of(at(0, 0), at(10, 0));
        java.util.function.Predicate<Vec3> noRead = target -> { fail("Invalid route must not read terrain"); return true; };
        assertNull(NativeRouteProgression.select(route, -1, at(0, 0), at(1, 0), 4, noRead));
        assertNull(NativeRouteProgression.select(route, 2, at(0, 0), at(1, 0), 4, noRead));
        assertNull(NativeRouteProgression.select(route, 0, at(Double.NaN, 0), at(1, 0), 4, noRead));
        assertNull(NativeRouteProgression.select(route, 0, at(0, 0), at(1, Double.POSITIVE_INFINITY), 4, noRead));
        assertNull(NativeRouteProgression.select(route, 0, at(0, 0), at(1, 0), -1, noRead));
        assertNull(NativeRouteProgression.select(route, 0, at(0, 0), at(1, 0), Double.NaN, noRead));
        assertNull(NativeRouteProgression.select(Arrays.asList(at(0, 0), null), 0, at(0, 0), at(1, 0), 4, noRead));
    }
}
