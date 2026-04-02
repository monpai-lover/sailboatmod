package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.nation.service.StructureConstructionManager.StructureType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class StructureSelectionScreen extends Screen {
    private static final int SCREEN_W = 240;
    private static final int SCREEN_H = 200;
    private static final int ROW_H = 24;

    private final ItemStack constructorStack;
    private int selectedIndex;

    public StructureSelectionScreen(ItemStack constructorStack) {
        super(Component.translatable("screen.sailboatmod.structure_selection"));
        this.constructorStack = constructorStack;
        this.selectedIndex = com.monpai.sailboatmod.item.BankConstructorItem.getSelectedIndex(constructorStack);
    }

    @Override
    protected void init() {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            com.monpai.sailboatmod.item.BankConstructorItem.setSelectedIndex(constructorStack, selectedIndex);
            onClose();
        }).bounds(left + SCREEN_W - 60, top + SCREEN_H - 28, 50, 20).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;

        g.fill(left, top, left + SCREEN_W, top + SCREEN_H, 0xCC101820);
        g.fill(left + 1, top + 1, left + SCREEN_W - 1, top + SCREEN_H - 1, 0xCC182632);

        g.drawString(this.font, this.title, left + 12, top + 10, 0xFFE7C977);

        int listY = top + 28;
        for (int i = 0; i < StructureType.ALL.size(); i++) {
            StructureType type = StructureType.ALL.get(i);
            int rowY = listY + i * ROW_H;

            if (i == selectedIndex) {
                g.fill(left + 12, rowY, left + SCREEN_W - 12, rowY + ROW_H, 0x44FFFFFF);
            }

            g.drawString(this.font, Component.translatable(type.translationKey()), left + 16, rowY + 8, 0xFFDCEEFF);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        int listY = top + 28;

        if (mouseX >= left + 12 && mouseX < left + SCREEN_W - 12) {
            for (int i = 0; i < StructureType.ALL.size(); i++) {
                int rowY = listY + i * ROW_H;
                if (mouseY >= rowY && mouseY < rowY + ROW_H) {
                    selectedIndex = i;
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
