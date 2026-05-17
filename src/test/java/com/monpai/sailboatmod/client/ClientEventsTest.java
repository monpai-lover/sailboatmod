package com.monpai.sailboatmod.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientEventsTest {
    @Test
    void embeddedBlockUiLoaderRegistersOnlyWhenBlockUiModIsAbsent() {
        assertTrue(ClientEvents.shouldRegisterEmbeddedBlockUiLoader(false));
        assertFalse(ClientEvents.shouldRegisterEmbeddedBlockUiLoader(true));
    }
}
