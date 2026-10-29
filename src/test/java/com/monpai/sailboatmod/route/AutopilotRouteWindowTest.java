package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.route.repair.RoutePatch;
import com.monpai.sailboatmod.route.water.lookahead.RouteSplicer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutopilotRouteWindowTest {
    @Test
    void fullRouteRetainsEveryTurnAndTheTrueDestinationBeyond512Points() {
        List<Vec3> original = route(3000);
        assertEquals(original, AutopilotRouteWindow.complete(original));
        assertEquals(original.getLast(), AutopilotRouteWindow.complete(original).getLast());
    }

    @Test
    void stableOverlappingWindowsHaveBoundedCopiesAndGlobalCursorMapping() {
        List<Vec3> full = route(3000);
        for (int target = 0; target < full.size(); target += 137) {
            AutopilotRouteWindow.Window window = AutopilotRouteWindow.around(full, target);
            assertTrue(window.points().size() <= AutopilotRouteWindow.WINDOW_POINTS);
            assertEquals(full.get(target), window.points().get(window.targetIndex()));
            assertEquals(target, window.offset() + window.targetIndex());
            if (target > 0) assertEquals(full.get(target - 1), window.points().get(window.targetIndex() - 1));
        }
        assertEquals(AutopilotRouteWindow.around(full, 900).offset(), AutopilotRouteWindow.around(full, 901).offset(),
                "small cursor advances must not invalidate an in-flight local repair");
    }

    @Test
    void aLocalDetourPreservesTheEntireUnvisitedSuffixAndVisitedPrefix() {
        List<Vec3> full = route(3000);
        AutopilotRouteWindow.Window window = AutopilotRouteWindow.around(full, 900);
        List<Vec3> local = new ArrayList<>(window.points());
        local.add(10, new Vec3(999, 63, 999));
        RouteSplicer.Spliced spliced = new RouteSplicer.Spliced(local, window.targetIndex(), null);
        AutopilotRouteWindow.State result = AutopilotRouteWindow.apply(full, window, spliced);
        assertEquals(full.size() + 1, result.route().size());
        assertEquals(full.subList(0, window.offset()), result.route().subList(0, window.offset()));
        assertEquals(full.subList(window.offset() + window.points().size(), full.size()),
                result.route().subList(window.offset() + local.size(), result.route().size()));
        assertEquals(full.getLast(), result.route().getLast());
        assertEquals(window.offset() + spliced.targetIndex(), result.targetIndex());
    }

    @Test
    void changedWindowRejectsAStaleDetourInsteadOfReplacingAnotherRoute() {
        List<Vec3> full = route(3000);
        AutopilotRouteWindow.Window window = AutopilotRouteWindow.around(full, 900);
        full.set(window.offset() + 1, new Vec3(-100, 63, -100));
        RouteSplicer.Spliced stale = new RouteSplicer.Spliced(window.points(), window.targetIndex(), null);
        assertThrows(IllegalStateException.class, () -> AutopilotRouteWindow.apply(full, window, stale));
    }

    @Test
    void saveLoadPreservesTheFullRouteAndGlobalCursorInTheLegacyNbtFormat() {
        CompoundTag tag = new CompoundTag();
        List<Vec3> full = route(3000);
        AutopilotRouteWindow.save(tag, full, 2800);
        assertEquals(3000, tag.getList("AutopilotRoute", 10).size());
        assertEquals(2800, tag.getInt("AutopilotTargetIndex"));
        AutopilotRouteWindow.State restored = AutopilotRouteWindow.load(tag);
        assertEquals(full, restored.route());
        assertEquals(2800, restored.targetIndex());
        CompoundTag legacy = new CompoundTag();
        ListTag oldPoints = new ListTag();
        for (Vec3 point : full) {
            CompoundTag item = new CompoundTag(); item.putDouble("X", point.x); item.putDouble("Y", point.y); item.putDouble("Z", point.z); oldPoints.add(item);
        }
        legacy.put("AutopilotRoute", oldPoints); legacy.putInt("AutopilotTargetIndex", 2900);
        assertEquals(new AutopilotRouteWindow.State(full, 2900), AutopilotRouteWindow.load(legacy));
    }

    @Test
    void catalogRepairBeyond512ChangesOnlyTheExactBoundedSlice() {
        List<Vec3> full = route(3000);
        List<Vec3> before = full.subList(1000, 1002);
        RoutePatch patch = new RoutePatch(before, List.of(before.getFirst(), new Vec3(123, 63, 456), before.getLast()));
        RouteDefinition route = new RouteDefinition("Auto-A-B", full, "test", "uuid", 0, 100, "A", "B");
        RouteDefinition updated = patch.applyTo(route).orElseThrow();
        assertEquals(3001, updated.waypoints().size());
        assertEquals(full.getLast(), updated.waypoints().getLast());
        assertEquals(full.subList(1002, full.size()), updated.waypoints().subList(1003, updated.waypoints().size()));
    }

    private static List<Vec3> route(int points) {
        List<Vec3> path = new ArrayList<>();
        for (int i = 0; i < points; i++) path.add(new Vec3(i * 8 + 0.5, 63, (i % 3) * 8 + 0.5));
        return path;
    }
}
