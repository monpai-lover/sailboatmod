package com.monpai.sailboatmod.route.repair;

import com.monpai.sailboatmod.route.RouteDefinition;
import com.monpai.sailboatmod.route.WaypointMeta;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePatchTest {
    private static final Vec3 X = new Vec3(25.5D, 63, 30.5D);
    private static final Vec3 Y = new Vec3(35.5D, 63, 30.5D);
    private static final RoutePatch PATCH = new RoutePatch(List.of(p(2), p(3)), List.of(p(2), X, Y, p(3)));

    @Test
    void appliesForward() {
        RouteDefinition patched = PATCH.applyTo(route("Auto-A-B", points(), List.of())).orElseThrow();

        assertEquals(List.of(p(0), p(1), p(2), X, Y, p(3), p(4), p(5)), patched.waypoints());
        assertEquals("Auto-A-B", patched.name());
    }

    @Test
    void appliesReversedToReturnRoute() {
        List<Vec3> back = points();
        Collections.reverse(back);

        RouteDefinition patched = PATCH.applyTo(route("Auto-B-A", back, List.of())).orElseThrow();

        assertEquals(List.of(p(5), p(4), p(3), Y, X, p(2), p(1), p(0)), patched.waypoints());
    }

    @Test
    void rejectsManualRoute() {
        assertTrue(PATCH.applyTo(route("Harbour loop", points(), List.of())).isEmpty());
    }

    @Test
    void acceptsLegacyAutoPrefix() {
        assertTrue(PATCH.applyTo(route("Water Auto: A-B", points(), List.of())).isPresent());
    }

    @Test
    void rejectsWhenSliceChanged() {
        List<Vec3> moved = points();
        moved.set(3, new Vec3(30.5D, 63, 2.5D));

        assertTrue(PATCH.applyTo(route("Auto-A-B", moved, List.of())).isEmpty());
    }

    @Test
    void rejectsAmbiguousSlice() {
        List<Vec3> twice = points();
        twice.addAll(List.of(p(2), p(3)));

        assertTrue(PATCH.applyTo(route("Auto-A-B", twice, List.of())).isEmpty());
    }

    @Test
    void applyingTwiceIsANoOp() {
        RouteDefinition once = PATCH.applyTo(route("Auto-A-B", points(), List.of())).orElseThrow();

        assertTrue(PATCH.applyTo(once).isEmpty());
    }

    @Test
    void recomputesLength() {
        RouteDefinition patched = PATCH.applyTo(route("Auto-A-B", points(), List.of())).orElseThrow();

        double expected = 0;
        for (int i = 1; i < patched.waypoints().size(); i++) {
            expected += patched.waypoints().get(i - 1).distanceTo(patched.waypoints().get(i));
        }
        assertEquals(expected, patched.routeLengthMeters(), 1e-9);
    }

    @Test
    void replacedMetasAreRaw() {
        List<WaypointMeta> metas = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            metas.add(new WaypointMeta(WaypointMeta.SEGMENT_START, WaypointMeta.ORIGIN_INTERP));
        }

        RouteDefinition patched = PATCH.applyTo(route("Auto-A-B", points(), metas)).orElseThrow();

        assertEquals(8, patched.waypointMetas().size());
        WaypointMeta raw = new WaypointMeta(WaypointMeta.SEGMENT_MID, WaypointMeta.ORIGIN_RAW);
        assertEquals(List.of(raw, raw, raw, raw), patched.waypointMetas().subList(2, 6));
        assertEquals(metas.get(0), patched.waypointMetas().get(0));
        assertEquals(metas.get(5), patched.waypointMetas().get(7));
    }

    @Test
    void metasStayEmptyWhenMisaligned() {
        RouteDefinition patched = PATCH.applyTo(route("Auto-A-B", points(), List.of())).orElseThrow();

        assertTrue(patched.waypointMetas().isEmpty());
    }

    @Test
    void applyToRoutesChangesOnlyMatchingRoutes() {
        List<RouteDefinition> routes = List.of(route("Harbour loop", points(), List.of()), route("Auto-A-B", points(), List.of()));

        List<RouteDefinition> updated = RouteRepairService.applyToRoutes(routes, PATCH);

        assertSame(routes.get(0), updated.get(0));
        assertEquals(8, updated.get(1).waypoints().size());
        assertSame(routes, RouteRepairService.applyToRoutes(routes, new RoutePatch(List.of(X, Y), List.of(X, Y))),
                "nothing matched, so the same list comes back");
    }

    @Test
    void fullCatalogSizeIsIndependentFromTheBoundedLocalPatchBudget() {
        List<Vec3> many = new ArrayList<>();
        for (int i = 0; i < RoutePatch.MAX_WAYPOINTS - 1; i++) {
            many.add(p(i));
        }

        assertTrue(PATCH.applyTo(route("Auto-A-B", many, List.of())).isPresent(),
                "the complete route is retained; only the local replacement needs a fixed budget");
    }

    @Test
    void rejectsAnUnboundedSingleReplacement() {
        List<Vec3> oversized = new ArrayList<>();
        oversized.add(p(2));
        for (int i = 0; i < RoutePatch.MAX_WAYPOINTS; i++) oversized.add(X);
        oversized.add(p(3));
        assertTrue(new RoutePatch(List.of(p(2), p(3)), oversized).applyTo(route("Auto-A-B", points(), List.of())).isEmpty());
    }

    static Vec3 p(int i) {
        return new Vec3(i * 10 + 0.5D, 63, 0.5D);
    }

    static List<Vec3> points() {
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            points.add(p(i));
        }
        return points;
    }

    static RouteDefinition route(String name, List<Vec3> waypoints, List<WaypointMeta> metas) {
        return new RouteDefinition(name, waypoints, "boyang", "uuid", 1L, 50.0D, "A", "B", metas);
    }
}
