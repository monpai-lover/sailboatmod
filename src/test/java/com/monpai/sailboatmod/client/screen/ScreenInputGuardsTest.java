package com.monpai.sailboatmod.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenInputGuardsTest {
    @Test
    void inventoryKeyIsConsumedOnlyWhileEditingText() {
        assertTrue(ScreenInputGuards.shouldConsumeInventoryKeyForTest(true, true));
        assertFalse(ScreenInputGuards.shouldConsumeInventoryKeyForTest(true, false));
        assertFalse(ScreenInputGuards.shouldConsumeInventoryKeyForTest(false, true));
    }
}
