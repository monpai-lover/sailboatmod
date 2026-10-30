package com.monpai.sailboatmod.map.route;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MapRouteModelsTest {
    @Test
    void coordinatesAndRouteLengthsMustBeFinite() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new MapRoutePoint(invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> new MapRoutePoint(0, invalid));
            assertThrows(IllegalArgumentException.class, () -> entry(invalid, List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> entry(-1, List.of()));
        assertEquals(0, entry(0, List.of()).lengthBlocks());
    }

    @Test
    void viewportRequiresFiniteOrderedBoundsAndPositiveScale() {
        assertThrows(IllegalArgumentException.class, () -> new MapRouteViewport(0, 0, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapRouteViewport(0, 1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapRouteViewport(Double.NaN, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new MapRouteViewport(0, 0, Double.POSITIVE_INFINITY, 1, 1));
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new MapRouteViewport(0, 0, 1, 1, invalid));
        }
        MapRouteViewport large = new MapRouteViewport(-1.0E12, -2.0E12, 1.0E12, 2.0E12, 0.001);
        assertEquals(-1.0E12, large.minX());
    }

    @Test
    void entriesAndSnapshotsOwnImmutableCopiesOfTheirInputs() {
        ArrayList<MapRoutePoint> points = new ArrayList<>(List.of(new MapRoutePoint(0, 0), new MapRoutePoint(1, 1)));
        ArrayList<List<MapRoutePoint>> paths = new ArrayList<>(List.of(points));
        MapRouteEntry entry = entry(2, paths);
        ArrayList<MapRouteEntry> routes = new ArrayList<>(List.of(entry));
        MapRouteSnapshot snapshot = new MapRouteSnapshot(routes, false);

        points.clear();
        paths.clear();
        routes.clear();

        assertEquals(2, snapshot.routes().getFirst().paths().getFirst().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.routes().clear());
        assertThrows(UnsupportedOperationException.class, () -> entry.paths().clear());
        assertThrows(UnsupportedOperationException.class, () -> entry.paths().getFirst().clear());
    }

    @Test
    void routeMetadataAndPathElementsCannotBeNullOrUnbounded() {
        assertThrows(NullPointerException.class, () -> new MapRouteEntry(null, MapRouteKind.ROAD, "", "", "", 0, List.of()));
        assertThrows(NullPointerException.class, () -> new MapRouteEntry("id", null, "", "", "", 0, List.of()));
        assertThrows(NullPointerException.class, () -> entry(0, null));
        assertThrows(NullPointerException.class, () -> entry(0, Collections.singletonList(null)));
        assertThrows(NullPointerException.class, () -> entry(0, List.of(Collections.singletonList(null))));
        assertThrows(IllegalArgumentException.class, () -> new MapRouteEntry(
                "x".repeat(MapRouteSnapshot.MAX_STRING_LENGTH + 1), MapRouteKind.ROAD, "", "", "", 0, List.of()));
    }

    @Test
    void snapshotsRejectRoutePointAndFragmentCountsBeyondWireBudgets() {
        MapRouteEntry empty = entry(0, List.of());
        assertThrows(IllegalArgumentException.class, () -> new MapRouteSnapshot(
                Collections.nCopies(MapRouteSnapshot.MAX_ROUTES + 1, empty), false));
        List<MapRoutePoint> pair = List.of(new MapRoutePoint(0, 0), new MapRoutePoint(1, 1));
        MapRouteEntry fragmented = entry(1, Collections.nCopies(MapRouteSnapshot.MAX_PARTS, pair));
        assertThrows(IllegalArgumentException.class, () -> new MapRouteSnapshot(List.of(fragmented, fragmented), false));
        MapRouteEntry manyPoints = entry(1, List.of(Collections.nCopies(MapRouteSnapshot.MAX_POINTS, new MapRoutePoint(0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new MapRouteSnapshot(List.of(manyPoints, manyPoints), false));
        assertEquals(MapRouteSnapshot.MAX_POINTS, new MapRouteSnapshot(List.of(manyPoints), true)
                .routes().getFirst().paths().getFirst().size());
    }

    private static MapRouteEntry entry(double length, List<List<MapRoutePoint>> paths) {
        return new MapRouteEntry("road:1", MapRouteKind.ROAD, "Main road", "A", "B", length, paths);
    }
}
