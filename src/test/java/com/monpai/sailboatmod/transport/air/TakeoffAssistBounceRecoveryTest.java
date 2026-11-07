package com.monpai.sailboatmod.transport.air;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Contact.*;
import static org.junit.jupiter.api.Assertions.*;

class TakeoffAssistBounceRecoveryTest {
    private static final UUID BODY = UUID.fromString("0bf1d134-7a6b-45d9-825e-4a569c9addce");
    private static final UUID TASK = UUID.fromString("4b127c45-0765-427d-b0b1-8c97c536e2a0");
    private static final String PROFILE = "same-native-profile";
    private static TakeoffAssistSession session() {
        return new TakeoffAssistSession(BODY, TASK, PROFILE, new TakeoffAssistConfig(true, 12));
    }
    private static TakeoffAssistSession.Frame frame(double time, TakeoffAssistSession.Contact contact,
                                                   boolean takeoff, boolean authority, boolean propulsion) {
        return new TakeoffAssistSession.Frame(BODY, TASK, PROFILE, time, .025, 95, 20, 23.426, 26.133,
                contact, takeoff, authority, propulsion, true, true);
    }
    private static TakeoffAssistSession.Frame frame(double time, TakeoffAssistSession.Contact contact) {
        return frame(time, contact, true, true, true);
    }
    private static double longContactGap(TakeoffAssistSession session, TakeoffAssistSession.Contact contact) {
        // fix51 timing, with synthetic fixed mass/speed to isolate episode policy from aerodynamics.
        assertTrue(session.evaluate(frame(10.825, GROUNDED)).impulse() > 0); // original anchor10.8
        assertTrue(session.evaluate(frame(23.475, ROLLING)).impulse() > 0);
        double before = session.totalAppliedImpulse();
        for (int i = 0; i <= 140; i++) {
            assertEquals(0, session.evaluate(frame(23.5 + i * .025, contact)).impulse());
            assertEquals(before, session.totalAppliedImpulse(), "No unsupported/unknown frame can add impulse");
        }
        return before;
    }
    @Test void threeAndAHalfSecondsWithoutSupportCanResumeOnlyOnRealRecontact() {
        var session = session();
        double before = longContactGap(session, AIRBORNE);
        var returned = session.evaluate(frame(27.1, ROLLING));
        assertEquals(95 * 12 * .025, returned.impulse(), 1e-9,
                "Actual wheel recontact inside the original episode must resume ground-only assistance");
        assertEquals(before + returned.impulse(), session.totalAppliedImpulse(), 1e-9);
        assertEquals(27.1 - 10.8, session.duration(), 1e-9, "Recontact does not reset firstPhysicsTime");
    }
    @Test void unknownContactAlsoStaysZeroAndDoesNotCreateAnotherBudget() {
        var session = session();
        longContactGap(session, UNKNOWN);
        assertTrue(session.evaluate(frame(27.1, GROUNDED)).impulse() > 0);
        assertEquals(27.1 - 10.8, session.duration(), 1e-9);
    }
    @Test void originalTwentySecondDeadlineStillClosesTheBouncedEpisode() {
        var session = session();
        longContactGap(session, AIRBORNE);
        assertTrue(session.evaluate(frame(27.1, ROLLING)).impulse() > 0);
        assertTrue(session.evaluate(frame(30.775, ROLLING)).impulse() > 0);
        double before = session.totalAppliedImpulse();
        assertEquals(0, session.evaluate(frame(30.8, ROLLING)).impulse());
        assertEquals("duration_limit", session.status());
        assertEquals(0, session.evaluate(frame(30.825, GROUNDED)).impulse());
        assertEquals(before, session.totalAppliedImpulse());
    }
    @Test void nativeHandoffDuringTheGapPermanentlyClosesTheSameTask() {
        var session = session();
        double before = longContactGap(session, AIRBORNE);
        assertEquals(0, session.evaluate(frame(27.025, AIRBORNE, false, true, true)).impulse());
        assertEquals("not_taking_off", session.status());
        assertEquals(0, session.evaluate(frame(27.1, ROLLING)).impulse());
        assertEquals("not_taking_off", session.status());
        assertEquals(before, session.totalAppliedImpulse());
    }
    @Test void authorityOrPropulsionLossDuringTheGapCannotRecoverOnRecontact() {
        for (boolean authorityLost : new boolean[]{true, false}) {
            var session = session();
            double before = longContactGap(session, AIRBORNE);
            assertEquals(0, session.evaluate(frame(27.025, AIRBORNE, true, !authorityLost, authorityLost)).impulse());
            assertEquals(authorityLost ? "authority_lost" : "propulsion_lost", session.status());
            assertEquals(0, session.evaluate(frame(27.1, GROUNDED)).impulse());
            assertEquals(before, session.totalAppliedImpulse());
        }
    }
}
