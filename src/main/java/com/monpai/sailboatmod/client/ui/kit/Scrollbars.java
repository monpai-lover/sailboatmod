package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Scroller;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventDispatcher;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.PropertyRegistry;
import com.lowdragmc.lowdraglib2.gui.ui.style.StyleOrigin;
import com.lowdragmc.lowdraglib2.gui.ui.style.animation.StyleAnimation;
import com.lowdragmc.lowdraglib2.syncdata.ISubscription;
import com.monpai.sailboatmod.client.ui.state.ScrollbarActivity;
import dev.vfyjxf.taffy.style.TaffyPosition;

/** Fades scrollbar visuals without changing layout or disabling the rail's hover hit area. */
public final class Scrollbars {
    private Scrollbars() { }

    public static void install(ScrollerView view) {
        if (view.hasClass("sm-scroll-fade")) return;
        view.addClass("sm-scroll-fade");
        // Reserve the vertical gutter even while AUTO hides its visuals. Otherwise showing the rail
        // changes line wrapping, which can repeatedly toggle overflow in a narrow list.
        view.viewPort.layout(layout -> layout.paddingRight(11));
        view.verticalScroller.layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE)
                .right(0).top(0).heightPercent(100).width(8).marginLeft(0));
        new Rail(view, view.verticalScroller);
        new Rail(view, view.horizontalScroller);
        float[] previous = {Float.NaN, Float.NaN, Float.NaN, Float.NaN};
        view.addEventListener(UIEvents.TICK, event -> {
            if (view.viewPort.getContentWidth() <= 0 || view.viewPort.getContentHeight() <= 0) return;
            float[] current = {view.getContainerWidth(), view.getContainerHeight(),
                    view.viewPort.getContentWidth(), view.viewPort.getContentHeight()};
            boolean changed = false;
            for (int i = 0; i < current.length; i++) changed |= Float.compare(previous[i], current[i]) != 0;
            if (!changed) return;
            System.arraycopy(current, 0, previous, 0, current.length);
            // LDLib2 can notify the container before its last child settles. Notify the final measured
            // extent as well, so shrinking/replaced rows cannot leave stale visibility or thumb size.
            UIEvent geometry = UIEvent.create(UIEvents.LAYOUT_CHANGED);
            geometry.target = view.viewContainer;
            UIEventDispatcher.dispatchDirectEvent(geometry, false);
        });
    }

    private static long now() {
        return System.nanoTime() / 1_000_000;
    }

    private static final class Rail {
        private final Scroller scroller;
        private final ScrollbarActivity activity = new ScrollbarActivity();
        private ISubscription animation;
        private boolean hovered;
        private boolean revealed;
        private float previousValue;

        Rail(ScrollerView view, Scroller scroller) {
            this.scroller = scroller;
            previousValue = scroller.getValue();
            opacity(0);
            view.addEventListener(UIEvents.MOUSE_WHEEL, event -> {
                if (event.deltaX != 0 || event.deltaY != 0) {
                    activity.pulse(now());
                    update();
                }
            }, true);
            scroller.addEventListener(UIEvents.MOUSE_ENTER, event -> {
                hovered = true;
                update();
            }, true);
            // Moving from the rail to its thumb also sends a leave; only leaving its rectangle clears hover.
            scroller.addEventListener(UIEvents.MOUSE_LEAVE, event -> {
                if (!scroller.isMouseOver(event.x, event.y)) hovered = false;
                update();
            }, true);
            scroller.addEventListener(UIEvents.MOUSE_DOWN, event -> {
                activity.pulse(now());
                update();
            }, true);
            view.addEventListener(UIEvents.TICK, event -> update());
            view.addEventListener(UIEvents.MUI_CHANGED, event -> reset());
            view.addEventListener(UIEvents.REMOVED, event -> reset());
        }

        private void update() {
            if (!scroller.isDisplayed()) {
                reset();
                return;
            }
            long time = now();
            float value = scroller.getValue();
            if (Float.compare(value, previousValue) != 0) activity.pulse(time);
            previousValue = value;
            boolean target = activity.visible(time, hovered || scroller.isDragging());
            if (target == revealed) return;
            revealed = target;
            if (animation != null) animation.unsubscribe();
            animation = StyleAnimation.of(scroller.getModularUI())
                    .select(scroller.headButton).select(scroller.tailButton).select(scroller.scrollContainer)
                    .duration(target ? .12f : .25f).style(PropertyRegistry.OPACITY, target ? 1f : 0f).start();
        }

        private void reset() {
            if (animation != null) {
                animation.unsubscribe();
                animation = null;
            }
            activity.reset();
            hovered = false;
            revealed = false;
            previousValue = scroller.getValue();
            opacity(0);
        }

        private void opacity(float value) {
            // The root keeps opacity 1: a fully faded scrollbar must still reveal itself on hover.
            for (var visual : new UIElement[] {
                    scroller.headButton, scroller.tailButton, scroller.scrollContainer}) {
                // Cancelling playback alone leaves its animation candidate above inline styles.
                visual.getStyleBag().removeCandidates(PropertyRegistry.OPACITY, slot ->
                        slot.origin() == StyleOrigin.ANIMATION && slot.specificity() == 999 && slot.sourceOrder() == 0);
                visual.style(style -> style.opacity(value));
            }
        }
    }
}
