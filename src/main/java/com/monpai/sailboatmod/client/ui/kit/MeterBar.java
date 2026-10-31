package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.gui.GuiGraphics;

/** Horizontal bar showing a fraction (tax rates, satisfaction, employment). */
public final class MeterBar extends UIElement {
    private float ratio;
    private int color = 0xFF7AA2C6;

    public MeterBar() {
        addClass("sm-chart");
        layout(l -> l.height(8).flexShrink(0));
    }

    public MeterBar set(float value, int argb) {
        this.ratio = Math.max(0f, Math.min(1f, value));
        this.color = argb;
        return this;
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        super.drawBackgroundAdditional(context);
        GuiGraphics g = context.graphics;
        g.flush();
        int x = Math.round(getContentX());
        int y = Math.round(getContentY());
        int w = Math.round(getContentWidth());
        int h = Math.round(getContentHeight());
        int fill = Math.round(w * ratio);
        if (fill > 0 && h > 0) {
            g.fill(x, y, x + fill, y + h, color);
            g.flush();
        }
    }
}
