package com.monpai.sailboatmod.route;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class AutopilotRecoveryGuardTest {
    @Test void firstAttemptAllowed(){var g=new AutopilotRecoveryGuard();assertEquals(AutopilotRecoveryGuard.Decision.ALLOW,g.request(0,10,10));}
    @Test void repeatedCallbacksCannotRefillWithoutMovement(){var g=new AutopilotRecoveryGuard();g.request(0,0,0);g.request(100,0,0);g.request(300,0,0);assertEquals(AutopilotRecoveryGuard.Decision.EXHAUSTED,g.request(600,0,0));}
    @Test void cooldownAppliesBetweenAttempts(){var g=new AutopilotRecoveryGuard();g.request(0,0,0);assertEquals(AutopilotRecoveryGuard.Decision.COOLDOWN,g.request(70,0,0));}
    @Test void realMovementRestoresRecovery(){var g=new AutopilotRecoveryGuard();g.request(0,0,0);g.moved(4,0);assertEquals(AutopilotRecoveryGuard.Decision.ALLOW,g.request(1,4,0));assertEquals(1,g.attempts());}
    @Test void smallOscillationDoesNotReset(){var g=new AutopilotRecoveryGuard();g.request(0,0,0);for(int i=0;i<1000;i++)g.moved(Math.sin(i),Math.cos(i));assertEquals(1,g.attempts());}
    @Test void pausedGameClockDoesNotConsumeCooldown(){var g=new AutopilotRecoveryGuard();g.request(100,0,0);for(int i=0;i<10000;i++)assertEquals(AutopilotRecoveryGuard.Decision.COOLDOWN,g.request(100,0,0));assertEquals(1,g.attempts());}
    @Test void explicitRestartClearsGuard(){var g=new AutopilotRecoveryGuard();g.request(0,0,0);g.reset();assertEquals(AutopilotRecoveryGuard.Decision.ALLOW,g.request(1,0,0));}
    @Test void phaseCodesDecodeSafely(){assertEquals(NavigationPhase.IDLE,NavigationPhase.of(999));assertEquals(NavigationPhase.PAUSED,NavigationPhase.of(NavigationPhase.PAUSED.ordinal()));}
    @Test void waitingAndRecoveryAreNotShownAsSailing(){assertEquals("WAITING_TERRAIN",NavigationPhase.WAITING_TERRAIN.traceStatus());assertEquals("REROUTING",NavigationPhase.RECOVERING.traceStatus());assertEquals("STUCK",NavigationPhase.BLOCKED.traceStatus());}
}
