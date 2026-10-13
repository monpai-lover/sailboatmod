package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.client.ui.kit.ActionButton;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.state.ConfirmGate;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerActionMenuMode;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerCancelJobPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerConfirmBuildPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMenuActionPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPauseBuildPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

import java.util.UUID;

/**
 * Road planner hub opened from the planner item: main actions, the preview decision, or managing a running build.
 * Sends the same packets as before. Cancelling a running build (which rolls it back) needs a second press.
 */
public class RoadPlannerActionMenuScreen extends ThemedScreen {
    private static final UUID EMPTY_ID = new UUID(0L, 0L);
    private static final String CONFIRM_ROLLBACK = "rollback";

    private final RoadPlannerActionMenuMode mode;
    private final UUID sessionId;
    private final ConfirmGate confirm = new ConfirmGate();
    private ActionButton rollbackButton;

    public RoadPlannerActionMenuScreen(RoadPlannerActionMenuMode mode, UUID sessionId) {
        super(Component.translatable(titleKey(mode)));
        this.mode = mode == null ? RoadPlannerActionMenuMode.MAIN : mode;
        this.sessionId = sessionId == null ? EMPTY_ID : sessionId;
    }

    @Override
    protected float windowWidth() {
        return 220;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        body.layout(l -> l.gapAll(Ui.GAP_S));
        switch (mode) {
            case PREVIEW -> {
                body.addChild(Ui.paragraph(Component.translatable("screen.sailboatmod.ui.road.menu.preview_hint"), "sm-muted"));
                body.addChild(item(Items.BRICKS, "confirm_build", Ui.Tone.PRIMARY, this::confirmBuild));
                body.addChild(item(Items.MAP, "return_to_planner", Ui.Tone.SECONDARY, this::returnToPlanner));
                body.addChild(item(Items.BARRIER, "cancel_preview", Ui.Tone.DANGER, this::cancelPreview));
            }
            case BUILDING -> {
                body.addChild(Ui.paragraph(Component.translatable("screen.sailboatmod.ui.road.menu.building_hint"), "sm-muted"));
                body.addChild(item(Items.SPYGLASS, "view_progress", Ui.Tone.SECONDARY, this::onClose));
                body.addChild(item(Items.CLOCK, "toggle_pause", Ui.Tone.SECONDARY, this::togglePauseBuild));
                rollbackButton = new ActionButton(Component.translatable("screen.sailboatmod.ui.road.menu.cancel_rollback"),
                        Ui.Tone.DANGER, this::cancelBuildAndRollback);
                rollbackButton.element().layout(l -> l.widthPercent(100).height(20));
                body.addChild(rollbackButton.element());
            }
            case MAIN -> {
                body.addChild(item(Items.MAP, "open_planner", Ui.Tone.PRIMARY, this::openPlannerFromServer));
                body.addChild(item(Items.COMPASS, "choose_target", Ui.Tone.SECONDARY, () -> menuAction(RoadPlannerMenuActionPacket.Action.OPEN_TARGET_SELECTION)));
                body.addChild(item(Items.DIRT_PATH, "edit_road", Ui.Tone.SECONDARY, () -> menuAction(RoadPlannerMenuActionPacket.Action.OPEN_EDIT_ROAD_SELECTION)));
                body.addChild(item(Items.IRON_PICKAXE, "demolish_road", Ui.Tone.SECONDARY, () -> menuAction(RoadPlannerMenuActionPacket.Action.OPEN_DEMOLITION_PLANNER)));
                body.addChild(item(Items.SPYGLASS, "view_queue", Ui.Tone.SECONDARY, this::onClose));
            }
        }
        return body;
    }

    private UIElement item(ItemLike icon, String key, Ui.Tone tone, Runnable action) {
        com.lowdragmc.lowdraglib2.gui.ui.elements.Button button =
                Ui.button(Component.translatable("screen.sailboatmod.ui.road.menu." + key), tone, action);
        button.addPreIcon(new com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture(new ItemStack(icon)));
        button.layout(l -> l.widthPercent(100).height(20));
        return button;
    }

    @Override
    protected void onUiTick() {
        if (rollbackButton != null) {
            rollbackButton.sync(true, false, confirm.isArmed(CONFIRM_ROLLBACK, ticks()));
        }
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
        if (!confirm.press(CONFIRM_ROLLBACK, ticks())) {
            onUiTick();
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerCancelJobPacket(sessionId));
        onClose();
    }

    private void togglePauseBuild() {
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerPauseBuildPacket(sessionId));
        onClose();
    }

    private void menuAction(RoadPlannerMenuActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerMenuActionPacket(action));
        onClose();
    }

    private static String titleKey(RoadPlannerActionMenuMode mode) {
        return switch (mode == null ? RoadPlannerActionMenuMode.MAIN : mode) {
            case PREVIEW -> "screen.sailboatmod.ui.road.menu.title_preview";
            case BUILDING -> "screen.sailboatmod.ui.road.menu.title_building";
            case MAIN -> "screen.sailboatmod.ui.road.menu.title_main";
        };
    }
}
