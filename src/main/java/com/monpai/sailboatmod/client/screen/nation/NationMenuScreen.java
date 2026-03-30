package com.monpai.sailboatmod.client.screen.nation;

import com.monpai.sailboatmod.client.texture.NationFlagUploadClient;
import com.monpai.sailboatmod.nation.service.NationFlagStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class NationMenuScreen extends Screen {
    private EditBox flagPathInput;
    private Component statusLine = Component.empty();

    public NationMenuScreen() {
        super(Component.translatable("screen.sailboatmod.nation.title"));
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = this.height / 2 - 82;

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.info"), button -> runCommand("nation info"))
                .bounds(centerX - 100, top + 18, 96, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.war"), button -> runCommand("nation war info"))
                .bounds(centerX + 4, top + 18, 96, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.claim"), button -> runCommand("nation claim"))
                .bounds(centerX - 100, top + 42, 96, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.unclaim"), button -> runCommand("nation unclaim"))
                .bounds(centerX + 4, top + 42, 96, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.flag_info"), button -> runCommand("nation flag info"))
                .bounds(centerX - 100, top + 66, 96, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.claimperm_info"), button -> runCommand("nation claimperm info"))
                .bounds(centerX + 4, top + 66, 96, 20)
                .build());

        this.flagPathInput = new EditBox(this.font, centerX - 100, top + 104, 200, 20, Component.translatable("screen.sailboatmod.nation.flag_path"));
        this.flagPathInput.setMaxLength(260);
        this.addRenderableWidget(this.flagPathInput);
        this.setInitialFocus(this.flagPathInput);

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.nation.upload"), button -> submitUpload())
                .bounds(centerX - 100, top + 128, 96, 20)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_name.cancel"), button -> onClose())
                .bounds(centerX + 4, top + 128, 96, 20)
                .build());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.flagPathInput != null) {
            this.flagPathInput.tick();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == 257 || keyCode == 335) && this.flagPathInput != null && this.flagPathInput.isFocused()) {
            submitUpload();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int top = this.height / 2 - 82;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, top, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.translatable("screen.sailboatmod.nation.desc"), centerX, top + 10, 0xA8E6FF);
        guiGraphics.drawString(this.font, Component.translatable("screen.sailboatmod.nation.upload_hint", NationFlagStorage.maxWidth(), NationFlagStorage.maxHeight(), NationFlagStorage.maxBytes()), centerX - 100, top + 92, 0xE0E0E0);
        if (!this.statusLine.getString().isBlank()) {
            guiGraphics.drawCenteredString(this.font, this.statusLine, centerX, top + 154, 0xFFF3B0);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void runCommand(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.connection == null) {
            this.statusLine = Component.translatable("screen.sailboatmod.nation.command_unavailable");
            return;
        }
        minecraft.player.connection.sendCommand(command);
        this.statusLine = Component.translatable("screen.sailboatmod.nation.command_sent", "/" + command);
    }

    private void submitUpload() {
        String path = this.flagPathInput == null ? "" : this.flagPathInput.getValue();
        this.statusLine = NationFlagUploadClient.uploadFromPath(path);
    }
}
