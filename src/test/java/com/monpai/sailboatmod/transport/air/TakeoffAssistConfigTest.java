package com.monpai.sailboatmod.transport.air;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TakeoffAssistConfigTest {
    @Test void oldConfigurationsDefaultToDisabledWithUsableSuggestedStrength() {
        assertFalse(TakeoffAssistConfig.DEFAULT.enabled());
        assertEquals(12, TakeoffAssistConfig.DEFAULT.acceleration());
    }

    @Test void ownerStrengthIsFiniteAndBounded() {
        assertEquals(0, new TakeoffAssistConfig(true, -2).acceleration());
        assertEquals(20, new TakeoffAssistConfig(true, 30).acceleration());
        for (double value : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY})
            assertEquals(0, new TakeoffAssistConfig(true, value).acceleration());
        assertEquals(7.5, new TakeoffAssistConfig(true, 7.5).acceleration());
    }
}
