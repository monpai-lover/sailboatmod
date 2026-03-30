package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.FinalizeRouteNamePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;

public class RouteBookNameScreen extends Screen {
    private final InteractionHand hand;
    private final String suggestedName;
    private EditBox nameBox;

    public RouteBookNameScreen(InteractionHand hand, String suggestedName) {
        super(Component.translatable("screen.sailboatmod.route_name.title"));
        this.hand = hand;
        this.suggestedName = suggestedName == null ? "" : suggestedName;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int top = this.height / 2 - 40;
        this.nameBox = new EditBox(this.font, cx - 100, top + 20, 200, 20, Component.translatable("screen.sailboatmod.route_name.input"));
        this.nameBox.setValue(suggestedName);
        this.nameBox.setMaxLength(64);
        this.addRenderableWidget(this.nameBox);
        this.setInitialFocus(this.nameBox);

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_name.confirm"), b -> submit())
                .bounds(cx - 100, top + 46, 96, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_name.cancel"), b -> onClose())
                .bounds(cx + 4, top + 46, 96, 20).build());
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        int cx = this.width / 2;
        int top = this.height / 2 - 40;
        guiGraphics.drawCenteredString(this.font, this.title, cx, top, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.translatable("screen.sailboatmod.route_name.desc"), cx, top + 10, 0xA8E6FF);
    }

    private void submit() {
        String name = this.nameBox == null ? "" : this.nameBox.getValue();
        ModNetwork.CHANNEL.sendToServer(new FinalizeRouteNamePacket(hand, name));
        onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
