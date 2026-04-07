package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.menu.WarehouseMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class WarehouseScreen extends AbstractContainerScreen<WarehouseMenu> {
    public WarehouseScreen(WarehouseMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 222;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xDD4B3420);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xEE2C1F14);
        guiGraphics.fill(leftPos + 7, topPos + 17, leftPos + imageWidth - 7, topPos + 17 + 108, 0xAA1A1713);
        guiGraphics.fill(leftPos + 7, topPos + 139, leftPos + imageWidth - 7, topPos + imageHeight - 7, 0xAA1A1713);
        for (int i = 0; i < menu.slots.size(); i++) {
            int x = leftPos + menu.slots.get(i).x;
            int y = topPos + menu.slots.get(i).y;
            guiGraphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8D7350);
            guiGraphics.fill(x, y, x + 16, y + 16, 0xFF20160E);
            guiGraphics.fill(x + 1, y + 1, x + 15, y + 15, 0xFF352419);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
    }
}
