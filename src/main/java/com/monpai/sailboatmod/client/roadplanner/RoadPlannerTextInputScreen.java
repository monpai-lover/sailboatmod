package com.monpai.sailboatmod.client.roadplanner;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRenameRoadPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/** Renames a planned road; returns to the planner either way. Blank names are not sent, as before. */
public class RoadPlannerTextInputScreen extends ThemedScreen {
    private static final int MAX_LENGTH = 64;

    private final UUID routeId;
    private final UUID edgeId;
    private final String initialValue;
    private final Screen parent;
    private TextField input;

    public RoadPlannerTextInputScreen(UUID routeId, UUID edgeId, String initialValue, Screen parent) {
        super(Component.translatable("screen.sailboatmod.ui.road.rename"));
        this.routeId = routeId == null ? new UUID(0L, 0L) : routeId;
        this.edgeId = edgeId == null ? new UUID(0L, 0L) : edgeId;
        this.initialValue = clean(initialValue);
        this.parent = parent;
    }

    @Override
    protected float windowWidth() {
        return 250;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        input = Ui.field(initialValue, Component.translatable("screen.sailboatmod.ui.road.name"), value -> { });
        input.layout(l -> l.widthPercent(100));
        UIElement actions = Ui.row();
        actions.layout(l -> l.gapAll(Ui.GAP_S));
        actions.addChildren(Ui.spacer(),
                Ui.button(Component.translatable("gui.cancel"), Ui.Tone.SECONDARY, this::cancel),
                Ui.button(Component.translatable("gui.done"), Ui.Tone.PRIMARY, this::submit));
        body.addChildren(input, actions);
        return body;
    }

    @Override
    protected void afterInit() {
        modularUI().requestFocus(input);
    }

    @Override
    protected boolean onTextFieldKey(UIElement field, int keyCode) {
        if (isEnter(keyCode)) {
            submit();
            return true;
        }
        return false;
    }

    @Override
    protected void onEscape() {
        cancel();
    }

    @Override
    public void onClose() {
        cancel();
    }

    private void submit() {
        String value = input == null ? "" : input.getValue();
        if (value.length() > MAX_LENGTH) {
            value = value.substring(0, MAX_LENGTH);
        }
        SubmitResult result = submitForTest(routeId, edgeId, value);
        if (!result.rejected()) {
            ModNetwork.CHANNEL.sendToServer(new RoadPlannerRenameRoadPacket(result.routeId(), result.edgeId(), result.value()));
        }
        Minecraft.getInstance().setScreen(parent);
    }

    private void cancel() {
        Minecraft.getInstance().setScreen(parent);
    }

    public static SubmitResult submitForTest(UUID routeId, UUID edgeId, String value) {
        String clean = clean(value);
        if (clean.isBlank()) {
            return new SubmitResult(routeId, edgeId, "", true);
        }
        return new SubmitResult(routeId, edgeId, clean, false);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record SubmitResult(UUID routeId, UUID edgeId, String value, boolean rejected) {
    }
}
