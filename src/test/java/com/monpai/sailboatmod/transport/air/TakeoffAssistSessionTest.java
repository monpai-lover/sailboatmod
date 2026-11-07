package com.monpai.sailboatmod.transport.air;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Contact.*;
import static org.junit.jupiter.api.Assertions.*;

class TakeoffAssistSessionTest {
    private static final UUID BODY = UUID.fromString("a4631d31-7846-40f1-8361-c4f79bd6a984");
    private static final UUID TASK = UUID.fromString("4173612f-301c-4ed1-8946-51175102c18b");
    private static final String PROFILE = "canonical-profile";

    private static TakeoffAssistSession session() {
        return new TakeoffAssistSession(BODY, TASK, PROFILE, new TakeoffAssistConfig(true, 12));
    }

    private static TakeoffAssistSession.Frame frame(double time) {
        return new TakeoffAssistSession.Frame(BODY, TASK, PROFILE, time, .01, 95, 10, 23.3, 40,
                GROUNDED, true, true, true, true, true);
    }

    private static TakeoffAssistSession.Frame changed(TakeoffAssistSession.Frame f, UUID body, UUID task,
            String profile, double time, double dt, double mass, double speed, double vr,
            TakeoffAssistSession.Contact contact, boolean takeoff, boolean authority,
            boolean propulsion, boolean brakes, boolean fresh) {
        return new TakeoffAssistSession.Frame(body, task, profile, time, dt, mass, speed, vr, 1.05 * vr, contact,
                takeoff, authority, propulsion, brakes, fresh);
    }

    @Test void assistanceNeverRunsAheadOfTheCurrentNativeSpeedCommand() {
        var s=session();
        var limited=new TakeoffAssistSession.Frame(BODY,TASK,PROFILE,10,.025,95,1,23.3,1.03,
                GROUNDED,true,true,true,true,true);
        assertEquals(95*.03,s.evaluate(limited).impulse(),1e-9);
        var ahead=new TakeoffAssistSession.Frame(BODY,TASK,PROFILE,10.025,.025,95,2.976,23.3,1.43,
                GROUNDED,true,true,true,true,true);
        assertEquals(0,s.evaluate(ahead).impulse(),"Actual42 overshoot must not fight the native speed controller");
        var rolling=new TakeoffAssistSession.Frame(BODY,TASK,PROFILE,10.05,.025,95,19.66,23.3,20.93,
                ROLLING,true,true,true,true,true);
        assertEquals(28.5,s.evaluate(rolling).impulse(),1e-9);
    }

    @Test void rotationReferenceDoesNotStopAccelerationBeforeNativeLiftoffDemand() {
        var s = session();
        // Real fix43 ground runs stalled at 1.05 * VR while native takeoff still requested more speed.
        var rotating = new TakeoffAssistSession.Frame(BODY, TASK, PROFILE, 10, .025,
                95, 22.62, 21.679428459064653, 27.3,
                ROLLING, true, true, true, true, true);
        assertEquals(28.5, s.evaluate(rotating).impulse(), 1e-9);
        var nearCommand = new TakeoffAssistSession.Frame(BODY, TASK, PROFILE, 10.025, .025,
                95, 27.29, 21.679428459064653, 27.3,
                ROLLING, true, true, true, true, true);
        assertEquals(.95, s.evaluate(nearCommand).impulse(), 1e-9);
        var atCommand = new TakeoffAssistSession.Frame(BODY, TASK, PROFILE, 10.05, .025,
                95, 27.3, 21.679428459064653, 27.3,
                ROLLING, true, true, true, true, true);
        assertEquals(0, s.evaluate(atCommand).impulse());
        assertEquals("speed_limit", s.status());
    }

    @Test void actualMassAccelerationAndSubstepProducePhysicalImpulse() {
        var s = session();
        assertEquals(11.4, s.evaluate(frame(10)).impulse(), 1e-9);
        assertEquals(11.4, s.totalAppliedImpulse(), 1e-9);
    }

    @Test void disabledOrZeroStrengthConfigurationCannotProduceImpulse() {
        for (var config : new TakeoffAssistConfig[]{TakeoffAssistConfig.DEFAULT, new TakeoffAssistConfig(true, 0)}) {
            var s = new TakeoffAssistSession(BODY, TASK, PROFILE, config);
            assertEquals(0, s.evaluate(frame(10)).impulse());
            assertEquals(0, s.totalAppliedImpulse());
        }
    }

    @Test void accumulatedImpulseMustRemainFinite() {
        var s = new TakeoffAssistSession(BODY, TASK, PROFILE, new TakeoffAssistConfig(true, 20));
        var f = frame(10);
        assertEquals(Double.MAX_VALUE, s.evaluate(changed(f, BODY, TASK, PROFILE, 10, .05, Double.MAX_VALUE, 0, 1000,
                GROUNDED, true, true, true, true, true)).impulse());
        assertEquals(0, s.evaluate(changed(f, BODY, TASK, PROFILE, 10.05, .05, Double.MAX_VALUE, 0, 1000,
                GROUNDED, true, true, true, true, true)).impulse());
        assertTrue(Double.isFinite(s.totalAppliedImpulse()));
    }

    @Test void frameIsConsumedBeforeCallerAppliesItAndCannotBeReplayed() {
        var s = session();
        assertTrue(s.evaluate(frame(10)).impulse() > 0);
        // Even if a downstream native impulse application throws, evaluating again cannot duplicate it.
        assertEquals(0, s.evaluate(frame(10)).impulse());
        assertEquals(0, s.evaluate(frame(9.99)).impulse());
        assertEquals(11.4, s.totalAppliedImpulse(), 1e-9);
        assertTrue(s.evaluate(frame(10.01)).impulse() > 0);
    }

    @Test void accelerationUsesNoMoreThanActualPhysicsTimeAdvance() {
        var s = session();
        assertEquals(11.4, s.evaluate(frame(10)).impulse(), 1e-9);
        assertEquals(95 * 12 * .002, s.evaluate(frame(10.002)).impulse(), 1e-9);
        assertEquals(95 * 12 * .01, s.evaluate(frame(10.02)).impulse(), 1e-9);
    }

    @Test void unknownAndAirborneContactsPauseTheSameNativeTakeoffEpisode() {
        var s = session();
        var f = frame(10);
        assertTrue(s.evaluate(f).impulse() > 0);
        assertEquals(0, s.evaluate(changed(f, BODY, TASK, PROFILE, 10.01, .01, 95, 10, 23.3,
                UNKNOWN, true, true, true, true, true)).impulse());
        assertTrue(s.evaluate(frame(10.02)).impulse() > 0);
        for (int i=3; i<=52; i++)
            assertEquals(0, s.evaluate(changed(f, BODY, TASK, PROFILE, 10+i*.01, .01, 95, 10, 23.3,
                    AIRBORNE, true, true, true, true, true)).impulse());
        assertTrue(s.evaluate(frame(10.53)).impulse() > 0);
        assertEquals("assisting", s.status());
    }

    @Test void taxiLandingPauseForeignAuthorityAndPowerLossEndEpisode() {
        var f = frame(10);
        for (int condition = 0; condition < 3; condition++) {
            var s = session();
            assertTrue(s.evaluate(f).impulse() > 0);
            assertEquals(0, s.evaluate(changed(f, BODY, TASK, PROFILE, 10.01, .01, 95, 10, 23.3,
                    GROUNDED, condition != 0, condition != 1, condition != 2, true, true)).impulse());
            assertEquals(0, s.evaluate(frame(10.02)).impulse());
        }
    }

    @Test void identityChangeEndsOldEpisodeAndDoesNotRearmWhenOriginalReturns() {
        var f = frame(10);
        for (int identity = 0; identity < 3; identity++) {
            var s = session();
            assertTrue(s.evaluate(f).impulse() > 0);
            assertEquals(0, s.evaluate(changed(f, identity == 0 ? UUID.randomUUID() : BODY,
                    identity == 1 ? UUID.randomUUID() : TASK, identity == 2 ? "other" : PROFILE,
                    10.01, .01, 95, 10, 23.3, GROUNDED, true, true, true, true, true)).impulse());
            assertEquals(0, s.evaluate(frame(10.02)).impulse());
        }
    }

    @Test void staleFrameAndAppliedBrakesCannotAddImpulse() {
        var f = frame(10);
        assertEquals(0, session().evaluate(changed(f, BODY, TASK, PROFILE, 10, .01, 95, 10, 23.3,
                GROUNDED, true, true, true, true, false)).impulse());
        assertEquals(0, session().evaluate(changed(f, BODY, TASK, PROFILE, 10, .01, 95, 10, 23.3,
                GROUNDED, true, true, true, false, true)).impulse());
    }

    @Test void speedIsCappedAtTheNativeCommandWithoutOvershoot() {
        var f = frame(10);
        double limit = 23.3 * 1.05;
        var s = session();
        assertEquals(95 * .03, s.evaluate(changed(f, BODY, TASK, PROFILE, 10, .01, 95, limit - .03, 23.3,
                GROUNDED, true, true, true, true, true)).impulse(), 1e-9);
        assertEquals(0, s.evaluate(changed(f, BODY, TASK, PROFILE, 10.01, .01, 95, limit, 23.3,
                GROUNDED, true, true, true, true, true)).impulse());
        assertTrue(session().evaluate(changed(f, BODY, TASK, PROFILE, 10, .01, 95, 23.3, 23.3,
                GROUNDED, true, true, true, true, true)).impulse() > 0);
    }

    @Test void realPhysicalElapsedTimeBoundsAnEpisodeEvenAcrossContactGaps() {
        var s = session();
        var f = frame(10);
        assertTrue(s.evaluate(f).impulse() > 0);
        assertEquals(0, s.evaluate(changed(f, BODY, TASK, PROFILE, 29.99, .01, 95, 10, 23.3,
                UNKNOWN, true, true, true, true, true)).impulse());
        assertEquals(0, s.evaluate(frame(30)).impulse());
        assertEquals(0, s.evaluate(frame(30.01)).impulse());
        assertEquals("duration_limit", s.status());
    }

    @Test void malformedObservationsDoNotConsumeFrameOrStartDuration() {
        var f = frame(10);
        double[][] bad = {{Double.NaN, .01, 95, 10, 23.3}, {10, 0, 95, 10, 23.3},
                {10, .051, 95, 10, 23.3}, {10, .01, 0, 10, 23.3}, {10, .01, -1, 10, 23.3},
                {10, .01, Double.POSITIVE_INFINITY, 10, 23.3}, {10, .01, 95, -1, 23.3},
                {10, .01, 95, Double.NaN, 23.3}, {10, .01, 95, 10, 0},
                {10, .01, 95, 10, Double.POSITIVE_INFINITY}};
        for (double[] values : bad) {
            var s = session();
            assertEquals(0, s.evaluate(changed(f, BODY, TASK, PROFILE, values[0], values[1], values[2], values[3], values[4],
                    GROUNDED, true, true, true, true, true)).impulse());
            assertEquals(0, s.duration());
            assertTrue(s.evaluate(frame(10)).impulse() > 0);
        }
    }

    @Test void changingStrengthPreservesTimeBudgetAndDisableCannotReplaySameEpisode() {
        var s = session();
        assertTrue(s.evaluate(frame(10)).impulse() > 0);
        s.updateConfig(new TakeoffAssistConfig(true, 5));
        assertEquals(4.75, s.evaluate(frame(10.01)).impulse(), 1e-9);
        assertEquals(0, s.evaluate(frame(30.01)).impulse());
        var disabled = session();
        disabled.updateConfig(new TakeoffAssistConfig(false, 12));
        assertEquals(0, disabled.evaluate(frame(10)).impulse());
        disabled.updateConfig(new TakeoffAssistConfig(true, 12));
        assertEquals(0, disabled.evaluate(frame(10.01)).impulse());
    }
}
