package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.Arrays;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Pure, single-seed correction of an allocation residual. A smaller residual is
 * only a search direction: the caller's original native predicate alone accepts
 * a result. All callback arguments and returned controls have independent storage.
 */
public final class BoundedTrimSearch {
    private static final int MAX_AXES = 16;
    private static final int RESIDUAL_SIZE = 6;
    private static final int MAX_EVALUATIONS = 192;
    private static final int MAX_ITERATIONS = 12;
    private static final int BACKTRACK_STEPS = 6;
    private static final double DIFFERENCE_STEP = .002;
    private static final double MAX_ANGLE_STEP = 5;
    private static final double DAMPING = 1.0e-8;
    private static final double MIN_PIVOT = 1.0e-14;

    private BoundedTrimSearch() {}

    public static double[] search(double[] seed, double[] minimum, double[] maximum,
                                  Function<double[], double[]> residual,
                                  Predicate<double[]> nativeAcceptance, int maxEvaluations) {
        if (seed == null || minimum == null || maximum == null || residual == null
                || nativeAcceptance == null || maxEvaluations <= 0 || seed.length == 0
                || seed.length > MAX_AXES || minimum.length != seed.length || maximum.length != seed.length) {
            return null;
        }
        int axes = seed.length;
        double[] lower = minimum.clone(), upper = maximum.clone(), angles = seed.clone();
        for (int axis = 0; axis < axes; axis++) {
            if (!Double.isFinite(angles[axis]) || !Double.isFinite(lower[axis])
                    || !Double.isFinite(upper[axis]) || lower[axis] > upper[axis]) return null;
            angles[axis] = clamp(angles[axis], lower[axis], upper[axis]);
        }

        Budget budget = new Budget(Math.min(maxEvaluations, MAX_EVALUATIONS), residual, nativeAcceptance);
        if (budget.accepts(angles)) return angles.clone();
        if (budget.failed || budget.remaining == 0) return null;
        double[] current = budget.residual(angles);
        if (current == null) return null;
        double score = squaredNorm(current);
        if (!Double.isFinite(score)) return null;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            double[][] jacobian = new double[RESIDUAL_SIZE][axes];
            for (int axis = 0; axis < axes; axis++) {
                double up = Math.min(DIFFERENCE_STEP, upper[axis] - angles[axis]);
                double down = Math.min(DIFFERENCE_STEP, angles[axis] - lower[axis]);
                double[] trial = angles.clone();
                trial[axis] = clamp(angles[axis] + (up >= down ? up : -down), lower[axis], upper[axis]);
                double step = trial[axis] - angles[axis];
                if (step == 0) continue; // An actually fixed or numerically immovable axis.
                double[] shifted = budget.residual(trial);
                if (shifted == null) return null;
                for (int row = 0; row < RESIDUAL_SIZE; row++) {
                    double derivative = (shifted[row] - current[row]) / step;
                    if (!Double.isFinite(derivative)) return null;
                    jacobian[row][axis] = derivative;
                }
            }

            double[][] normal = new double[axes][axes];
            double[] rhs = new double[axes];
            for (int x = 0; x < axes; x++) {
                for (int y = 0; y < axes; y++) {
                    for (int row = 0; row < RESIDUAL_SIZE; row++) normal[x][y] += jacobian[row][x] * jacobian[row][y];
                    if (!Double.isFinite(normal[x][y])) return null;
                }
                normal[x][x] += DAMPING;
                for (int row = 0; row < RESIDUAL_SIZE; row++) rhs[x] -= jacobian[row][x] * current[row];
                if (!Double.isFinite(normal[x][x]) || !Double.isFinite(rhs[x])) return null;
            }
            double[] delta = solve(normal, rhs);
            if (delta == null) return null;
            double largest = 0;
            for (double value : delta) largest = Math.max(largest, Math.abs(value));
            if (largest == 0) return null;
            if (largest > MAX_ANGLE_STEP) {
                for (int axis = 0; axis < axes; axis++) delta[axis] *= MAX_ANGLE_STEP / largest;
            }

            boolean improved = false;
            for (int backtrack = 0; backtrack < BACKTRACK_STEPS; backtrack++) {
                double[] trial = angles.clone();
                for (int axis = 0; axis < axes; axis++) {
                    trial[axis] = clamp(angles[axis] + Math.scalb(delta[axis], -backtrack), lower[axis], upper[axis]);
                }
                if (Arrays.equals(trial, angles)) continue;
                double[] next = budget.residual(trial);
                if (next == null) return null;
                double nextScore = squaredNorm(next);
                if (!Double.isFinite(nextScore)) return null;
                if (nextScore < score - 1.0e-12) {
                    angles = trial;
                    current = next;
                    score = nextScore;
                    improved = true;
                    if (budget.accepts(angles)) return angles.clone();
                    if (budget.failed || budget.remaining == 0) return null;
                    break;
                }
            }
            if (!improved) return null;
        }
        return null;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double squaredNorm(double[] values) {
        double result = 0;
        for (double value : values) result += value * value;
        return result;
    }

    /** Partial-pivot elimination of the damped, at-most16 by16 normal system. */
    private static double[] solve(double[][] matrix, double[] rhs) {
        int n = rhs.length;
        double[][] rows = new double[n][n + 1];
        for (int i = 0; i < n; i++) {
            System.arraycopy(matrix[i], 0, rows[i], 0, n);
            rows[i][n] = rhs[i];
        }
        for (int column = 0; column < n; column++) {
            int pivot = column;
            for (int row = column + 1; row < n; row++) {
                if (Math.abs(rows[row][column]) > Math.abs(rows[pivot][column])) pivot = row;
            }
            double divisor = rows[pivot][column];
            if (!Double.isFinite(divisor) || Math.abs(divisor) < MIN_PIVOT) return null;
            double[] swapped = rows[column]; rows[column] = rows[pivot]; rows[pivot] = swapped;
            for (int entry = column; entry <= n; entry++) {
                rows[column][entry] /= divisor;
                if (!Double.isFinite(rows[column][entry])) return null;
            }
            for (int row = 0; row < n; row++) if (row != column) {
                double factor = rows[row][column];
                for (int entry = column; entry <= n; entry++) {
                    rows[row][entry] -= factor * rows[column][entry];
                    if (!Double.isFinite(rows[row][entry])) return null;
                }
            }
        }
        double[] result = new double[n];
        for (int row = 0; row < n; row++) result[row] = rows[row][n];
        return result;
    }

    /** Both expensive callbacks consume the same finite allowance. */
    private static final class Budget {
        private int remaining;
        private boolean failed;
        private final Function<double[], double[]> residual;
        private final Predicate<double[]> acceptance;

        private Budget(int remaining, Function<double[], double[]> residual, Predicate<double[]> acceptance) {
            this.remaining = remaining;
            this.residual = residual;
            this.acceptance = acceptance;
        }

        private boolean accepts(double[] angles) {
            if (failed || remaining == 0) return false;
            remaining--;
            try {
                return acceptance.test(angles.clone());
            } catch (RuntimeException exception) {
                failed = true;
                return false;
            }
        }

        private double[] residual(double[] angles) {
            if (failed || remaining == 0) return null;
            remaining--;
            try {
                double[] result = residual.apply(angles.clone());
                if (result == null || result.length != RESIDUAL_SIZE) return null;
                double[] snapshot = result.clone();
                for (double value : snapshot) if (!Double.isFinite(value)) return null;
                return snapshot;
            } catch (RuntimeException exception) {
                failed = true;
                return null;
            }
        }
    }
}
