package com.monpai.sailboatmod.client.screen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimsMapVisibilityTest {
    @Test
    void mapToolsOnlyShowOnClaimsMapView() {
        assertTrue(ClaimsMapVisibility.showMapTools(true, 0));
        assertFalse(ClaimsMapVisibility.showMapTools(true, 1));
        assertFalse(ClaimsMapVisibility.showMapTools(false, 0));
        assertFalse(ClaimsMapVisibility.showMapTools(false, 1));
    }

    @Test
    void mapInteractionsOnlyWorkOnClaimsMapView() {
        assertTrue(ClaimsMapVisibility.allowMapInteraction(true, 0));
        assertFalse(ClaimsMapVisibility.allowMapInteraction(true, 1));
        assertFalse(ClaimsMapVisibility.allowMapInteraction(false, 0));
        assertFalse(ClaimsMapVisibility.allowMapInteraction(false, 1));
    }
}
