package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.vehicle.AutopilotPanel;
import com.monpai.sailboatmod.entity.CarriageEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.OpenSailboatStoragePacket;
import com.monpai.sailboatmod.network.packet.RenameSailboatPacket;
import com.monpai.sailboatmod.network.packet.SetUnloadOnArrivalPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Carriage panel opened while riding: name, storage, autopilot and unload-on-arrival. Sends the same packets as
 * before and closes when the player leaves the carriage.
 */
public class CarriageInfoScreen extends ThemedScreen {
    private final CarriageEntity carriage;
    private TextField nameInput;
    private final Label owner = Ui.text("", "sm-muted");
    private final Label storage = Ui.text("", "sm-muted");
    private Button unloadButton;
    private AutopilotPanel autopilot;

    public CarriageInfoScreen(CarriageEntity carriage) {
        super(carriage.getInfoScreenTitle());
        this.carriage = carriage;
    }

    @Override
    protected float windowWidth() {
        return 240;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        UIElement identity = Ui.column("sm-pane");
        identity.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        nameInput = Ui.field(carriage.hasCustomName() ? carriage.getCustomName().getString() : "",
                Component.translatable("screen.sailboatmod.name"), value -> { });
        nameInput.layout(l -> l.widthPercent(100));
        UIElement actions = Ui.row();
        actions.layout(l -> l.gapAll(Ui.GAP_S));
        actions.addChildren(Ui.spacer(),
                Ui.button(Component.translatable("screen.sailboatmod.open_storage"), Ui.Tone.SECONDARY,
                        () -> ModNetwork.CHANNEL.sendToServer(new OpenSailboatStoragePacket())),
                Ui.button(Component.translatable("screen.sailboatmod.save"), Ui.Tone.PRIMARY, () -> {
                    ModNetwork.CHANNEL.sendToServer(new RenameSailboatPacket(carriage.getId(), this.nameInput.getValue()));
                    toast.show(Component.translatable("screen.sailboatmod.ui.saved"), true, ticks());
                }));
        UIElement facts = Ui.row();
        facts.layout(l -> l.gapAll(Ui.GAP_M));
        facts.addChildren(owner, storage);
        identity.addChildren(nameInput, actions, facts);

        autopilot = new AutopilotPanel(carriage, carriage::getRouteCount, carriage::getSelectedRouteIndex);
        unloadButton = Ui.button(Component.empty(), Ui.Tone.SECONDARY, () -> {
            boolean next = !carriage.isUnloadOnArrival();
            ModNetwork.CHANNEL.sendToServer(new SetUnloadOnArrivalPacket(carriage.getId(), next));
            carriage.setAllowNonOrderAutoUnload(next);
            refresh();
        });
        unloadButton.layout(l -> l.widthPercent(100));
        body.addChildren(identity, autopilot.element(), unloadButton);
        refresh();
        return body;
    }

    @Override
    protected void onUiTick() {
        if (Minecraft.getInstance().player == null || Minecraft.getInstance().player.getVehicle() != carriage) {
            onClose();
            return;
        }
        refresh();
    }

    private void refresh() {
        owner.setValue(Component.translatable("screen.sailboatmod.owner_name", carriage.getOwnerName()));
        storage.setValue(Component.translatable("screen.sailboatmod.storage_info", carriage.getStorageSlotCount()));
        unloadButton.setText(Component.translatable("screen.sailboatmod.vehicle.unload_on_arrival")
                .append(": ")
                .append(Component.translatable(carriage.isUnloadOnArrival() ? "screen.sailboatmod.toggle.on" : "screen.sailboatmod.toggle.off")));
        autopilot.refresh();
    }
}
