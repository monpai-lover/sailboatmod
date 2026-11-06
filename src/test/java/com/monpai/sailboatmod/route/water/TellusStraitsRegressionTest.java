package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.water.tellus.PmTilesLandMask;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in real-geography acceptance cases: full production route creation and independent hull checks.
 * Set SAILBOAT_TELLUS_LANDMASK to the official Land Polygons v1.0.0 file and select this class.
 * These mask-backed tests do not cover actual saved chunks, structures, depth or native boat physics.
 */
class TellusStraitsRegressionTest {
    private static final long LIMIT_MILLIS = 120_000L;
    private static final Map<Double, TellusMaskRouteFixture> FIXTURES = new HashMap<>();
    private static Path maskPath;

    @AfterAll
    static void closeFixtures() throws IOException {
        IOException failure = null;
        try {
            for (TellusMaskRouteFixture fixture : FIXTURES.values()) {
                try {
                    fixture.close();
                } catch (IOException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
        } finally {
            FIXTURES.clear();
            maskPath = null;
        }
        if (failure != null) throw failure;
    }

    private record RouteCase(String name, double scale, double fromLon, double fromLat, double toLon, double toLat,
                             double gateMinLon, double gateMaxLon, double gateMinLat, double gateMaxLat,
                             boolean reverse) {
        RouteCase reversed() {
            return new RouteCase(name, scale, fromLon, fromLat, toLon, toLat,
                    gateMinLon, gateMaxLon, gateMinLat, gateMaxLat, true);
        }

        @Override public String toString() { return name + "-1to" + (int) scale + (reverse ? "-reverse" : "-forward"); }
    }

    @BeforeAll
    static void prepare() {
        String file = System.getenv("SAILBOAT_TELLUS_LANDMASK");
        assumeTrue(file != null && Files.isRegularFile(Path.of(file)), "Tellus land mask not provided");
        maskPath = Path.of(file);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static Stream<RouteCase> trickyRoutes() {
        List<RouteCase> cases = List.of(
                // Open Aegean -> Marmara: must negotiate the curved Dardanelles, not just open water.
                new RouteCase("dardanelles", 100, 25.95, 39.85, 27.25, 40.5, 26.15, 26.85, 40.0, 40.5, false),
                // Atlantic -> Mediterranean, including both shores of the entrance.
                new RouteCase("gibraltar", 100, -5.8, 35.95, -5.1, 36.05, -5.65, -5.3, 35.8, 36.3, false),
                // English Channel -> North Sea through the Dover bottleneck.
                new RouteCase("dover", 100, 1.0, 50.4, 2.0, 51.4, 1.1, 1.95, 50.8, 51.2, false),
                // Malacca west side -> Singapore east side through the island-strewn southern passage.
                new RouteCase("malacca-singapore", 100, 100.2, 4.3, 104.2, 1.1, 103.2, 104.2, 0.8, 1.6, false),
                // A hull-clear Stockholm harbour point -> Baltic, rather than a dry city-centre endpoint.
                new RouteCase("stockholm-archipelago", 100, 18.094, 59.3183, 19.1, 59.15, 18.2, 18.9, 59.05, 59.45, false),
                // Short coastal berth -> Aegean islands: exercises port connectors and near-shore finishing.
                new RouteCase("piraeus-coastal", 100, 23.62, 37.925, 24.3, 37.8, 23.75, 24.15, 37.55, 38.05, false),
                // The same Bosphorus at three map scales. Independent unit-grid 2x2 checks confirm
                // that the official mask has a valid hull-width passage at all three scales.
                new RouteCase("bosphorus-local", 30, 29.005, 41.015, 29.15, 41.25, 29.0, 29.15, 41.06, 41.2, false),
                new RouteCase("bosphorus-local", 100, 29.005, 41.015, 29.15, 41.25, 29.0, 29.15, 41.06, 41.2, false),
                new RouteCase("bosphorus-local", 300, 29.005, 41.015, 29.15, 41.25, 29.0, 29.15, 41.06, 41.2, false));
        return cases.stream().flatMap(c -> Stream.of(c, c.reversed()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("trickyRoutes")
    @Timeout(180)
    void connectedRealStraitsProduceCreatableHullClearRoutes(RouteCase route) throws Exception {
        TellusMaskRouteFixture fixture = FIXTURES.get(route.scale);
        if (fixture == null) {
            fixture = new TellusMaskRouteFixture(maskPath, route.scale);
            FIXTURES.put(route.scale, fixture);
        }
        BlockPos a = fixture.endpoint(route.fromLon, route.fromLat), b = fixture.endpoint(route.toLon, route.toLat);
        BlockPos source = route.reverse ? b : a, target = route.reverse ? a : b;
        System.out.printf("[straits] start=%s source=%s target=%s nativeZoom=%d%n", route, source, target, fixture.mask.zoom());
        long began = System.nanoTime();
        WaterRouteResult<List<BlockPos>> result = fixture.plan(source, target, LIMIT_MILLIS);
        long millis = (System.nanoTime() - began) / 1_000_000L;
        System.out.printf("[straits] result=%s successful=%s reason=%s elapsed=%dms%n", route,
                result.successful(), result.reason(), millis);
        assertTrue(result.successful(), route + " reason=" + result.reason());
        List<BlockPos> path = result.value();
        assertTrue(path.size() >= 2, route + " must create a usable route");
        assertEquals(source, path.getFirst(), route + " must retain the formal source berth");
        assertEquals(target, path.getLast(), route + " must retain the formal destination berth");
        assertTrue(crossesGate(path, route), route + " must traverse the named local passage");
        long checked = fixture.assertHullWater(path);
        double length = 0;
        for (int i = 1; i < path.size(); i++) length += Math.sqrt(path.get(i - 1).distSqr(path.get(i)));
        System.out.printf("[straits] verified=%s waypoints=%d length=%.2f blocks hullSamples=%d%n", route, path.size(), length, checked);
        String output = System.getenv("SAILBOAT_TELLUS_ROUTE_OUT");
        if (output != null) fixture.writeCsv(Path.of(output + "-" + route + ".csv"), path);
    }

    private static boolean crossesGate(List<BlockPos> path, RouteCase route) {
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            int steps = Math.max(1, (int) Math.ceil(Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ())));
            for (int step = 0; step <= steps; step++) {
                double fraction = step / (double) steps;
                double longitude = PmTilesLandMask.lon(a.getX() + (b.getX() - a.getX()) * fraction, route.scale);
                double latitude = PmTilesLandMask.lat(a.getZ() + (b.getZ() - a.getZ()) * fraction, route.scale);
                if (longitude >= route.gateMinLon && longitude <= route.gateMaxLon
                        && latitude >= route.gateMinLat && latitude <= route.gateMaxLat) return true;
            }
        }
        return false;
    }
}
