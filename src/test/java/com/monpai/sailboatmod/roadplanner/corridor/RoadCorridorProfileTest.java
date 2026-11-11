package com.monpai.sailboatmod.roadplanner.corridor;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadCorridorProfileTest {
    private static double[] unit(int n) {
        double[] spacing = new double[n - 1];
        Arrays.fill(spacing, 1.0);
        return spacing;
    }

    private static double[] none(int n) {
        double[] values = new double[n];
        Arrays.fill(values, Double.NEGATIVE_INFINITY);
        return values;
    }

    private static double[] design(double[] ground) {
        return RoadCorridorProfile.design(ground, unit(ground.length), none(ground.length), none(ground.length));
    }

    @Test
    void levelGroundGivesALevelRoadAtThatHeight() {
        double[] ground = new double[200];
        Arrays.fill(ground, 70);
        for (double height : design(ground)) {
            assertEquals(70, height, 1.0E-9);
        }
    }

    @Test
    void aGentleSlopeIsFollowedAsItIs() {
        double[] ground = new double[300];
        for (int i = 0; i < ground.length; i++) {
            ground[i] = 70 + i * 0.1;
        }
        double[] road = design(ground);
        for (int i = 60; i < 240; i++) {
            assertEquals(ground[i], road[i], 1.0E-6, "a slope within the grade limit needs no earthworks, i=" + i);
        }
    }

    @Test
    void roughGroundWithinTheGradeWindowIsNeverClimbedFasterThanTheLimit() {
        Random random = new Random(11);
        double[] ground = new double[600];
        double level = 80;
        for (int i = 0; i < ground.length; i++) {
            if (i % 40 == 0) {
                level = 80 + random.nextInt(15);
            }
            ground[i] = level + random.nextInt(3);
        }
        double[] road = design(ground);
        for (int i = 1; i < road.length; i++) {
            assertTrue(Math.abs(road[i] - road[i - 1]) <= RoadCorridorProfile.MAX_GRADE + 1.0E-9,
                    "grade " + Math.abs(road[i] - road[i - 1]) + " at " + i);
        }
    }

    @Test
    void theProfileIsSmoothWithNoAbruptChangeOfGrade() {
        double[] ground = new double[400];
        for (int i = 0; i < ground.length; i++) {
            ground[i] = i < 200 ? 70 : 82;
        }
        double[] road = design(ground);
        for (int i = 2; i < road.length; i++) {
            double bend = Math.abs(road[i] - 2 * road[i - 1] + road[i - 2]);
            assertTrue(bend <= 0.06, "grade changes too sharply at " + i + ": " + bend);
        }
    }

    @Test
    void aNarrowDipIsSpannedAndANarrowHumpIsCut() {
        double[] ground = new double[300];
        Arrays.fill(ground, 80);
        for (int i = 100; i < 120; i++) {
            ground[i] = 60;
        }
        for (int i = 200; i < 205; i++) {
            ground[i] = 90;
        }
        for (double height : design(ground)) {
            assertEquals(80, height, 1.0E-9);
        }
    }

    @Test
    void aClearedHeightIsReachedExactlyAndApproachedWithinTheGradeLimit() {
        int n = 400;
        double[] ground = new double[n];
        Arrays.fill(ground, 64);
        double[] cleared = none(n);
        for (int i = 180; i <= 220; i++) {
            cleared[i] = 71;
        }
        double[] road = RoadCorridorProfile.design(ground, unit(n), none(n), cleared);
        for (int i = 180; i <= 220; i++) {
            assertEquals(71, road[i], 1.0E-9, "clearance must hold across the whole channel, i=" + i);
        }
        for (int i = 1; i < n; i++) {
            assertTrue(Math.abs(road[i] - road[i - 1]) <= RoadCorridorProfile.MAX_GRADE + 1.0E-9);
        }
        assertEquals(64, road[120], 1.0E-9, "the ramp is no longer than the climb needs");
        assertEquals(64, road[280], 1.0E-9);
    }

    @Test
    void onlyWaterWideEnoughForBoatsIsAChannelUnlessTheLegIsMarked() {
        int n = 300;
        boolean[] wet = new boolean[n];
        for (int i = 50; i < 60; i++) {
            wet[i] = true;
        }
        for (int i = 150; i < 200; i++) {
            wet[i] = true;
        }
        boolean[] channel = RoadCorridorProfile.channel(wet, new boolean[n]);
        for (int i = 0; i < 140; i++) {
            assertFalse(channel[i], "a ten block stream is not a shipping channel");
        }
        assertFalse(channel[150]);
        assertFalse(channel[199]);
        for (int i = 150 + RoadCorridorProfile.CHANNEL_SHORE; i < 200 - RoadCorridorProfile.CHANNEL_SHORE; i++) {
            assertTrue(channel[i], "the middle of wide water must be kept clear, i=" + i);
        }
        boolean[] marked = new boolean[n];
        Arrays.fill(marked, true);
        assertTrue(RoadCorridorProfile.channel(wet, marked)[55], "a leg planned as a major bridge is cleared for boats regardless");
    }

    @Test
    void anyWindowWithEnoughContextReproducesTheWholeProfileExactly() {
        Random random = new Random(3);
        int n = 900;
        double[] ground = new double[n];
        double[] spacing = new double[n - 1];
        double[] minimum = none(n);
        double[] cleared = none(n);
        double level = 70;
        for (int i = 0; i < n; i++) {
            level += random.nextInt(5) - 2;
            ground[i] = level;
            if (i < n - 1) {
                spacing[i] = 0.7 + random.nextDouble() * 0.3;
            }
            if (i % 150 > 120) {
                minimum[i] = level + 1;
                cleared[i] = level + 8;
            }
        }
        double[] whole = RoadCorridorProfile.design(ground, spacing, minimum, cleared);
        int reach = RoadCorridorProfile.DESIGN_REACH;
        for (int from = 0; from < n; from += 128) {
            int to = Math.min(n, from + 128);
            int low = Math.max(0, from - reach);
            int high = Math.min(n, to + reach);
            double[] window = RoadCorridorProfile.design(Arrays.copyOfRange(ground, low, high),
                    Arrays.copyOfRange(spacing, low, high - 1), Arrays.copyOfRange(minimum, low, high),
                    Arrays.copyOfRange(cleared, low, high));
            assertArrayEquals(Arrays.copyOfRange(whole, from, to), Arrays.copyOfRange(window, from - low, to - low), 0.0,
                    "window starting at " + from + " disagrees with the whole profile");
        }
    }
}
