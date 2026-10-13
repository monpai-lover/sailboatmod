package com.monpai.sailboatmod.client.ui.territory;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws a nation or town flag texture centred in its box, scaled down to fit and never stretched; falls back to the
 * primary colour when there is no texture.
 */
public final class FlagPreview extends UIElement {
    private ResourceLocation texture;
    private int textureWidth = 64;
    private int textureHeight = 32;
    private int fallbackColor = 0xFF404040;

    public FlagPreview() {
        addClass("sm-chart");
    }

    public FlagPreview set(ResourceLocation texture, int width, int height, int fallbackRgb) {
        this.texture = texture;
        this.textureWidth = width <= 0 ? 64 : width;
        this.textureHeight = height <= 0 ? 32 : height;
        this.fallbackColor = 0xFF000000 | (fallbackRgb & 0xFFFFFF);
        return this;
    }

    @Override
    public void drawContents(GUIContext context) {
        super.drawContents(context);
        GuiGraphics g = context.graphics;
        int x = Math.round(getContentX());
        int y = Math.round(getContentY());
        int w = Math.round(getContentWidth());
        int h = Math.round(getContentHeight());
        if (w < 2 || h < 2) {
            return;
        }
        if (texture == null) {
            g.fill(x, y, x + w, y + h, fallbackColor);
            return;
        }
        float scale = Math.min(1f, Math.min(w / (float) textureWidth, h / (float) textureHeight));
        int dw = Math.max(1, Math.round(textureWidth * scale));
        int dh = Math.max(1, Math.round(textureHeight * scale));
        int dx = x + (w - dw) / 2;
        int dy = y + (h - dh) / 2;
        g.blit(texture, dx, dy, dw, dh, 0, 0, textureWidth, textureHeight, textureWidth, textureHeight);
    }
}
