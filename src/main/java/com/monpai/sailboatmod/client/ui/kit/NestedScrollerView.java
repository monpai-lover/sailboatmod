package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;

import java.util.List;

/** Lets the enclosing page take the wheel when this list cannot move farther in that direction. */
final class NestedScrollerView extends ScrollerView {
    private float beforeX;
    private float beforeY;

    NestedScrollerView() {
        // LDLib2 stops every wheel event at the list root, even for an empty or fully scrolled list.
        for (var listener : List.copyOf(getBubbleListeners(UIEvents.MOUSE_WHEEL))) {
            removeEventListener(UIEvents.MOUSE_WHEEL, listener);
        }
        addEventListener(UIEvents.MOUSE_WHEEL, this::beforeWheel, true);
        addEventListener(UIEvents.MOUSE_WHEEL, this::afterWheel);
    }

    private void beforeWheel(UIEvent event) {
        beforeX = horizontalScroller.getNormalizedValue();
        beforeY = verticalScroller.getNormalizedValue();
    }

    private void afterWheel(UIEvent event) {
        // Capture/bubble also covers a wheel over the scrollbar itself, whose path skips viewPort.
        if (moved(beforeX, horizontalScroller.getNormalizedValue(), getContainerWidth() - viewPort.getContentWidth())
                || moved(beforeY, verticalScroller.getNormalizedValue(), getContainerHeight() - viewPort.getContentHeight())) {
            event.stopPropagation();
        }
    }

    private static boolean moved(float before, float after, float range) {
        return range > 0 && Float.isFinite(before) && Float.isFinite(after) && before != after;
    }

    @Override
    protected float horizontalClamp(float value) {
        return getContainerWidth() <= viewPort.getContentWidth() ? 0 : super.horizontalClamp(value);
    }

    @Override
    protected float verticalClamp(float value) {
        return getContainerHeight() <= viewPort.getContentHeight() ? 0 : super.verticalClamp(value);
    }
}
