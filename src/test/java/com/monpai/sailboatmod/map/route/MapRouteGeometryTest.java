package com.monpai.sailboatmod.map.route;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class MapRouteGeometryTest {
    private static final MapRouteViewport VIEW = new MapRouteViewport(0, 0, 10, 10, 1);

    @Test
    void clipsCrossingSegmentWhoseEndpointsAreBothOutside() {
        assertEquals(List.of(List.of(point(0, 5), point(10, 5))),
                MapRouteGeometry.clip(List.of(point(-10, 5), point(20, 5)), VIEW));
    }

    @Test
    void clipsDiagonalAndVerticalSegmentsAtExactBoundaries() {
        assertEquals(List.of(List.of(point(0, 0), point(10, 10))),
                MapRouteGeometry.clip(List.of(point(-2, -2), point(12, 12)), VIEW));
        assertEquals(List.of(List.of(point(5, 10), point(5, 0))),
                MapRouteGeometry.clip(List.of(point(5, 20), point(5, -10)), VIEW));
    }

    @Test
    void reenteringPathProducesSeparateVisibleFragments() {
        List<MapRoutePoint> path = List.of(point(-5, 2), point(5, 2), point(15, 2),
                point(15, 8), point(5, 8), point(-5, 8));
        assertEquals(List.of(
                        List.of(point(0, 2), point(5, 2), point(10, 2)),
                        List.of(point(10, 8), point(5, 8), point(0, 8))),
                MapRouteGeometry.clip(path, VIEW));
    }

    @Test
    void outsideDetourDoesNotMergeFragmentsEvenAtTheSameBoundaryPoint() {
        assertEquals(2, MapRouteGeometry.clip(List.of(point(5, 5), point(15, 5), point(5, 5)), VIEW).size());
    }

    @Test
    void clippingSupportsLargePositiveAndNegativeCoordinatesWithoutPackingLoss() {
        for (double origin : new double[]{1.0E12, -1.0E12}) {
            MapRouteViewport view = new MapRouteViewport(origin, origin, origin + 10, origin + 10, 1);
            assertEquals(List.of(List.of(point(origin, origin + 5), point(origin + 10, origin + 5))),
                    MapRouteGeometry.clip(List.of(point(origin - 10, origin + 5), point(origin + 20, origin + 5)), view));
        }
    }

    @Test
    void clippingFiniteExtremeCoordinatesCannotOverflowIntoInvalidPoints() {
        assertEquals(List.of(List.of(point(0, 5), point(10, 5))),
                MapRouteGeometry.clip(List.of(point(-Double.MAX_VALUE, 5), point(Double.MAX_VALUE, 5)), VIEW));
    }

    @Test
    void emptySingletonOutsideAndCornerTouchPathsDoNotCreatePhantomSegments() {
        assertTrue(MapRouteGeometry.clip(List.of(), VIEW).isEmpty());
        assertTrue(MapRouteGeometry.clip(List.of(point(5, 5)), VIEW).isEmpty());
        assertTrue(MapRouteGeometry.clip(List.of(point(-5, -1), point(15, -1)), VIEW).isEmpty());
        assertTrue(MapRouteGeometry.clip(List.of(point(-1, 1), point(1, -1)), VIEW).isEmpty());
        assertTrue(MapRouteGeometry.clip(List.of(point(5, 5), point(5, 5)), VIEW).isEmpty());
    }

    @Test
    void clippingCopiesInputAndReturnsImmutableFragments() {
        ArrayList<MapRoutePoint> input = new ArrayList<>(List.of(point(1, 1), point(9, 9)));
        List<List<MapRoutePoint>> clipped = MapRouteGeometry.clip(input, VIEW);
        input.clear();
        assertEquals(2, clipped.getFirst().size());
        assertThrows(UnsupportedOperationException.class, () -> clipped.clear());
        assertThrows(UnsupportedOperationException.class, () -> clipped.getFirst().clear());
    }

    @Test
    void simplificationUsesZoomToleranceAndPreservesEndpointsAndSharpCorners() {
        List<MapRoutePoint> path = List.of(point(0, 0), point(5, 0.4), point(10, 0));
        assertEquals(List.of(point(0, 0), point(10, 0)), MapRouteGeometry.simplify(path, 1.0 / 1.0));
        assertEquals(path, MapRouteGeometry.simplify(path, 1.0 / 4.0));
        List<MapRoutePoint> bend = List.of(point(0, 0), point(5, 5), point(10, 0));
        assertEquals(bend, MapRouteGeometry.simplify(bend, 0.5));
    }

    @Test
    void simplificationBoundsDeviationFromEveryOriginalVertexInScreenPixels() {
        ArrayList<MapRoutePoint> path = new ArrayList<>();
        for (int x = 0; x <= 100; x++) {
            path.add(point(x, Math.sin(x / 5.0) * 4));
        }
        for (double pixelsPerBlock : new double[]{0.1, 1, 10}) {
            List<MapRoutePoint> simplified = MapRouteGeometry.simplify(path, 0.75 / pixelsPerBlock);
            for (MapRoutePoint original : path) {
                assertTrue(distanceToPath(original, simplified) * pixelsPerBlock <= 0.7500001,
                        "Simplified route exceeds the requested screen error");
            }
            assertEquals(path.getFirst(), simplified.getFirst());
            assertEquals(path.getLast(), simplified.getLast());
        }
    }

    @Test
    void simplificationRetainsBacktrackingAndHandlesCoincidentEndpoints() {
        List<MapRoutePoint> outAndBack = List.of(point(0, 0), point(10, 0), point(0, 0));
        assertEquals(outAndBack, MapRouteGeometry.simplify(outAndBack, 1));
        assertEquals(List.of(point(0, 0), point(0, 0)),
                MapRouteGeometry.simplify(List.of(point(0, 0), point(0, 0), point(0, 0)), 1));
    }

    @Test
    void simplificationDoesNotOverflowAtExtremeFiniteCoordinates() {
        List<MapRoutePoint> path = List.of(point(-Double.MAX_VALUE, 0), point(0, Double.MAX_VALUE), point(Double.MAX_VALUE, 0));
        assertEquals(path, MapRouteGeometry.simplify(path, 1));
    }

    @Test
    void pathologicalSimplificationFallsBackToOriginalGeometryWithinItsWorkBudget() {
        ArrayList<MapRoutePoint> path = new ArrayList<>();
        path.add(point(0, 0));
        path.add(point(0, 0)); // Unlimited RDP removes this duplicate after processing the zigzag.
        for (int x = 1; x <= 8190; x++) {
            path.add(point(x, (x & 1) * 2));
        }
        assertEquals(path, MapRouteGeometry.simplify(path, 0.1));
    }

    @Test
    void interruptedSimplificationPreservesOriginalGeometryAndInterruptFlag() {
        List<MapRoutePoint> path = List.of(point(0, 0), point(5, 5), point(10, 0));
        Thread.currentThread().interrupt();
        try {
            assertEquals(path, MapRouteGeometry.simplify(path, 100));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptedClippingCancelsAndPreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class,
                    () -> MapRouteGeometry.clip(List.of(point(-10, 5), point(20, 5)), VIEW));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void simplificationValidatesToleranceAndReturnsAnImmutableCopy() {
        List<MapRoutePoint> path = new ArrayList<>(List.of(point(0, 0), point(1, 1)));
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> MapRouteGeometry.simplify(path, invalid));
        }
        List<MapRoutePoint> simplified = MapRouteGeometry.simplify(path, 0);
        path.clear();
        assertEquals(2, simplified.size());
        assertThrows(UnsupportedOperationException.class, simplified::clear);
        assertTrue(MapRouteGeometry.simplify(List.of(), 0).isEmpty());
    }

    private static MapRoutePoint point(double x, double z) {
        return new MapRoutePoint(x, z);
    }

    private static double distanceToPath(MapRoutePoint point, List<MapRoutePoint> path) {
        double nearest = Double.POSITIVE_INFINITY;
        for (int index = 1; index < path.size(); index++) {
            MapRoutePoint a = path.get(index - 1);
            MapRoutePoint b = path.get(index);
            double dx = b.x() - a.x();
            double dz = b.z() - a.z();
            double projection = ((point.x() - a.x()) * dx + (point.z() - a.z()) * dz) / (dx * dx + dz * dz);
            projection = Math.max(0, Math.min(1, projection));
            nearest = Math.min(nearest, Math.hypot(point.x() - (a.x() + projection * dx),
                    point.z() - (a.z() + projection * dz)));
        }
        return nearest;
    }
}
