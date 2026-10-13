package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.network.chat.Component;

/**
 * Short message floating at the bottom of a window: results of the player's actions, server notices, "no reply".
 * Positive messages use the theme's toast colours, failures are outlined in red. Hidden again after three seconds.
 */
public final class Toast {
    private static final long SHOW_TICKS = 60;

    private UIElement element;
    private Label text;
    private long hideAtTick = -1;

    /** Elements are created on first use, so screens can be constructed without a running client (unit tests). */
    private void build() {
        if (element != null) {
            return;
        }
        element = Ui.row("sm-toast");
        element.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).bottom(Ui.GAP_L).left(Ui.GAP_L).height(14)
                .paddingHorizontal(Ui.GAP_M).maxWidth(320));
        element.style(s -> s.zIndex(60));
        text = Ui.text("", "sm-on-toast");
        element.addChild(text);
        element.setDisplay(false);
    }

    /** Add this to the window (it positions itself absolutely). */
    public UIElement element() {
        build();
        return element;
    }

    public void show(Component message, boolean positive, long nowTick) {
        build();
        text.setValue(message);
        Ui.setClass(element, "negative", !positive);
        element.setDisplay(true);
        hideAtTick = nowTick + SHOW_TICKS;
    }

    public void tick(long nowTick) {
        if (element != null && hideAtTick >= 0 && nowTick >= hideAtTick) {
            element.setDisplay(false);
            hideAtTick = -1;
        }
    }
}
