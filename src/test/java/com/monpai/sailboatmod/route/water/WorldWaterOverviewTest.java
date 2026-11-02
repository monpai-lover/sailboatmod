package com.monpai.sailboatmod.route.water;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldWaterOverviewTest {
    private static final double SCALE = 100;

    private static int bx(double lon) { return (int) Math.round(lon * 111319.49166666667D / SCALE); }
    private static int bz(double lat) {
        return (int) Math.round(-6378137.050041738D * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2)) / SCALE);
    }

    @Test
    void bundledOverviewKnowsOceansContinentsAndStraits() {
        WorldWaterOverview overview = WorldWaterOverview.get();
        assertNotNull(overview, "the bundled overview resource must load");
        assertTrue(overview.water(75, -10), "Indian Ocean");
        assertTrue(overview.water(-30, 30), "Atlantic");
        assertFalse(overview.water(15, 20), "Sahara");
        assertFalse(overview.water(100, 45), "Mongolia");
        assertTrue(overview.water(1.4, 50.9), "Strait of Dover");
        assertTrue(overview.water(105.8, -6.0), "Sunda Strait");
        assertTrue(overview.water(-5.6, 35.95), "Strait of Gibraltar");
        assertFalse(overview.water(32.3, 30.0), "the Suez isthmus is closed at overview resolution");
    }

    @Test
    void seaDistanceDoesNotLeakThroughIsthmuses() {
        WorldWaterOverview overview = WorldWaterOverview.get();
        // Port Said (Mediterranean) to Suez (Red Sea): ~150 km apart, but by sea it is around Africa.
        GlobalWaterGridPlanner.SeaDistance field = overview.seaDistance(SCALE, bx(32.35), bz(31.35), bx(32.6), bz(29.8));
        double d = field.distance(bx(32.6), bz(29.8));
        assertTrue(d > 150_000, "Mediterranean -> Red Sea must not cross Suez in the overview, got " + d);
        // Taipei -> IJmuiden: about 280k blocks at 1:100 by sea.
        GlobalWaterGridPlanner.SeaDistance far = overview.seaDistance(SCALE, bx(4.55), bz(52.465), bx(121.37), bz(25.16));
        double route = far.distance(bx(121.37), bz(25.16));
        assertTrue(route > 230_000 && route < 330_000, "Taipei -> Amsterdam sea distance " + route);
    }

    @Test
    void tellusBlockRectanglesMapToTheSamePlaces() {
        GlobalWaterGridPlanner.OverviewWater water = WorldWaterOverview.get().forTellusScale(SCALE);
        assertTrue(water.anyWater(bx(75), bz(-10), bx(75) + 1023, bz(-10) + 1023), "Indian Ocean");
        assertFalse(water.anyWater(bx(15), bz(20), bx(15) + 1023, bz(20) + 1023), "Sahara");
        assertFalse(water.anyWater(bx(100), bz(45), bx(100) + 1023, bz(45) + 1023), "Mongolia");
    }
}
