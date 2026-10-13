package com.monpai.sailboatmod.client.ui.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScrollbarActivityTest {
    @Test
    void startsHiddenAndKeepsRecentScrollingVisible() {
        ScrollbarActivity activity = new ScrollbarActivity();
        assertFalse(activity.visible(0, false));
        activity.pulse(100);
        assertTrue(activity.visible(100, false));
        assertTrue(activity.visible(999, false));
        assertFalse(activity.visible(1000, false));
    }

    @Test
    void hoverOrDraggingHoldVisibilityThenRestartIdleDelay() {
        ScrollbarActivity activity = new ScrollbarActivity();
        assertTrue(activity.visible(5000, true));
        assertTrue(activity.visible(12000, true));
        assertTrue(activity.visible(12899, false));
        assertFalse(activity.visible(12900, false));
    }

    @Test
    void repeatedScrollingExtendsDeadlineAndResetClearsRetainedHover() {
        ScrollbarActivity activity = new ScrollbarActivity();
        activity.pulse(0);
        activity.pulse(800);
        assertTrue(activity.visible(1500, false));
        activity.reset();
        assertFalse(activity.visible(1501, false));
    }
}
