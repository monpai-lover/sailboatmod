package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRenameRoadPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class RoadPlannerTextInputScreen extends Screen {
    private final UUID routeId;
    private final UUID edgeId;
    private final String initialValue;
    private final Screen parent;
    private EditBox input;

    public RoadPlannerTextInputScreen(UUID routeId, UUID edgeId, String initialValue, Screen parent) {
        super(Component.literal("重命名道路"));
        this.routeId = routeId == null ? new UUID(0L, 0L) : routeId;
        this.edgeId = edgeId == null ? new UUID(0L, 0L) : edgeId;
        this.initialValue = clean(initialValue);
        this.parent = parent;
    }

    @Override
    protected void init() {
        int boxWidth = 240;
        int x = (width - boxWidth) / 2;
        int y = height / 2 - 20;
        input = new EditBox(font, x, y, boxWidth, 20, title);
        input.setMaxLength(64);
        input.setValue(initialValue);
        addRenderableWidget(input);
        addRenderableWidget(Button.builder(Component.literal("确定"), ignored -> submit()).bounds(x, y + 28, 80, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), ignored -> cancel()).bounds(x + boxWidth - 80, y + 28, 80, 20).build());
        setInitialFocus(input);
    }

    private void submit() {
        SubmitResult result = submitForTest(routeId, edgeId, input == null ? "" : input.getValue());
        if (!result.rejected()) {
            ModNetwork.CHANNEL.sendToServer(new RoadPlannerRenameRoadPacket(result.routeId(), result.edgeId(), result.value()));
        }
        Minecraft.getInstance().setScreen(parent);
    }

    private void cancel() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) {
            submit();
            return true;
        }
        if (keyCode == 256) {
            cancel();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
