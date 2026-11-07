package com.monpai.sailboatmod.transport.air;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Contact.*;
import static org.junit.jupiter.api.Assertions.*;

class TakeoffAssistLiftoffTest {
    private static final UUID BODY = UUID.fromString("a4631d31-7846-40f1-8361-c4f79bd6a984");
    private static final UUID TASK = UUID.fromString("4173612f-301c-4ed1-8946-51175102c18b");
    private static TakeoffAssistSession session() {
        return new TakeoffAssistSession(BODY, TASK, "profile", new TakeoffAssistConfig(true, 20));
    }
    private static TakeoffAssistSession.Frame frame(double time, double dt, TakeoffAssistSession.Contact contact) {
        return new TakeoffAssistSession.Frame(BODY, TASK, "profile", time, dt, 95, 24.5, 23.5, 32,
                contact, true, true, true, true, true);
    }

    @Test void actualShortGroundRollHopPausesForceWithoutEndingTheArmedEpisode() {
        // fix46 native trace: unsupported near 377.45, contact returns at 377.65.
        var s = session();
        assertTrue(s.evaluate(frame(377.425, .025, ROLLING)).impulse() > 0);
        double before = s.totalAppliedImpulse();
        for (int i = 0; i < 8; i++)
            assertEquals(0, s.evaluate(frame(377.45 + i * .025, .025, AIRBORNE)).impulse());
        assertEquals(before, s.totalAppliedImpulse(), "No extra force throughout the actual unsupported interval");
        assertTrue(s.evaluate(frame(377.65, .025, ROLLING)).impulse() > 0,
                "A short hop must not permanently cancel the ongoing ground roll");
    }

    @Test void continuousAirborneContactPausesTheEpisodeAtEverySupportedSubstepRate() {
        for (double dt : new double[]{.01, .025, .05}) {
            var s = session();
            assertTrue(s.evaluate(frame(10, dt, GROUNDED)).impulse() > 0);
            double before = s.totalAppliedImpulse();
            int count = (int)Math.round(.5 / dt);
            for (int i = 1; i <= count; i++)
                assertEquals(0, s.evaluate(frame(10 + i * dt, dt, AIRBORNE)).impulse());
            assertEquals(before, s.totalAppliedImpulse());
            assertEquals("airborne_pending", s.status());
            assertTrue(s.evaluate(frame(10 + (count + 1) * dt, dt, GROUNDED)).impulse() > 0,
                    "Wheel recontact continues the same episode while native takeoff remains active");
        }
    }

    @Test void missingPhysicsObservationsDoNotConfirmContinuousFlight() {
        var s = session();
        assertTrue(s.evaluate(frame(10, .025, GROUNDED)).impulse() > 0);
        assertEquals(0, s.evaluate(frame(10.025, .025, AIRBORNE)).impulse());
        assertEquals(0, s.evaluate(frame(15, .025, AIRBORNE)).impulse());
        assertTrue(s.evaluate(frame(15.025, .025, ROLLING)).impulse() > 0,
                "Wall time or a large native observation gap cannot establish sustained liftoff");
    }

    @Test void uncertainContactBreaksTheContinuousAirborneInterval() {
        var s = session();
        assertTrue(s.evaluate(frame(10, .025, GROUNDED)).impulse() > 0);
        for (int i = 1; i <= 12; i++) assertEquals(0, s.evaluate(frame(10 + i * .025, .025, AIRBORNE)).impulse());
        assertEquals(0, s.evaluate(frame(10.325, .025, UNKNOWN)).impulse());
        for (int i = 14; i <= 25; i++) assertEquals(0, s.evaluate(frame(10 + i * .025, .025, AIRBORNE)).impulse());
        assertTrue(s.evaluate(frame(10.65, .025, ROLLING)).impulse() > 0);
    }

    @Test void repeatedBouncesCannotExtendTheOriginalTwentySecondBudget() {
        var s = session();
        assertTrue(s.evaluate(frame(10, .025, GROUNDED)).impulse() > 0);
        for (int i = 1; i < 800; i++)
            s.evaluate(frame(10 + i * .025, .025, i % 4 == 0 ? ROLLING : AIRBORNE));
        assertEquals(0, s.evaluate(frame(30, .025, ROLLING)).impulse());
        assertEquals("duration_limit", s.status());
    }
}
