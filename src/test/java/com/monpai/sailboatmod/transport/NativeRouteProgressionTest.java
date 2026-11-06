package com.monpai.sailboatmod.transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
