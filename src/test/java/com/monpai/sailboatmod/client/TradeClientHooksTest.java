package com.monpai.sailboatmod.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeClientHooksTest {
    @Test
    void tradeWindowReplacesScreenWhenCurrentScreenIsNotBOScreen() {
        assertEquals(
                TradeClientHooks.OpenMode.REPLACE_SCREEN,
                TradeClientHooks.openModeForCurrentScreen(true)
        );
    }

    @Test
    void tradeWindowReplacesScreenWhenNoScreenIsOpen() {
        assertEquals(
                TradeClientHooks.OpenMode.REPLACE_SCREEN,
                TradeClientHooks.openModeForCurrentScreen(false)
        );
    }
}
