package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class BoundedTrimSearchTest {
    private static double[] six(double force, double torque) {
        return new double[]{force, torque, 0, 0, 0, 0};
    }

    private static boolean close(double actual, double expected) {
        return Math.abs(actual - expected) < 1.0e-6;
    }

    @Test void findsCoupledControlsThatNoSingleAxisCandidateCanPass() {
        Predicate<double[]> nativeAcceptance = a -> close(a[0] + a[1], 4) && close(a[0] - a[1], 2);
        assertFalse(nativeAcceptance.test(new double[]{3, 0}));
        assertFalse(nativeAcceptance.test(new double[]{0, 1}));
        double[] result = BoundedTrimSearch.search(new double[]{0, 0}, new double[]{-20, -20},
                new double[]{20, 20}, a -> six(a[0] + a[1] - 4, a[0] - a[1] - 2),
                nativeAcceptance, 192);
        assertNotNull(result, "Joint force/torque correction must find a native-accepted pair");
        assertTrue(nativeAcceptance.test(result));
        assertArrayEquals(new double[]{3, 1}, result, 1.0e-6);
    }

    @Test void aLowOrZeroResidualNeverOverridesTheNativeAcceptanceGate() {
        assertNull(BoundedTrimSearch.search(new double[]{0}, new double[]{-20}, new double[]{20},
                a -> six(a[0] - 2, 0), a -> false, 192));
        assertNull(BoundedTrimSearch.search(new double[]{0}, new double[]{-20}, new double[]{20},
                a -> six(0, 0), a -> false, 192));
    }

    @Test void alreadyAcceptedSeedNeedsNoResidualEvaluationAndReturnsAnIndependentCopy() {
        double[] seed = {2, -3};
        AtomicInteger accepted = new AtomicInteger();
        double[] result = BoundedTrimSearch.search(seed, new double[]{-10, -10}, new double[]{10, 10},
                a -> { fail("Already accepted controls must not invoke the allocation residual"); return null; },
                a -> { accepted.incrementAndGet(); return true; }, 1);
        assertNotNull(result);
        assertArrayEquals(seed, result);
        assertNotSame(seed, result);
        assertEquals(1, accepted.get());
    }

    @Test void usesActualAsymmetricBoundsRatherThanAnAssumedNativeAngleRange() {
        Predicate<double[]> accepted = a -> close(a[0], -1.25) && close(a[1], 6);
        double[] result = BoundedTrimSearch.search(new double[]{-1.4, 4}, new double[]{-2, 3},
                new double[]{-1, 7}, a -> {
                    assertTrue(a[0] >= -2 && a[0] <= -1 && a[1] >= 3 && a[1] <= 7);
                    return six(a[0] + 1.25, a[1] - 6);
                }, accepted, 192);
        assertNotNull(result);
        assertTrue(accepted.test(result));
    }

    @Test void usesAnInBoundsDifferenceAtTheUpperLimitAndCanLeaveThatLimit() {
        double[] result = BoundedTrimSearch.search(new double[]{1}, new double[]{-2}, new double[]{1},
                a -> { assertTrue(a[0] >= -2 && a[0] <= 1); return six(a[0] - .4, 0); },
                a -> close(a[0], .4), 192);
        assertNotNull(result);
        assertEquals(.4, result[0], 1.0e-6);
    }

    @Test void fixedAxesRemainFixedWhileOtherControlsCanBeSolved() {
        double[] result = BoundedTrimSearch.search(new double[]{2, 0}, new double[]{2, -3},
                new double[]{2, 3}, a -> {
                    assertEquals(2, a[0]);
                    return six(a[1] - 1, 0);
                }, a -> close(a[0], 2) && close(a[1], 1), 192);
        assertNotNull(result);
        assertArrayEquals(new double[]{2, 1}, result, 1.0e-6);
    }

    @Test void clampsTheSeedBeforeCallingEitherNativeCallback() {
        AtomicInteger residuals = new AtomicInteger();
        double[] seed = {50};
        double[] result = BoundedTrimSearch.search(seed, new double[]{-1}, new double[]{1},
                a -> { residuals.incrementAndGet(); return six(0, 0); },
                a -> { assertEquals(1, a[0]); return true; }, 1);
        assertNotNull(result);
        assertEquals(1, result[0]);
        assertEquals(50, seed[0]);
        assertEquals(0, residuals.get());
    }

    @Test void callbackArrayMutationCannotCorruptTheWorkingControlsOrCallerArrays() {
        double[] seed = {0, 0}, lower = {-10, -10}, upper = {10, 10};
        Function<double[], double[]> residual = a -> {
            double[] r = six(a[0] + a[1] - 4, a[0] - a[1] - 2);
            Arrays.fill(a, Double.NaN);
            return r;
        };
        Predicate<double[]> accepted = a -> {
            boolean valid = close(a[0], 3) && close(a[1], 1);
            Arrays.fill(a, -999);
            return valid;
        };
        double[] result = BoundedTrimSearch.search(seed, lower, upper, residual, accepted, 192);
        assertNotNull(result);
        assertArrayEquals(new double[]{3, 1}, result, 1.0e-6);
        assertArrayEquals(new double[]{0, 0}, seed);
        assertArrayEquals(new double[]{-10, -10}, lower);
        assertArrayEquals(new double[]{10, 10}, upper);
    }

    @Test void zeroOrNegativeBudgetInvokesNeitherCallback() {
        for (int budget : new int[]{0, -1}) {
            assertNull(BoundedTrimSearch.search(new double[]{0}, new double[]{-1}, new double[]{1},
                    a -> { fail("No residual budget"); return null; },
                    a -> { fail("No acceptance budget"); return true; }, budget));
        }
    }

    @Test void sharesTheRequestedHardBudgetAcrossResidualAndAcceptanceCalls() {
        for (int limit : new int[]{1, 2, 3, 7}) {
            AtomicInteger callbacks = new AtomicInteger();
            assertNull(BoundedTrimSearch.search(new double[]{0, 0}, new double[]{-100, -100},
                    new double[]{100, 100}, a -> { callbacks.incrementAndGet(); return six(a[0] - 50, a[1] - 50); },
                    a -> { callbacks.incrementAndGet(); return false; }, limit));
            assertTrue(callbacks.get() <= limit, "Acceptance and residual share one hard limit");
        }
    }

    @Test void callerCannotExpandTheTotalEvaluationCapBeyond192() {
        double[] lower = new double[16], upper = new double[16];
        Arrays.fill(lower, -1000); Arrays.fill(upper, 1000);
        AtomicInteger callbacks = new AtomicInteger();
        assertNull(BoundedTrimSearch.search(new double[16], lower, upper,
                a -> { callbacks.incrementAndGet(); return six(a[0] - 100, 0); },
                a -> { callbacks.incrementAndGet(); return false; }, 10000));
        assertTrue(callbacks.get() > 100, "Exercise a real multi-axis search rather than rejecting16 axes");
        assertTrue(callbacks.get() <= 192, "Large caller budget must not expand bounded native work");
    }

    @Test void anUnreachableTargetCannotBypassTheTwelveRoundFiveDegreeStepLimit() {
        double[] farthest = {0};
        assertNull(BoundedTrimSearch.search(new double[]{0}, new double[]{-200}, new double[]{200},
                a -> { farthest[0] = Math.max(farthest[0], a[0]); return six(a[0] - 100, 0); },
                a -> close(a[0], 100), 192));
        assertTrue(farthest[0] <= 61, "A bounded fallback cannot jump100 degrees or iterate until success");
    }

    @Test void nonFiniteMalformedOrUnresponsiveResidualsFailClosed() {
        for (double[] invalid : new double[][]{
                {Double.NaN, 0, 0, 0, 0, 0}, {Double.POSITIVE_INFINITY, 0, 0, 0, 0, 0},
                {1.0e308, 0, 0, 0, 0, 0}, {1, 2, 3, 4, 5}}) {
            assertNull(BoundedTrimSearch.search(new double[]{0}, new double[]{-1}, new double[]{1},
                    a -> invalid, a -> false, 192));
        }
        AtomicInteger calls = new AtomicInteger();
        assertNull(BoundedTrimSearch.search(new double[]{0, 0}, new double[]{-1, -1}, new double[]{1, 1},
                a -> { calls.incrementAndGet(); return six(1, 0); }, a -> false, 192));
        assertTrue(calls.get() <= 192);
    }

    @Test void invalidGeometryIsRejectedWithoutInvokingNativeCallbacks() {
        Function<double[], double[]> residual = a -> { fail("Invalid input must not reach native residual"); return null; };
        Predicate<double[]> accepted = a -> { fail("Invalid input must not reach native acceptance"); return true; };
        assertNull(BoundedTrimSearch.search(null, new double[]{0}, new double[]{1}, residual, accepted, 192));
        assertNull(BoundedTrimSearch.search(new double[0], new double[0], new double[0], residual, accepted, 192));
        assertNull(BoundedTrimSearch.search(new double[]{0}, new double[]{1}, new double[]{-1}, residual, accepted, 192));
        assertNull(BoundedTrimSearch.search(new double[]{Double.NaN}, new double[]{-1}, new double[]{1}, residual, accepted, 192));
        assertNull(BoundedTrimSearch.search(new double[]{0}, new double[]{Double.NEGATIVE_INFINITY}, new double[]{1}, residual, accepted, 192));
        assertNull(BoundedTrimSearch.search(new double[]{0}, new double[]{-1, -1}, new double[]{1}, residual, accepted, 192));
        assertNull(BoundedTrimSearch.search(new double[17], new double[17], new double[17], residual, accepted, 192));
    }
}
