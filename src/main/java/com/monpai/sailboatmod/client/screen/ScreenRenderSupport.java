package com.monpai.sailboatmod.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;

/** Renders widgets after a custom screen's panels without repainting its background. */
public final class ScreenRenderSupport {
    private ScreenRenderSupport() {
    }

    public static void renderWidgets(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        for (Renderable renderable : screen.renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }
}
