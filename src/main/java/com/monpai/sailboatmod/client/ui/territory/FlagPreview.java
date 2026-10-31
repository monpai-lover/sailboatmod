package com.monpai.sailboatmod.client.ui.territory;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/** A nation/town flag drawn inside LDLib2's content/scissor lifecycle. */
public final class FlagPreview extends UIElement {
    private ResourceLocation texture;
    private int textureWidth = 64;
    private int textureHeight = 32;
    private int fallbackColor = 0xFF404040;

    public FlagPreview() {
        addClass("sm-chart");
        layout(l -> l.paddingAll(1));
    }

    public FlagPreview set(ResourceLocation texture, int width, int height, int fallbackRgb) {
        this.texture = texture;
        this.textureWidth = width <= 0 ? 64 : width;
        this.textureHeight = height <= 0 ? 32 : height;
        this.fallbackColor = 0xFF000000 | (fallbackRgb & 0xFFFFFF);
        return this;
    }

    @Override
    public void drawBackgroundAdditional(GUIContext context) {
        super.drawBackgroundAdditional(context);
        GuiGraphics g = context.graphics;
        int x = Math.round(getContentX());
        int y = Math.round(getContentY());
        int w = Math.round(getContentWidth());
        int h = Math.round(getContentHeight());
        var bounds = FlagPreviewLayout.fit(x, y, w, h, textureWidth, textureHeight);
        if (bounds == null) {
            return;
        }
        if (texture == null) {
            g.fill(x, y, x + w, y + h, fallbackColor);
            return;
        }

        // LDLib2's chart background is batched; the 1.21.1 texture blit is immediate.
        // Submit the background first, otherwise it can cover the uploaded flag later.
        // Use the additional-background hook so clipping/visibility remain owned by UIElement.
        g.flush();
        float[] previousColor = RenderSystem.getShaderColor().clone();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            g.blit(texture, bounds.x(), bounds.y(), bounds.width(), bounds.height(),
                    0, 0, textureWidth, textureHeight, textureWidth, textureHeight);
        } finally {
            g.flush();
            RenderSystem.setShaderColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
        }
    }
}
