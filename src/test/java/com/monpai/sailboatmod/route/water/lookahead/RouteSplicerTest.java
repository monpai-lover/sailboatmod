package com.monpai.sailboatmod.route.water.lookahead;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteSplicerTest {
    /** obstacle inside segment 2 (between (20,0) and (30,0)) */
    private static final RouteLookahead.Obstacle INSIDE = new RouteLookahead.Obstacle(2, b(23, 0), 2, b(27, 0));

    @Test
    void insertsDetourBetweenTheSegmentEnds() {
        List<Vec3> route = route(6);

        RouteSplicer.Spliced spliced = RouteSplicer.splice(route, INSIDE, detour(), 1, 512).orElseThrow();

        assertEquals(9, spliced.route().size());
        assertEquals(route.subList(0, 3), spliced.route().subList(0, 3));
        assertEquals(new Vec3(23.5D, 63, 0.5D), spliced.route().get(3));
        assertEquals(new Vec3(25.5D, 63, 5.5D), spliced.route().get(4));
        assertEquals(new Vec3(27.5D, 63, 0.5D), spliced.route().get(5));
        assertEquals(route.subList(3, 6), spliced.route().subList(6, 9));
        assertEquals(1, spliced.targetIndex());
    }

    @Test
    void boatOnTheBlockedSegmentTargetsTheFirstDetourPoint() {
        RouteSplicer.Spliced spliced = RouteSplicer.splice(route(6), INSIDE, detour(), 3, 512).orElseThrow();

        assertEquals(3, spliced.targetIndex());
    }

    @Test
    void boatPastTheSegmentIsStale() {
        assertTrue(RouteSplicer.isStale(INSIDE, 4));
        assertFalse(RouteSplicer.isStale(INSIDE, 3));
        assertTrue(RouteSplicer.splice(route(6), INSIDE, detour(), 4, 512).isEmpty());
    }

    @Test
    void endpointsOnWaypointsAreNotDuplicated() {
        RouteLookahead.Obstacle onWaypoints = new RouteLookahead.Obstacle(2, b(20, 0), 2, b(30, 0));
        List<BlockPos> detour = List.of(b(20, 0), b(25, 5), b(30, 0));

        RouteSplicer.Spliced spliced = RouteSplicer.splice(route(6), onWaypoints, detour, 1, 512).orElseThrow();

        assertEquals(7, spliced.route().size());
        assertEquals(new Vec3(25.5D, 63, 5.5D), spliced.route().get(3));
    }

    @Test
    void obstacleSpanningAWaypointRemovesIt() {
        RouteLookahead.Obstacle spanning = new RouteLookahead.Obstacle(1, b(13, 0), 2, b(27, 0));
        List<BlockPos> detour = List.of(b(13, 0), b(20, 6), b(27, 0));
        List<Vec3> route = route(6);

        RouteSplicer.Spliced spliced = RouteSplicer.splice(route, spanning, detour, 1, 512).orElseThrow();

        assertFalse(spliced.route().contains(route.get(2)));
        assertEquals(List.of(route.get(1), route.get(2), route.get(3)), spliced.patch().before());
        assertEquals(route.get(1), spliced.patch().after().get(0));
        assertEquals(route.get(3), spliced.patch().after().get(spliced.patch().after().size() - 1));
    }

    @Test
    void realisticAutoRouteOf256PointsAcceptsADetour() {
        List<Vec3> route = route(256);
        RouteLookahead.Obstacle obstacle = new RouteLookahead.Obstacle(100, b(1003, 0), 100, b(1007, 0));
        List<BlockPos> detour = List.of(b(1003, 0), b(1004, 4), b(1005, 6), b(1006, 4), b(1007, 0));

        assertTrue(RouteSplicer.splice(route, obstacle, detour, 50, RouteLookaheadGuard.MAX_WAYPOINTS).isPresent(),
                "auto routes are thinned to exactly 256 points, so the cap must leave room for detours");
    }

    @Test
    void rejectsSpliceOverWaypointCap() {
        RouteLookahead.Obstacle obstacle = new RouteLookahead.Obstacle(2, b(23, 0), 2, b(27, 0));

        assertTrue(RouteSplicer.splice(route(511), obstacle, detour(), 1, 512).isEmpty());
        assertFalse(RouteSplicer.isStale(obstacle, 1));
    }

    @Test
    void patchCarriesExactOriginalSlice() {
        List<Vec3> route = route(6);

        RouteSplicer.Spliced spliced = RouteSplicer.splice(route, INSIDE, detour(), 1, 512).orElseThrow();

        assertEquals(List.of(route.get(2), route.get(3)), spliced.patch().before());
        assertEquals(spliced.route().subList(2, 7), spliced.patch().after());
        assertEquals(List.of(route.get(3), route.get(2)), spliced.patch().reversed().before());
    }

    /** waypoints (i*10, 0) for i in 0..n-1. */
    private static List<Vec3> route(int n) {
        List<Vec3> route = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            route.add(new Vec3(i * 10 + 0.5D, 63, 0.5D));
        }
        return route;
    }

    private static List<BlockPos> detour() {
        return List.of(b(23, 0), b(25, 5), b(27, 0));
    }

    private static BlockPos b(int x, int z) {
        return new BlockPos(x, 63, z);
    }
}
