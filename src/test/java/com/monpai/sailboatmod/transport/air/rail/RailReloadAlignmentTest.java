package com.monpai.sailboatmod.transport.air.rail;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailReloadAlignmentTest {
    @Test void reservedParkedStartAllowsNativeFloatingPointDriftOnly() {
        Vec3 saved = new Vec3(135549.81190873584, 136.7713294740383, -28707.49969792455);
        Vec3 actual = new Vec3(135549.81190873584, 136.77132947403922, -28707.499697913376);
        assertFalse(RailReloadAlignment.permits(saved, actual));
        assertTrue(RailReloadAlignment.permitsParkedStart(saved, actual));
        assertTrue(RailReloadAlignment.permitsParkedStart(saved, saved.add(.03, -.1, .04)));
        assertFalse(RailReloadAlignment.permitsParkedStart(saved, saved.add(0, .000002, 0)));
        assertFalse(RailReloadAlignment.permitsParkedStart(saved, saved.add(.05001, 0, 0)));
    }
    private final Vec3 saved=new Vec3(-15028.77,-57.3481,5223.894);

    @Test void sameAnchorAndObservedGravityDropAreEligible() {
        assertTrue(RailReloadAlignment.permits(saved,saved));
        assertTrue(RailReloadAlignment.permits(saved,saved.add(0,-.053953,0)));
    }
    @Test void horizontalRadiusIsEuclideanAndIncludesExactBoundary() {
        assertTrue(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(.03,-.1,.04)));
        assertTrue(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(.05,-.25,0)));
        assertFalse(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(.04,-.1,.04)));
        assertFalse(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(.050001,-.1,0)));
    }
    @Test void downwardLimitIsInclusiveButLargerDriftAndAnyUpwardMovementAreRejected() {
        assertTrue(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(0,-.25,0)));
        assertFalse(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(0,-.250001,0)));
        assertFalse(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(0,.000001,0)));
        assertFalse(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(0,1e-12,0)));
        assertFalse(RailReloadAlignment.permits(saved,saved.add(0,1,0)));
    }
    @Test void onlyBoundaryRoundoffGetsANanometreTolerance() {
        assertTrue(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(.05+5e-10,-.25-5e-10,0)));
        assertFalse(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(.05+2e-9,0,0)));
        assertFalse(RailReloadAlignment.permits(Vec3.ZERO,new Vec3(0,-.25-2e-9,0)));
    }
    @Test void nullNonfiniteAndOverflowingDifferencesCannotQualify() {
        assertFalse(RailReloadAlignment.permits(null,Vec3.ZERO));assertFalse(RailReloadAlignment.permits(Vec3.ZERO,null));
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            for(Vec3 v:new Vec3[]{new Vec3(bad,0,0),new Vec3(0,bad,0),new Vec3(0,0,bad)}) {
                assertFalse(RailReloadAlignment.permits(v,Vec3.ZERO));assertFalse(RailReloadAlignment.permits(Vec3.ZERO,v));
            }
        assertFalse(RailReloadAlignment.permits(new Vec3(Double.MAX_VALUE,0,0),new Vec3(-Double.MAX_VALUE,0,0)));
        assertFalse(RailReloadAlignment.permits(new Vec3(0,Double.MAX_VALUE,0),new Vec3(0,-Double.MAX_VALUE,0)));
    }
    @Test void eligibilityCannotMoveOrMutateSavedOrActualSamples() {
        var actual=saved.add(.01,-.2,.01);var beforeSaved=new Vec3(saved.x,saved.y,saved.z);var beforeActual=new Vec3(actual.x,actual.y,actual.z);
        assertTrue(RailReloadAlignment.permits(saved,actual));assertEquals(beforeSaved,saved);assertEquals(beforeActual,actual);
        assertFalse(RailReloadAlignment.permits(actual,saved),"Reversing saved/actual is an upward correction, not an eligible drop");
    }
}
