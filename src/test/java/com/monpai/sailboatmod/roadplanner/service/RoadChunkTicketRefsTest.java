package com.monpai.sailboatmod.roadplanner.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadChunkTicketRefsTest {
    @Test
    void twoJobsSharingAChunkKeepItUntilBothAreDone() {
        RoadChunkTicketRefs refs = new RoadChunkTicketRefs();
        assertTrue(refs.acquire(7L), "the first job adds the ticket");
        assertFalse(refs.acquire(7L), "the second job reuses it");
        assertFalse(refs.release(7L), "one job is still waiting: the ticket stays");
        assertTrue(refs.release(7L), "the last job removes it");
        assertFalse(refs.release(7L), "releasing again does nothing");
        assertEquals(0, refs.size());
    }
}
