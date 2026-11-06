package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.transport.air.TakeoffAssistConfig;
import com.monpai.sailboatmod.transport.air.TakeoffAssistSession;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.transport.air.TakeoffAssistSession.Contact.*;

class NativeTakeoffAssistLifecycleTest {
    @Test void rollingMainWheelsContinueAnArmedEpisodeButCannotInitiallyArmOne() {
        var pending=lifecycle();pending.arm();
        assertEquals(0,pending.evaluate(frame(task,1,ROLLING)).impulse());
        assertTrue(pending.evaluate(frame(task,1.025,GROUNDED)).impulse()>0);
        assertTrue(pending.evaluate(frame(task,1.05,ROLLING)).impulse()>0);
        assertEquals(0,pending.evaluate(frame(task,1.075,UNKNOWN)).impulse());
    }
    private final UUID body = UUID.randomUUID(), task = UUID.randomUUID();
    private final String profile = "installed-profile";
    private final TakeoffAssistConfig enabled = new TakeoffAssistConfig(true, 12);
    private NativeTakeoffAssistLifecycle lifecycle() {
        var lifecycle = new NativeTakeoffAssistLifecycle();
        lifecycle.configure(enabled, true);
        return lifecycle;
    }
    private TakeoffAssistSession.Frame frame(UUID id, double time, TakeoffAssistSession.Contact contact) {
        return new TakeoffAssistSession.Frame(body, id, profile, time, .025, 95, 8, 35, 40,
                contact, true, true, true, true, true);
    }
    @Test void savedEnabledRunningTaskNeedsFreshOwnerArming() {
        var lifecycle = lifecycle();
        assertEquals(0, lifecycle.evaluate(frame(task, 1, GROUNDED)).impulse());
        lifecycle.arm();
        assertTrue(lifecycle.evaluate(frame(task, 1.025, GROUNDED)).impulse() > 0);
    }
    @Test void observedReadyFollowedByANewNativeTaskArmsNormally() {
        var lifecycle = lifecycle();
        lifecycle.ready(body, profile);
        assertTrue(lifecycle.evaluate(frame(task, 1, GROUNDED)).impulse() > 0);
    }
    @Test void readyFromAnotherBodyOrProfileCannotArmTheTask() {
        var lifecycle = lifecycle();
        lifecycle.ready(UUID.randomUUID(), profile);
        assertEquals(0, lifecycle.evaluate(frame(task, 1, GROUNDED)).impulse());
        lifecycle.ready(body, "changed-profile");
        assertEquals(0, lifecycle.evaluate(frame(task, 1.025, GROUNDED)).impulse());
    }
    @Test void explicitArmWaitsForPositiveGroundContactAndNeverStartsInTheAir() {
        var lifecycle = lifecycle();
        lifecycle.arm();
        assertEquals(0, lifecycle.evaluate(frame(task, 1, UNKNOWN)).impulse());
        assertTrue(lifecycle.evaluate(frame(task, 1.025, GROUNDED)).impulse() > 0);
        var other = lifecycle();
        other.arm();
        assertEquals(0, other.evaluate(frame(task, 1, AIRBORNE)).impulse());
        other.arm();
        assertEquals(0, other.evaluate(frame(task, 1.025, GROUNDED)).impulse(), "airborne task cannot be rearmed");
    }
    @Test void pauseAndConfigOffOnCannotResetTheCurrentTask() {
        var lifecycle = lifecycle();
        lifecycle.ready(body, profile);
        assertTrue(lifecycle.evaluate(frame(task, 1, GROUNDED)).impulse() > 0);
        lifecycle.suppress(task);
        lifecycle.configure(TakeoffAssistConfig.DEFAULT, true);
        lifecycle.configure(enabled, true);
        lifecycle.arm();
        lifecycle.ready(body, profile);
        assertEquals(0, lifecycle.evaluate(frame(task, 1.025, GROUNDED)).impulse());
        UUID next = UUID.randomUUID();
        lifecycle.ready(body, profile);
        assertTrue(lifecycle.evaluate(frame(next, 2, GROUNDED)).impulse() > 0);
    }
    @Test void disableAndExpiryPreserveTheSameTasksBudgetAndEndState() {
        for (boolean expire : new boolean[] { false, true }) {
            var lifecycle = lifecycle();
            lifecycle.ready(body, profile);
            assertTrue(lifecycle.evaluate(frame(task, 1, GROUNDED)).impulse() > 0);
            if (expire) lifecycle.expire();
            else lifecycle.configure(TakeoffAssistConfig.DEFAULT, true);
            lifecycle.configure(enabled, true);
            lifecycle.arm();
            assertEquals(0, lifecycle.evaluate(frame(task, 1.025, GROUNDED)).impulse());
        }
    }
    @Test void nativeTakeoffHandoffAfterAirborneCannotRestartTheSameEpisode() {
        var lifecycle = lifecycle();
        lifecycle.ready(body, profile);
        assertTrue(lifecycle.evaluate(frame(task, 1, GROUNDED)).impulse() > 0);
        for(int i=1;i<=20;i++) assertEquals(0, lifecycle.evaluate(frame(task, 1+i*.025, AIRBORNE)).impulse());
        assertEquals(0, lifecycle.evaluate(new TakeoffAssistSession.Frame(body,task,profile,1.525,.025,
                95,8,35,40,AIRBORNE,false,true,true,true,true)).impulse());
        lifecycle.ready(body, profile);
        lifecycle.arm();
        assertEquals(0, lifecycle.evaluate(frame(task, 2, GROUNDED)).impulse());
    }
    @Test void anOldTaskCannotReuseAnotherTasksArmingHistory() {
        var lifecycle = lifecycle();
        lifecycle.arm();
        assertTrue(lifecycle.evaluate(frame(task, 1, GROUNDED)).impulse() > 0);
        lifecycle.suppress(task);
        UUID next = UUID.randomUUID();
        lifecycle.ready(body, profile);
        assertTrue(lifecycle.evaluate(frame(next, 2, GROUNDED)).impulse() > 0);
        lifecycle.ready(body, profile);
        lifecycle.arm();
        assertEquals(0, lifecycle.evaluate(frame(task, 3, GROUNDED)).impulse());
    }
    @Test void sameTaskIdentityChangeEndsItsOriginalEpisodePermanently() {
        for (boolean changedBody : new boolean[] { false, true }) {
            var lifecycle = lifecycle();
            lifecycle.arm();
            assertTrue(lifecycle.evaluate(frame(task, 1, GROUNDED)).impulse() > 0);
            var changed = new TakeoffAssistSession.Frame(changedBody ? UUID.randomUUID() : body,
                    task, changedBody ? profile : "changed-profile", 1.025, .025, 95, 8, 35, 40,
                    GROUNDED, true, true, true, true, true);
            lifecycle.arm();
            assertEquals(0, lifecycle.evaluate(changed).impulse());
            lifecycle.arm();
            assertEquals(0, lifecycle.evaluate(frame(task, 1.05, GROUNDED)).impulse());
        }
    }
}
