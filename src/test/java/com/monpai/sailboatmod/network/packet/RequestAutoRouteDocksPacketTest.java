package com.monpai.sailboatmod.network.packet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestAutoRouteDocksPacketTest {
    @Test
    void unloadedCandidateChunksAreNotInspectedForAutoRouteListing() {
        assertFalse(RequestAutoRouteDocksPacket.shouldInspectCandidateChunkForTest(false));
        assertTrue(RequestAutoRouteDocksPacket.shouldInspectCandidateChunkForTest(true));
    }
}
