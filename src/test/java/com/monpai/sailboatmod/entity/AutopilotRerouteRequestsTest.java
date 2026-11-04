package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutopilotRerouteRequestsTest {
    @Test
    void forwardLookaheadSharesTheSlotWithoutStoppingDistantNavigation() throws Exception {
        AutopilotRerouteRequests requests = new AutopilotRerouteRequests();
        assertFalse(requests.blocksMotion());
        AutopilotRerouteRequests.Request lookahead = requests.claimLookahead();
        assertNotNull(lookahead);
        assertTrue(requests.isInFlight(), "background lookahead still excludes concurrent rescue work");
        assertFalse(requests.blocksMotion(), "only the guard's near-obstacle hold should stop forward lookahead");
        assertNull(requests.claim());
        assertTrue(requests.complete(lookahead));
        assertFalse(requests.blocksMotion());
    }

    @Test
    void localRescueBlocksMotionAndLateLookaheadCannotReleaseIt() throws Exception {
        AutopilotRerouteRequests requests = new AutopilotRerouteRequests();
        AutopilotRerouteRequests.Request lookahead = requests.claimLookahead();
        requests.invalidate();
        assertTrue(lookahead.cancelled());
        AutopilotRerouteRequests.Request rescue = requests.claim();
        assertTrue(requests.blocksMotion());
        assertNull(requests.claimLookahead());
        assertFalse(requests.complete(lookahead));
        assertTrue(requests.blocksMotion(), "an old forward repair must not release a newer rescue hold");
        assertTrue(requests.complete(rescue));
        assertFalse(requests.blocksMotion());
    }

    @Test
    void onlyOneRerouteCanOwnTheSlot() {
        AutopilotRerouteRequests requests = new AutopilotRerouteRequests();

        assertNotNull(requests.claim());
        assertTrue(requests.isInFlight());
        assertNull(requests.claim());
    }

    @Test
    void theCurrentRequestCompletesOnlyOnce() {
        AutopilotRerouteRequests requests = new AutopilotRerouteRequests();
        AutopilotRerouteRequests.Request request = requests.claim();

        assertTrue(requests.complete(request));
        assertFalse(requests.isInFlight());
        assertFalse(requests.complete(request));
    }

    @Test
    void stoppingAVoyageDiscardsItsSuccessOrFailureCallback() {
        AutopilotRerouteRequests requests = new AutopilotRerouteRequests();
        AutopilotRerouteRequests.Request previous = requests.claim();

        requests.invalidate();

        assertFalse(requests.complete(previous), "a stopped voyage cannot apply either a result or its failure fallback");
        assertFalse(requests.isInFlight());
    }

    @Test
    void restartedVoyageKeepsItsSlotWhenTheOldCallbackArrives() {
        AutopilotRerouteRequests requests = new AutopilotRerouteRequests();
        AutopilotRerouteRequests.Request previous = requests.claim();
        requests.invalidate();
        AutopilotRerouteRequests.Request restarted = requests.claim();

        assertNotNull(restarted);
        assertFalse(requests.complete(previous));
        assertTrue(requests.isInFlight(), "discarding the old result must not release the restarted voyage's slot");
        assertTrue(requests.complete(restarted));
        assertFalse(requests.isInFlight());
    }

    @Test
    void aDuplicateCallbackCannotReleaseTheNextRequestInTheSameVoyage() {
        AutopilotRerouteRequests requests = new AutopilotRerouteRequests();
        AutopilotRerouteRequests.Request previous = requests.claim();
        assertTrue(requests.complete(previous));
        AutopilotRerouteRequests.Request current = requests.claim();

        assertFalse(requests.complete(previous));
        assertFalse(requests.complete(null));
        assertTrue(requests.isInFlight());
        assertTrue(requests.complete(current));
    }
}
