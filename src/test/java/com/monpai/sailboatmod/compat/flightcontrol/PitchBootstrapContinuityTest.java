package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PitchBootstrapContinuityTest {
    private static final double V1 = 19.5219460842, VR = 23.4263353010;
    private static final double AOA = Math.toRadians(4.612);

    private static double blend(double speed) {
        return NativeFixedWingPitchBootstrap.compensation(0, AOA, speed, V1, VR, false);
    }
    @Test void theObservedVrCrossingKeepsMeasuredFeedbackWithoutAZeroStep() {
        assertEquals(AOA, blend(VR), 0);
        assertTrue(blend(23.27) > AOA * .94, "Real continuation crossed VR here; compensation must taper, not disappear");
        assertEquals(blend(VR), blend(VR - 1e-7), 1e-12);
        assertEquals(AOA, blend(VR + 20), 0);
    }
    @Test void theLowerBandEdgeIsContinuousAndMonotone() {
        double low = .95 * VR;
        assertEquals(0, blend(low), 0);
        assertEquals(0, blend(low - .01), 0);
        assertEquals(0, blend(low + 1e-7), 1e-12);
        double previous = 0;
        for (int i = 0; i <= 100; i++) {
            double next = blend(low + (VR-low)*i/100);
            assertTrue(next >= previous && next <= AOA);
            previous = next;
        }
        assertEquals(AOA * .5, blend((low+VR)/2), 1e-14);
    }
    @Test void v1WinsWhenItIsHigherThanTheFivePercentBandFloor() {
        assertEquals(0, NativeFixedWingPitchBootstrap.compensation(0, AOA, 23.1, 23.2, VR, false), 0);
        assertEquals(AOA * .5, NativeFixedWingPitchBootstrap.compensation(0, AOA, (23.2+VR)/2, 23.2, VR, false), 1e-14);
    }
    @Test void nativeHeldAndNonzeroValuesRemainBitForBitUnchanged() {
        for (double nativeValue : new double[]{0, -0.0, .012345, -.08, Double.NaN}) {
            assertEquals(Double.doubleToRawLongBits(nativeValue), Double.doubleToRawLongBits(
                    NativeFixedWingPitchBootstrap.compensation(nativeValue, AOA, VR, V1, VR, true)));
        }
        assertEquals(.023, NativeFixedWingPitchBootstrap.compensation(.023, AOA, VR, V1, VR, false), 0);
    }
    @Test void invalidEvidenceAndStallRangeRetainNativeResult() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(0, NativeFixedWingPitchBootstrap.compensation(0, invalid, VR, V1, VR, false), 0);
            assertEquals(0, NativeFixedWingPitchBootstrap.compensation(0, AOA, invalid, V1, VR, false), 0);
            assertEquals(0, NativeFixedWingPitchBootstrap.compensation(0, AOA, VR, invalid, VR, false), 0);
            assertEquals(0, NativeFixedWingPitchBootstrap.compensation(0, AOA, VR, V1, invalid, false), 0);
        }
        assertEquals(0, NativeFixedWingPitchBootstrap.compensation(0, AOA, VR, VR, VR, false), 0);
        assertEquals(0, NativeFixedWingPitchBootstrap.compensation(0, AOA, VR, 0, VR, false), 0);
        assertEquals(0, blend(-VR), 0);
        assertEquals(0, blend(V1), 0);
    }
    @Test void negativeMeasuredAoAAlsoTapersWithoutChangingItsSignOrBounds() {
        assertEquals(-AOA / 2, NativeFixedWingPitchBootstrap.compensation(0, -AOA, .975*VR, V1, VR, false), 1e-14);
    }
    @Test void appliedHooksWithoutAnAuthorizedScopeNeverReadOrChangeNativeSettings() throws Exception {
        String[] names={"scopeApplied","measurementApplied","guidanceApplied"};
        var flags=new java.lang.reflect.Field[names.length];
        boolean[] previous=new boolean[names.length];
        for(int i=0;i<names.length;i++) {
            flags[i]=PitchBootstrapCompatibility.class.getDeclaredField(names[i]);
            flags[i].setAccessible(true);previous[i]=flags[i].getBoolean(null);flags[i].setBoolean(null,true);
        }
        try (var denied=NativeFixedWingPitchBootstrap.enter(null,null,null)) {
            for(double original:new double[]{0,-0.0,.015,-.04,Double.longBitsToDouble(0x7ff8000000000051L)})
                assertEquals(Double.doubleToRawLongBits(original),Double.doubleToRawLongBits(
                        NativeFixedWingPitchBootstrap.nativeCompensation(null,original,AOA,VR,-Math.toRadians(8))));
        } finally {
            for(int i=0;i<flags.length;i++) flags[i].setBoolean(null,previous[i]);
        }
    }
    @Test void absentDiagnosticsNeverInventMeasuredArgumentsOrAZeroWeight() throws Exception {
        Object diagnostics=NativeFixedWingPitchBootstrap.diagnostics(null);
        var names=java.util.Arrays.stream(diagnostics.getClass().getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
        for(String name:new String[]{"lastForwardSpeed","lastMeasuredCompensation","lastNativeCompensation",
                "lastReturnedCompensation","lastWeight","lastVr"}) {
            assertTrue(names.contains(name),"Missing native argument observation: "+name);
            assertTrue(Double.isNaN((Double)diagnostics.getClass().getMethod(name).invoke(diagnostics)),name);
        }
    }
    @Test void enteringAScopeDoesNotExtendTheOriginalSampleFreshnessDeadline() {
        long sample=1_000_000_000L, opened=sample+200_000_000L;
        assertTrue(NativeFixedWingPitchBootstrap.freshScope(opened,sample,sample+250_000_000L));
        assertFalse(NativeFixedWingPitchBootstrap.freshScope(opened,sample,sample+250_000_001L));
        assertFalse(NativeFixedWingPitchBootstrap.freshScope(opened,sample,opened+100_000_000L));
        assertFalse(NativeFixedWingPitchBootstrap.freshScope(opened,opened+1,opened));
        assertFalse(NativeFixedWingPitchBootstrap.freshScope(opened,sample,opened-1));
    }
    @Test void aResampledFrameCannotBorrowTheEarlierInvocationEvenWithEqualTimestamps() {
        long now=1_000_000_000L;Object original=new Object(), resampled=new Object();
        assertTrue(NativeFixedWingPitchBootstrap.freshScope(now,now,now,original,original));
        assertFalse(NativeFixedWingPitchBootstrap.freshScope(now,now,now,original,resampled));
        assertFalse(NativeFixedWingPitchBootstrap.freshScope(now,now,now,null,null));
    }
}
