package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerCancelJobPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerActionMenuMode;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerConfirmBuildPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerDemolishRoadPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMenuActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

public class RoadPlannerActionMenuScreen extends Screen {
    private static final UUID EMPTY_ID = new UUID(0L, 0L);

    private final RoadPlannerActionMenuMode mode;
    private final UUID sessionId;

    public RoadPlannerActionMenuScreen(RoadPlannerActionMenuMode mode, UUID sessionId) {
        super(Component.literal(titleFor(mode)));
        this.mode = mode == null ? RoadPlannerActionMenuMode.MAIN : mode;
        this.sessionId = sessionId == null ? EMPTY_ID : sessionId;
    }

    @Override
    protected void init() {
        int left = width / 2 - 90;
        int top = height / 2 - 58;
        int row = 24;
        if (mode == RoadPlannerActionMenuMode.PREVIEW) {
            addButton(left, top, "\u786e\u8ba4\u5efa\u9020", button -> confirmBuild());
            addButton(left, top + row, "\u8fd4\u56de\u89c4\u5212\u5668", button -> returnToPlanner());
            addButton(left, top + row * 2, "\u53d6\u6d88\u9884\u89c8", button -> cancelPreview());
            addButton(left, top + row * 3, "\u5173\u95ed", button -> onClose());
            return;
        }
        if (mode == RoadPlannerActionMenuMode.BUILDING) {
            addButton(left, top, "\u67e5\u770b\u65bd\u5de5\u8fdb\u5ea6", button -> closeOnly());
            addButton(left, top + row, "\u53d6\u6d88\u5efa\u9020\u5e76\u56de\u6eda", button -> cancelBuildAndRollback());
            addButton(left, top + row * 2, "\u6682\u505c/\u7ee7\u7eed\u65bd\u5de5", button -> closeOnly());
            addButton(left, top + row * 3, "\u5173\u95ed", button -> onClose());
            return;
        }
        addButton(left, top, "\u6253\u5f00\u9053\u8def\u89c4\u5212", button -> openPlannerFromServer());
        addButton(left, top + row, "\u9009\u62e9\u76ee\u7684\u5730 Town", button -> closeOnly());
        addButton(left, top + row * 2, "\u62c6\u9664\u5df2\u6709\u9053\u8def", button -> openDemolitionPlanner());
        addButton(left, top + row * 3, "\u67e5\u770b\u65bd\u5de5\u961f\u5217", button -> closeOnly());
        addButton(left, top + row * 4, "\u5173\u95ed", button -> onClose());
    }

    private void addButton(int x, int y, String label, Button.OnPress onPress) {
        addRenderableWidget(Button.builder(Component.literal(label), onPress).bounds(x, y, 180, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 86, 0xFFF1D9A0);
        if (mode == RoadPlannerActionMenuMode.PREVIEW) {
            graphics.drawCenteredString(font, Component.literal("\u5f53\u524d\u6709\u9053\u8def\u5e7d\u7075\u9884\u89c8\uff0c\u8bf7\u9009\u62e9\u4e0b\u4e00\u6b65\u64cd\u4f5c"), width / 2, height / 2 - 70, 0xFFB7C8D6);
        } else if (mode == RoadPlannerActionMenuMode.BUILDING) {
            graphics.drawCenteredString(font, Component.literal("\u5f53\u524d\u5b58\u5728\u65bd\u5de5\u4efb\u52a1\uff0c\u53ef\u53d6\u6d88\u5e76\u56de\u6eda"), width / 2, height / 2 - 70, 0xFFB7C8D6);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void confirmBuild() {
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerConfirmBuildPacket(sessionId));
        RoadPlannerClientHooks.clearPreview();
        onClose();
    }

    private void returnToPlanner() {
        RoadPlannerClientHooks.clearPreview();
        openPlannerFromServer();
    }

    private void openPlannerFromServer() {
        RoadPlannerMenuActionPacket.Action action = mode == RoadPlannerActionMenuMode.PREVIEW
                ? RoadPlannerMenuActionPacket.Action.RETURN_TO_PLANNER
                : RoadPlannerMenuActionPacket.Action.OPEN_PLANNER;
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerMenuActionPacket(action));
        Minecraft.getInstance().setScreen(null);
    }

    private void cancelPreview() {
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerCancelJobPacket(sessionId));
        RoadPlannerClientHooks.clearPreview();
        onClose();
    }

    private void cancelBuildAndRollback() {
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerCancelJobPacket(sessionId));
        onClose();
    }

    private void openDemolitionPlanner() {
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerMenuActionPacket(RoadPlannerMenuActionPacket.Action.OPEN_DEMOLITION_PLANNER));
        onClose();
    }

    private void closeOnly() {
        onClose();
    }

    private static String titleFor(RoadPlannerActionMenuMode mode) {
        return switch (mode == null ? RoadPlannerActionMenuMode.MAIN : mode) {
            case PREVIEW -> "\u9053\u8def\u9884\u89c8\u786e\u8ba4";
            case BUILDING -> "\u9053\u8def\u65bd\u5de5\u7ba1\u7406";
            case MAIN -> "\u9053\u8def\u89c4\u5212\u5668";
        };
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

}
