package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import net.minecraft.network.chat.Component;

/**
 * A button bound to a server action, created once and updated in place.
 *
 * <p>{@link #sync} shows one of three states without rebuilding the element: idle with its label, "Sending…" while
 * the request waits for a reply (and disabled, so it cannot be sent twice), or "Press again to confirm" for a
 * destructive action that has been pressed once.</p>
 */
public final class ActionButton {
    private final Button button;
    private Component label;
    private String state = "";

    public ActionButton(Component label, Ui.Tone tone, Runnable onClick) {
        this.label = label;
        this.button = Ui.button(label, tone, onClick);
    }

    /**
     * Shares the available width with its siblings in a horizontal action row.
     *
     * <p>A zero basis keeps the allocation independent of the idle, pending and confirmation labels. The full
     * label remains readable on hover when a translation is wider than its share. Buttons in columns use the
     * default automatic width, which stretches to the column; fixed-width side panels can still set an explicit
     * width on {@link #element()}.</p>
     */
    public ActionButton inRow() {
        button.layout(l -> l.widthAuto().flexBasis(0).flexGrow(1).flexShrink(1).minWidth(40));
        button.text.layout(l -> l.widthPercent(100).flexShrink(1).minWidth(0));
        button.text.setOverflowVisible(false);
        button.textStyle(t -> t.adaptiveWidth(false).textWrap(TextWrap.HOVER_ROLL));
        return this;
    }

    public Button element() {
        return button;
    }

    public ActionButton setLabel(Component value) {
        label = value;
        state = "";
        return this;
    }

    public void sync(boolean enabled, boolean pending, boolean armed) {
        String next = pending ? "pending" : armed ? "armed" : "idle";
        if (!next.equals(state)) {
            state = next;
            button.setText(pending
                    ? Component.translatable("screen.sailboatmod.ui.sending")
                    : armed ? Component.translatable("screen.sailboatmod.ui.confirm_again") : label);
            toggle("sm-pending", pending);
            toggle("sm-armed", armed && !pending);
        }
        button.setActive(enabled && !pending);
    }

    private void toggle(String cssClass, boolean on) {
        if (on && !button.hasClass(cssClass)) {
            button.addClass(cssClass);
        } else if (!on && button.hasClass(cssClass)) {
            button.removeClass(cssClass);
        }
    }
}
