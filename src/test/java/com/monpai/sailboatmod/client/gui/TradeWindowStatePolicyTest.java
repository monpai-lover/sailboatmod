package com.monpai.sailboatmod.client.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeWindowStatePolicyTest {
    @Test
    void refreshForSameTargetWithoutProposalKeepsLocalDraft() {
        assertFalse(TradeWindowStatePolicy.shouldReplaceDraft(
                "target-a", false, "",
                "target-a", false, ""
        ));
    }

    @Test
    void targetOrProposalChangeReplacesLocalDraft() {
        assertTrue(TradeWindowStatePolicy.shouldReplaceDraft(
                "target-a", false, "",
                "target-b", false, ""
        ));
        assertTrue(TradeWindowStatePolicy.shouldReplaceDraft(
                "target-a", false, "",
                "target-a", true, "p1"
        ));
        assertTrue(TradeWindowStatePolicy.shouldReplaceDraft(
                "target-a", true, "p1",
                "target-a", true, "p2"
        ));
    }

    @Test
    void currencyFilterKeepsOnlyDigitsAndParseFallsBackToZero() {
        assertEquals("120045", TradeWindowStatePolicy.filterCurrencyText("12a00 45"));
        assertEquals("", TradeWindowStatePolicy.filterCurrencyText(null));
        assertTrue(TradeWindowStatePolicy.isCurrencyCharacterAllowed('9'));
        assertFalse(TradeWindowStatePolicy.isCurrencyCharacterAllowed('-'));
        assertEquals(9000L, TradeWindowStatePolicy.parseCurrency("9000"));
        assertEquals(0L, TradeWindowStatePolicy.parseCurrency("999999999999999999999999"));
        assertEquals(0L, TradeWindowStatePolicy.parseCurrency(""));
    }
}
