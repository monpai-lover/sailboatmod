package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.ControlAutopilotPacket;
import com.monpai.sailboatmod.network.packet.OpenSailboatStoragePacket;
import com.monpai.sailboatmod.network.packet.RenameSailboatPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class CarriageInfoScreen extends Screen {
    private static final int PANEL_W = 236;
    private static final int PANEL_H = 176;

    private final CarriageEntity carriage;
    private EditBox nameInput;
    private Button routePrevButton;
    private Button routeNextButton;

    public CarriageInfoScreen(CarriageEntity carriage) {
        super(carriage.getInfoScreenTitle());
        this.carriage = carriage;
    }

    @Override
    protected void init() {
        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;

        this.nameInput = new EditBox(this.font, left + 18, top + 34, PANEL_W - 36, 18, Component.translatable("screen.sailboatmod.name"));
        this.nameInput.setMaxLength(64);
        this.nameInput.setValue(carriage.hasCustomName() ? carriage.getCustomName().getString() : "");
        this.addRenderableWidget(this.nameInput);

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.save"), b -> {
            ModNetwork.CHANNEL.sendToServer(new RenameSailboatPacket(carriage.getId(), this.nameInput.getValue()));
        }).bounds(left + 18, top + 58, 92, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.open_storage"), b -> {
            ModNetwork.CHANNEL.sendToServer(new OpenSailboatStoragePacket());
        }).bounds(left + PANEL_W - 110, top + 58, 92, 20).build());

        this.routePrevButton = this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(carriage.getId(), SailboatEntity.AutopilotControlAction.PREV_ROUTE));
        }).bounds(left + 18, top + 110, 20, 20).build());
        this.routeNextButton = this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(carriage.getId(), SailboatEntity.AutopilotControlAction.NEXT_ROUTE));
        }).bounds(left + PANEL_W - 38, top + 110, 20, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_start"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(carriage.getId(), SailboatEntity.AutopilotControlAction.START));
        }).bounds(left + 18, top + 136, 46, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_pause"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(carriage.getId(), SailboatEntity.AutopilotControlAction.PAUSE));
        }).bounds(left + 70, top + 136, 46, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_resume"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(carriage.getId(), SailboatEntity.AutopilotControlAction.RESUME));
        }).bounds(left + 122, top + 136, 46, 18).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_stop"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(carriage.getId(), SailboatEntity.AutopilotControlAction.STOP));
        }).bounds(left + 174, top + 136, 44, 18).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.nameInput != null) {
            this.nameInput.tick();
        }
        if (this.routePrevButton != null) {
            this.routePrevButton.active = carriage.getRouteCount() > 0;
        }
        if (this.routeNextButton != null) {
            this.routeNextButton.active = carriage.getRouteCount() > 0;
        }
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().player.getVehicle() != carriage) {
            this.onClose();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int left = (this.width - PANEL_W) / 2;
        int top = (this.height - PANEL_H) / 2;
        guiGraphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xD2181A1F);
        guiGraphics.fill(left + 1, top + 1, left + PANEL_W - 1, top + PANEL_H - 1, 0xEE242A33);
        guiGraphics.fill(left + 12, top + 24, left + PANEL_W - 12, top + 86, 0x66253138);
        guiGraphics.fill(left + 12, top + 96, left + PANEL_W - 12, top + PANEL_H - 14, 0x66253138);

        guiGraphics.drawCenteredString(this.font, this.title, left + PANEL_W / 2, top + 10, 0xFFF0DFC2);
        guiGraphics.drawString(this.font, Component.translatable("screen.sailboatmod.current_name", carriage.getName()).getString(), left + 18, top + 24, 0xFFD7E8F4, false);
        guiGraphics.drawString(this.font, Component.translatable("screen.sailboatmod.owner_name", carriage.getOwnerName()).getString(), left + 18, top + 84, 0xFFB8CCE0, false);
        guiGraphics.drawString(this.font, Component.translatable("screen.sailboatmod.storage_info", carriage.getStorageSlotCount()).getString(), left + 18, top + 96, 0xFFB8CCE0, false);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.sailboatmod.route_state", autopilotState()),
                left + PANEL_W / 2, top + 112, 0xFFEBC776);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.sailboatmod.route_current",
                        carriage.getSelectedRouteIndex() + 1,
                        Math.max(carriage.getRouteCount(), 1),
                        carriage.getSelectedRouteName()),
                left + PANEL_W / 2, top + 124, 0xFFD7E8F4);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component autopilotState() {
        if (!carriage.isAutopilotActive()) {
            return Component.translatable("screen.sailboatmod.route_status.stopped");
        }
        if (carriage.isAutopilotPaused()) {
            return Component.translatable("screen.sailboatmod.route_status.paused");
        }
        return Component.translatable("screen.sailboatmod.route_status.running");
    }
}
