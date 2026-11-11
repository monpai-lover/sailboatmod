package com.monpai.sailboatmod.roadplanner.corridor;

/**
 * Vertical road design: turns the ground under the centreline into a smooth, grade-limited
 * running surface.
 *
 * <p>Every stage is a filter with a fixed reach, so the height at a station depends only on the
 * ground within {@link #REACH} stations of it. Two construction windows that overlap by more than
 * that therefore agree exactly on the stations they share, which is what lets a long road be built
 * window by window without a step where the windows meet.</p>
 *
 * <ol>
 *   <li><b>Close</b> narrow dips up to the lower of their rims, so a gully or stream is spanned
 *       level instead of the road diving into it.</li>
 *   <li><b>Open</b> narrow humps down to their surroundings, so the road cuts through a bump
 *       instead of climbing over it.</li>
 *   <li>Average the steepest-allowed line lying above the ground with the one lying below it. The
 *       result never exceeds the grade limit and splits the remaining relief evenly between cut and
 *       fill.</li>
 *   <li>Blur twice, which rounds every change of grade into a vertical curve.</li>
 *   <li>Where a height must be cleared, as over a shipping channel, raise the line to it and bring
 *       it back down at the grade limit on either side.</li>
 * </ol>
 */
public final class RoadCorridorProfile {
    /** Rise per block of travel. One half-slab every three blocks at the limit. */
    public static final double MAX_GRADE = 1.0D / 3.0D;
    static final int CLOSE_RADIUS = 10;
    static final int OPEN_RADIUS = 3;
    /**
     * How far the grade limit looks. It is exact for climbs of up to {@code GRADE_WINDOW * MAX_GRADE}
     * blocks; a taller face is still crossed, but more steeply than the limit.
     */
    static final int GRADE_WINDOW = 58;
    static final int SMOOTH_RADIUS = 5;
    /** Reach of the ramps that carry the road up to a height it must clear. */
    static final int LIFT_WINDOW = 30;
    static final int FINAL_SMOOTH_RADIUS = 3;
    static final int MASK_RADIUS = 2;
    /** Water must run this far on each side of a point, along the road, to count as a shipping channel. */
    static final int CHANNEL_RADIUS = 12;
    /** A channel's clearance is guaranteed from this far off either shore. */
    static final int CHANNEL_SHORE = 4;
    /** Stations of context needed on each side for {@link #design} to be exact, given exact inputs. */
    public static final int DESIGN_REACH = Math.max(2 * CLOSE_RADIUS + 2 * OPEN_RADIUS + GRADE_WINDOW + 2 * SMOOTH_RADIUS,
            FINAL_SMOOTH_RADIUS + LIFT_WINDOW) + FINAL_SMOOTH_RADIUS;
    /** Stations of context needed on each side for {@link #channel} to be exact. */
    public static final int CHANNEL_REACH = 2 * CHANNEL_RADIUS + CHANNEL_SHORE;
    /** Stations of context needed on each side for {@link #elevated} to be exact. */
    public static final int REACH = Math.max(DESIGN_REACH, CHANNEL_REACH + FINAL_SMOOTH_RADIUS + LIFT_WINDOW + FINAL_SMOOTH_RADIUS)
            + 4 * MASK_RADIUS + 1;

    private RoadCorridorProfile() {
    }

    /**
     * @param ground  height the road would like to sit at, per station
     * @param spacing true distance from station {@code i} to {@code i + 1}
     * @param minimum lowest acceptable surface per station, or negative infinity
     * @param cleared height the surface must reach per station, approached by ramps, or negative infinity
     */
    public static double[] design(double[] ground, double[] spacing, double[] minimum, double[] cleared) {
        int n = ground.length;
        if (n == 0) {
            return new double[0];
        }
        double[] closed = slide(slide(ground, CLOSE_RADIUS, true), CLOSE_RADIUS, false);
        double[] opened = slide(slide(closed, OPEN_RADIUS, false), OPEN_RADIUS, true);
        double[] graded = new double[n];
        for (int i = 0; i < n; i++) {
            double above = opened[i];
            double below = opened[i];
            double distance = 0.0D;
            for (int j = i + 1; j < n && j <= i + GRADE_WINDOW; j++) {
                distance += spacing[j - 1];
                above = Math.max(above, opened[j] - MAX_GRADE * distance);
                below = Math.min(below, opened[j] + MAX_GRADE * distance);
            }
            distance = 0.0D;
            for (int j = i - 1; j >= 0 && j >= i - GRADE_WINDOW; j--) {
                distance += spacing[j];
                above = Math.max(above, opened[j] - MAX_GRADE * distance);
                below = Math.min(below, opened[j] + MAX_GRADE * distance);
            }
            graded[i] = (above + below) / 2.0D;
        }
        double[] smooth = blur(blur(graded, SMOOTH_RADIUS), SMOOTH_RADIUS);
        if (cleared != null) {
            // Widening the cleared stretch first keeps the final blur from shaving its ends.
            double[] target = slide(cleared, FINAL_SMOOTH_RADIUS, true);
            for (int i = 0; i < n; i++) {
                double lift = target[i];
                double distance = 0.0D;
                for (int j = i + 1; j < n && j <= i + LIFT_WINDOW; j++) {
                    distance += spacing[j - 1];
                    lift = Math.max(lift, target[j] - MAX_GRADE * distance);
                }
                distance = 0.0D;
                for (int j = i - 1; j >= 0 && j >= i - LIFT_WINDOW; j--) {
                    distance += spacing[j];
                    lift = Math.max(lift, target[j] - MAX_GRADE * distance);
                }
                graded[i] = Math.max(smooth[i], lift);
            }
            smooth = blur(graded, FINAL_SMOOTH_RADIUS);
        } else {
            smooth = blur(smooth, FINAL_SMOOTH_RADIUS);
        }
        if (minimum != null) {
            for (int i = 0; i < n; i++) {
                smooth[i] = Math.max(smooth[i], minimum[i]);
            }
        }
        return smooth;
    }

    /**
     * Decides where the road is carried on a deck instead of on fill. Short gaps between raised
     * runs are joined and raised runs too short to read as a bridge are dropped, except over water.
     *
     * @param raised station would stand clear of the ground by enough to want a deck
     * @param wet    station is over water and must always be decked
     */
    public static boolean[] elevated(boolean[] raised, boolean[] wet) {
        int n = raised.length;
        boolean[] joined = spread(spread(raised, MASK_RADIUS, true), MASK_RADIUS, false);
        boolean[] kept = spread(spread(joined, MASK_RADIUS, false), MASK_RADIUS, true);
        boolean[] overWater = spread(wet, 1, true);
        boolean[] result = new boolean[n];
        for (int i = 0; i < n; i++) {
            result[i] = kept[i] || overWater[i];
        }
        return result;
    }

    /**
     * Finds where the road crosses water wide enough for boats, and so must leave them headroom.
     * Only the middle of such a crossing is returned; the deck is free to come down to the banks.
     *
     * @param wet    station is over water
     * @param marked station lies on a leg planned as a major bridge, whose water always counts
     */
    public static boolean[] channel(boolean[] wet, boolean[] marked) {
        boolean[] wide = spread(spread(wet, CHANNEL_RADIUS, false), CHANNEL_RADIUS, true);
        for (int i = 0; i < wide.length; i++) {
            wide[i] = wide[i] || (wet[i] && marked[i]);
        }
        return spread(wide, CHANNEL_SHORE, false);
    }

    /** Sliding maximum (or minimum) over {@code [i - radius, i + radius]}, clamped at the ends. */
    private static double[] slide(double[] values, int radius, boolean maximum) {
        int n = values.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double best = values[i];
            int from = Math.max(0, i - radius);
            int to = Math.min(n - 1, i + radius);
            for (int j = from; j <= to; j++) {
                best = maximum ? Math.max(best, values[j]) : Math.min(best, values[j]);
            }
            out[i] = best;
        }
        return out;
    }

    private static boolean[] spread(boolean[] values, int radius, boolean any) {
        int n = values.length;
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            boolean result = !any;
            int from = Math.max(0, i - radius);
            int to = Math.min(n - 1, i + radius);
            for (int j = from; j <= to; j++) {
                if (values[j] == any) {
                    result = any;
                    break;
                }
            }
            out[i] = result;
        }
        return out;
    }

    /** Mean over {@code [i - radius, i + radius]}; the ends repeat their last value. */
    private static double[] blur(double[] values, int radius) {
        int n = values.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = 0.0D;
            for (int j = i - radius; j <= i + radius; j++) {
                sum += values[Math.max(0, Math.min(n - 1, j))];
            }
            out[i] = sum / (2 * radius + 1);
        }
        return out;
    }
}
