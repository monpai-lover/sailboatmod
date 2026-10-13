package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.vehicle.AutopilotPanel;
import com.monpai.sailboatmod.entity.SailboatEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenSailboatStoragePacket;
import com.monpai.sailboatmod.network.packet.RenameSailboatPacket;
import com.monpai.sailboatmod.network.packet.SelectSailboatSeatPacket;
import com.monpai.sailboatmod.network.packet.SetHandlingPresetPacket;
import com.monpai.sailboatmod.network.packet.SetSailboatRentalPricePacket;
import com.monpai.sailboatmod.network.packet.SetUnloadOnArrivalPacket;
import com.monpai.sailboatmod.network.packet.ToggleSailPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Sailboat panel opened while riding: name and rent, storage, sail and handling, autopilot, seats.
 *
 * <p>Every control sends the same packet as before (rename and rent are saved together). The panel closes when the
 * player leaves the boat. Values shown come from the boat's synced data and refresh every tick, except a field the
 * player is typing in.</p>
 */
public class SailboatInfoScreen extends ThemedScreen {
    private static final int SEATS = 5;

    private final SailboatEntity sailboat;
    private TextField nameInput;
    private TextField rentalPriceInput;
    private final Label owner = Ui.text("", "sm-muted");
    private final Label storage = Ui.text("", "sm-muted");
    private final Label rentNow = Ui.text("", "sm-muted");
    private Button sailButton;
    private Button handlingButton;
    private Button unloadButton;
    private AutopilotPanel autopilot;
    private final Button[] seatButtons = new Button[SEATS];

    public SailboatInfoScreen(SailboatEntity sailboat) {
        super(sailboat.getInfoScreenTitle());
        this.sailboat = sailboat;
    }

    @Override
    protected float windowWidth() {
        return 260;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();

        // identity: name, rent, storage
        UIElement identity = Ui.column("sm-pane");
        identity.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        nameInput = Ui.field(sailboat.hasCustomName() ? sailboat.getCustomName().getString() : "",
                Component.translatable("screen.sailboatmod.name"), value -> { });
        nameInput.layout(l -> l.flexGrow(1).minWidth(0));
        rentalPriceInput = Ui.signedField(formatRentalPriceInput(), Component.translatable("screen.sailboatmod.rent_input"),
                SailboatEntity.MIN_RENTAL_PRICE, SailboatEntity.MAX_RENTAL_PRICE, value -> { });
        rentalPriceInput.layout(l -> l.width(60));
        rentalPriceInput.style(s -> s.tooltips(Component.translatable("screen.sailboatmod.ui.rent_hint")));
        UIElement fields = Ui.row();
        fields.layout(l -> l.gapAll(Ui.GAP_S));
        fields.addChildren(nameInput, Ui.icon(new ItemStack(Items.EMERALD), 12), rentalPriceInput);
        UIElement identityActions = Ui.row();
        identityActions.layout(l -> l.gapAll(Ui.GAP_S));
        identityActions.addChildren(rentNow, Ui.spacer(),
                Ui.button(Component.translatable("screen.sailboatmod.open_storage"), Ui.Tone.SECONDARY,
                        () -> ModNetwork.CHANNEL.sendToServer(new OpenSailboatStoragePacket())),
                Ui.button(Component.translatable("screen.sailboatmod.save"), Ui.Tone.PRIMARY, this::save));
        UIElement facts = Ui.row();
        facts.layout(l -> l.gapAll(Ui.GAP_M));
        facts.addChildren(owner, storage);
        identity.addChildren(fields, identityActions, facts);

        // sailing
        UIElement sailing = Ui.row();
        sailing.layout(l -> l.gapAll(Ui.GAP_S));
        if (sailboat.showsSailControl()) {
            sailButton = Ui.button(Component.empty(), Ui.Tone.SECONDARY, () -> ModNetwork.CHANNEL.sendToServer(new ToggleSailPacket()));
            sailButton.layout(l -> l.flexGrow(1).flexBasis(0));
            sailing.addChild(sailButton);
        }
        handlingButton = Ui.button(Component.empty(), Ui.Tone.SECONDARY, () -> {
            int nextId = sailboat.getHandlingPreset().next().id;
            ModNetwork.CHANNEL.sendToServer(new SetHandlingPresetPacket(nextId));
        });
        handlingButton.layout(l -> l.flexGrow(1).flexBasis(0));
        unloadButton = Ui.button(Component.empty(), Ui.Tone.SECONDARY, () -> {
            boolean next = !sailboat.isUnloadOnArrival();
            ModNetwork.CHANNEL.sendToServer(new SetUnloadOnArrivalPacket(sailboat.getId(), next));
            sailboat.setAllowNonOrderAutoUnload(next);
            refresh();
        });
        unloadButton.layout(l -> l.flexGrow(1).flexBasis(0));
        sailing.addChildren(handlingButton, unloadButton);

        autopilot = new AutopilotPanel(sailboat, sailboat::getRouteCount, sailboat::getSelectedRouteIndex);

        // seats
        UIElement seats = Ui.wrapRow(Ui.GAP_S);
        seats.layout(l -> l.widthPercent(100));
        for (int seat = 0; seat < SEATS; seat++) {
            int seatId = seat;
            Button button = Ui.button(Component.empty(), Ui.Tone.SECONDARY,
                    () -> ModNetwork.CHANNEL.sendToServer(new SelectSailboatSeatPacket(sailboat.getId(), seatId)));
            button.layout(l -> l.width(78));
            seatButtons[seat] = button;
            seats.addChild(button);
        }

        body.addChildren(identity, sailing, autopilot.element(), Ui.text(Component.translatable("screen.sailboatmod.ui.seats"), "sm-muted"), seats);
        refresh();
        return body;
    }

    private void save() {
        ModNetwork.CHANNEL.sendToServer(new RenameSailboatPacket(sailboat.getId(), nameInput.getValue()));
        ModNetwork.CHANNEL.sendToServer(new SetSailboatRentalPricePacket(sailboat.getId(), parseRentalPriceInput()));
        toast.show(Component.translatable("screen.sailboatmod.ui.saved"), true, ticks());
    }

    @Override
    protected void onUiTick() {
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().player.getVehicle() != sailboat) {
            onClose();
            return;
        }
        refresh();
    }

    private void refresh() {
        if (!rentalPriceInput.isFocused()) {
            rentalPriceInput.setText(formatRentalPriceInput(), false);
        }
        owner.setValue(Component.translatable("screen.sailboatmod.owner_name", sailboat.getOwnerName()));
        storage.setValue(Component.translatable("screen.sailboatmod.storage_info", sailboat.getStorageSlotCount()));
        rentNow.setValue(Component.translatable("screen.sailboatmod.rent_price", sailboat.isAvailableForRent()
                ? Component.literal(Integer.toString(sailboat.getRentalPrice()))
                : Component.translatable("screen.sailboatmod.rent_disabled")));
        if (sailButton != null) {
            sailButton.setText(sailboat.isSailDeployed()
                    ? Component.translatable("screen.sailboatmod.sail_stow")
                    : Component.translatable("screen.sailboatmod.sail_deploy"));
        }
        handlingButton.setText(Component.translatable("screen.sailboatmod.handling_mode",
                Component.translatable("screen.sailboatmod.preset." + sailboat.getHandlingPreset().name().toLowerCase())));
        unloadButton.setText(Component.translatable("screen.sailboatmod.vehicle.unload_on_arrival")
                .append(": ")
                .append(Component.translatable(sailboat.isUnloadOnArrival() ? "screen.sailboatmod.toggle.on" : "screen.sailboatmod.toggle.off")));
        autopilot.refresh();
        Entity me = Minecraft.getInstance().player;
        for (int seat = 0; seat < SEATS; seat++) {
            Entity occupant = getSeatOccupant(seat);
            Component label = occupant == null
                    ? Component.translatable(seat == 0 ? "screen.sailboatmod.seat_captain" : "screen.sailboatmod.seat_empty")
                    : occupant.getName();
            seatButtons[seat].setText(Component.literal((seat + 1) + ": ").append(label));
            Ui.setClass(seatButtons[seat], "sm-primary", occupant != null && occupant == me);
        }
    }

    private int parseRentalPriceInput() {
        String raw = rentalPriceInput == null ? null : rentalPriceInput.getValue();
        if (raw == null || raw.isBlank() || raw.equals("-")) {
            return sailboat.getRentalPrice();
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.max(SailboatEntity.MIN_RENTAL_PRICE, Math.min(SailboatEntity.MAX_RENTAL_PRICE, parsed));
        } catch (NumberFormatException ignored) {
            return sailboat.getRentalPrice();
        }
    }

    private String formatRentalPriceInput() {
        return Integer.toString(sailboat.getRentalPrice());
    }

    private Entity getSeatOccupant(int seat) {
        for (Entity passenger : sailboat.getPassengers()) {
            if (sailboat.getSeatFor(passenger) == seat) {
                return passenger;
            }
        }
        return null;
    }
}
