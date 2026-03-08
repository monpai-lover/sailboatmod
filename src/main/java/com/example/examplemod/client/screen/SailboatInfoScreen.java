package com.example.examplemod.client.screen;

import com.example.examplemod.entity.SailboatEntity;
import com.example.examplemod.network.ModNetwork;
import com.example.examplemod.network.packet.OpenSailboatStoragePacket;
import com.example.examplemod.network.packet.RenameSailboatPacket;
import com.example.examplemod.network.packet.SelectSailboatSeatPacket;
import com.example.examplemod.network.packet.SetHandlingPresetPacket;
import com.example.examplemod.network.packet.SetSailboatRentalPricePacket;
import com.example.examplemod.network.packet.ToggleSailPacket;
import com.example.examplemod.network.packet.ControlAutopilotPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

public class SailboatInfoScreen extends Screen {
    private final SailboatEntity sailboat;
    private EditBox nameInput;
    private EditBox rentalPriceInput;
    private Button handlingButton;
    private Button sailButton;
    private Button routePrevButton;
    private Button routeNextButton;

    public SailboatInfoScreen(SailboatEntity sailboat) {
        super(Component.translatable("screen.sailboatmod.info"));
        this.sailboat = sailboat;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int top = this.height / 2 - 90;

        this.nameInput = new EditBox(this.font, centerX - 100, top + 20, 132, 20, Component.translatable("screen.sailboatmod.name"));
        this.nameInput.setMaxLength(64);
        this.nameInput.setValue(sailboat.hasCustomName() ? sailboat.getCustomName().getString() : "");
        this.addRenderableWidget(this.nameInput);
        this.rentalPriceInput = new EditBox(this.font, centerX + 36, top + 20, 64, 20, Component.translatable("screen.sailboatmod.rent_input"));
        this.rentalPriceInput.setMaxLength(7);
        this.rentalPriceInput.setValue(Integer.toString(sailboat.getRentalPrice()));
        this.addRenderableWidget(this.rentalPriceInput);

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.rename"), b -> {
            ModNetwork.CHANNEL.sendToServer(new RenameSailboatPacket(sailboat.getId(), nameInput.getValue()));
            ModNetwork.CHANNEL.sendToServer(new SetSailboatRentalPricePacket(sailboat.getId(), parseRentalPriceInput()));
        }).bounds(centerX - 100, top + 46, 96, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.open_storage"), b -> {
            ModNetwork.CHANNEL.sendToServer(new OpenSailboatStoragePacket());
        }).bounds(centerX + 4, top + 46, 96, 20).build());

        this.sailButton = this.addRenderableWidget(Button.builder(getSailButtonText(), b -> {
            ModNetwork.CHANNEL.sendToServer(new ToggleSailPacket());
        }).bounds(centerX - 100, top + 68, 96, 20).build());

        this.handlingButton = this.addRenderableWidget(Button.builder(getHandlingButtonText(), b -> {
            int nextId = sailboat.getHandlingPreset().next().id;
            ModNetwork.CHANNEL.sendToServer(new SetHandlingPresetPacket(nextId));
        }).bounds(centerX + 4, top + 68, 96, 20).build());

        this.routePrevButton = this.addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(sailboat.getId(), SailboatEntity.AutopilotControlAction.PREV_ROUTE));
        }).bounds(centerX - 100, top + 92, 20, 20).build());
        this.routeNextButton = this.addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(sailboat.getId(), SailboatEntity.AutopilotControlAction.NEXT_ROUTE));
        }).bounds(centerX + 80, top + 92, 20, 20).build());

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_start"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(sailboat.getId(), SailboatEntity.AutopilotControlAction.START));
        }).bounds(centerX - 100, top + 114, 48, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_pause"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(sailboat.getId(), SailboatEntity.AutopilotControlAction.PAUSE));
        }).bounds(centerX - 50, top + 114, 48, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_resume"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(sailboat.getId(), SailboatEntity.AutopilotControlAction.RESUME));
        }).bounds(centerX + 0, top + 114, 48, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_stop"), b -> {
            ModNetwork.CHANNEL.sendToServer(new ControlAutopilotPacket(sailboat.getId(), SailboatEntity.AutopilotControlAction.STOP));
        }).bounds(centerX + 50, top + 114, 50, 20).build());

        for (int seat = 0; seat < 5; seat++) {
            int buttonX = centerX - 100 + (seat % 3) * 68;
            int buttonY = top + 138 + (seat / 3) * 22;
            int seatId = seat;
            this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.seat", seat + 1), b -> {
                ModNetwork.CHANNEL.sendToServer(new SelectSailboatSeatPacket(sailboat.getId(), seatId));
            }).bounds(buttonX, buttonY, 64, 20).build());
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.nameInput != null) {
            this.nameInput.tick();
        }
        if (this.rentalPriceInput != null) {
            this.rentalPriceInput.tick();
            if (!this.rentalPriceInput.isFocused()) {
                this.rentalPriceInput.setValue(Integer.toString(sailboat.getRentalPrice()));
            }
        }
        if (this.handlingButton != null) {
            this.handlingButton.setMessage(getHandlingButtonText());
        }
        if (this.sailButton != null) {
            this.sailButton.setMessage(getSailButtonText());
        }
        if (this.routePrevButton != null) {
            this.routePrevButton.active = sailboat.getRouteCount() > 0;
        }
        if (this.routeNextButton != null) {
            this.routeNextButton.active = sailboat.getRouteCount() > 0;
        }
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().player.getVehicle() != sailboat) {
            this.onClose();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;
        int top = this.height / 2 - 90;
        guiGraphics.drawCenteredString(this.font, this.title, centerX, top, 0xFFFFFF);

        Component nameText = sailboat.hasCustomName() ? sailboat.getCustomName() : Component.translatable("screen.sailboatmod.unnamed");
        guiGraphics.drawString(this.font, Component.translatable("screen.sailboatmod.current_name", nameText), centerX - 100, top + 2, 0xE0E0E0);
        guiGraphics.drawString(this.font, Component.translatable("screen.sailboatmod.rent_price", sailboat.getRentalPrice()), centerX + 36, top + 2, 0xA8E6FF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.sailboatmod.route_current", sailboat.getSelectedRouteIndex() + 1, Math.max(sailboat.getRouteCount(), 1), sailboat.getSelectedRouteName()),
                centerX, top + 98, 0xB0F0FF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.sailboatmod.route_state", getAutopilotStateText()),
                centerX, top + 86, 0xFFF3B0);

        String captainName = "-";
        for (Entity passenger : sailboat.getPassengers()) {
            if (sailboat.getSeatFor(passenger) == 0) {
                captainName = passenger.getName().getString();
                break;
            }
        }
        String ownerUuid = sailboat.getOwnerUuid() == null || sailboat.getOwnerUuid().isBlank() ? "-" : sailboat.getOwnerUuid();
        guiGraphics.drawString(this.font, Component.translatable("screen.sailboatmod.captain", captainName), centerX - 100, top + 172, 0xFFD27F);
        guiGraphics.drawString(this.font, Component.translatable("screen.sailboatmod.owner_full", sailboat.getOwnerName(), ownerUuid), centerX - 100, top + 184, 0xA8E6FF);
        guiGraphics.drawString(this.font, Component.translatable("screen.sailboatmod.storage_info", 27), centerX - 100, top + 196, 0xA8E6FF);

        int rowY = top + 208;
        guiGraphics.drawString(this.font, Component.translatable("screen.sailboatmod.passengers"), centerX - 100, rowY, 0xFFFFFF);
        rowY += 12;

        if (sailboat.getPassengers().isEmpty()) {
            guiGraphics.drawString(this.font, Component.literal("-"), centerX - 100, rowY, 0xAAAAAA);
        } else {
            for (Entity passenger : sailboat.getPassengers()) {
                int seat = sailboat.getSeatFor(passenger) + 1;
                Component line = Component.literal("[" + seat + "] " + passenger.getName().getString());
                if (seat == 1) {
                    line = line.copy().withStyle(ChatFormatting.GOLD);
                }
                guiGraphics.drawString(this.font, line, centerX - 100, rowY, 0xFFFFFF);
                rowY += 12;
                if (rowY > this.height - 12) {
                    break;
                }
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component getHandlingButtonText() {
        return Component.translatable("screen.sailboatmod.handling_mode", Component.translatable("screen.sailboatmod.preset." + sailboat.getHandlingPreset().name().toLowerCase()));
    }

    private Component getSailButtonText() {
        return sailboat.isSailDeployed()
                ? Component.translatable("screen.sailboatmod.sail_stow")
                : Component.translatable("screen.sailboatmod.sail_deploy");
    }

    private Component getAutopilotStateText() {
        if (!sailboat.isAutopilotActive()) {
            return Component.translatable("screen.sailboatmod.route_status.stopped");
        }
        if (sailboat.isAutopilotPaused()) {
            return Component.translatable("screen.sailboatmod.route_status.paused");
        }
        return Component.translatable("screen.sailboatmod.route_status.running");
    }

    private int parseRentalPriceInput() {
        if (rentalPriceInput == null) {
            return sailboat.getRentalPrice();
        }
        String raw = rentalPriceInput.getValue();
        if (raw == null || raw.isBlank()) {
            return sailboat.getRentalPrice();
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(SailboatEntity.MIN_RENTAL_PRICE, Math.min(SailboatEntity.MAX_RENTAL_PRICE, parsed));
        } catch (NumberFormatException ignored) {
            return sailboat.getRentalPrice();
        }
    }
}
