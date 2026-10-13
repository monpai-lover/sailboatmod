package com.monpai.sailboatmod.client.ui.state;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiStateHelpersTest {
    @Test
    void pendingRequestBlocksSecondSendUntilResolved() {
        PendingRequests pending = new PendingRequests(100);
        assertTrue(pending.begin("buy", 0));
        assertFalse(pending.begin("buy", 5), "a second click while waiting must not send again");
        assertTrue(pending.begin("list", 5), "other actions stay independent");
        pending.resolve("buy");
        assertTrue(pending.begin("buy", 6));
    }

    @Test
    void pendingRequestReportsTimeoutOnce() {
        PendingRequests pending = new PendingRequests(100);
        pending.begin("buy", 0);
        assertEquals(Optional.empty(), pending.pollTimedOut(99));
        assertEquals(Optional.of("buy"), pending.pollTimedOut(100));
        assertEquals(Optional.empty(), pending.pollTimedOut(200));
        assertFalse(pending.isPending("buy"));
    }

    @Test
    void confirmGateNeedsSecondPressOnSameTargetWithinWindow() {
        ConfirmGate gate = new ConfirmGate(60);
        assertFalse(gate.press("order:a", 0));
        assertTrue(gate.isArmed("order:a", 10));
        assertTrue(gate.press("order:a", 10));
        assertFalse(gate.isArmed("order:a", 11), "confirming disarms");
    }

    @Test
    void confirmGateNeverConfirmsADifferentOrExpiredTarget() {
        ConfirmGate gate = new ConfirmGate(60);
        gate.press("order:a", 0);
        assertFalse(gate.press("order:b", 5), "pressing another row re-arms instead of confirming");
        assertTrue(gate.isArmed("order:b", 5));
        assertFalse(gate.press("order:b", 70), "an expired arm does not confirm");
    }
}
