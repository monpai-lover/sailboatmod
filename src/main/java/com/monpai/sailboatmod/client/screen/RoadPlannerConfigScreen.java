package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.nation.service.ManualRoadPlannerConfig;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.ConfigureRoadPlannerPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class RoadPlannerConfigScreen extends Screen {
    private static final int SCREEN_W = 200;
    private static final int SCREEN_H = 180;

    private static final int[] WIDTH_OPTIONS = {3, 5, 7};
    private static final String[] MATERIAL_OPTIONS = {"auto", "stone_brick", "sandstone", "cobblestone"};
    private static final Component[] MATERIAL_LABELS = {
            Component.translatable("screen.sailboatmod.road_planner.config.mat.auto"),
            Component.translatable("screen.sailboatmod.road_planner.config.mat.stone_brick"),
            Component.translatable("screen.sailboatmod.road_planner.config.mat.sandstone"),
            Component.translatable("screen.sailboatmod.road_planner.config.mat.cobblestone")
    };

    private int selectedWidth = 3;
    private int selectedMaterialIndex = 0;
    private boolean tunnelEnabled = false;
    private boolean reopenOptionsOnClose = true;

    private Button[] widthButtons;
    private Button[] materialButtons;
    private Button tunnelButton;

    public RoadPlannerConfigScreen() {
        this(RoadPlannerClientHooks.currentPlannerConfig());
    }

    public RoadPlannerConfigScreen(ManualRoadPlannerConfig initialConfig) {
        super(Component.translatable("screen.sailboatmod.road_planner.config.title"));
        ManualRoadPlannerConfig normalized = initialConfig == null
                ? ManualRoadPlannerConfig.defaults()
                : ManualRoadPlannerConfig.normalized(initialConfig.width(), initialConfig.materialPreset(), initialConfig.tunnelEnabled());
        this.selectedWidth = normalized.width();
        this.selectedMaterialIndex = materialIndexFor(normalized.materialPreset());
        this.tunnelEnabled = normalized.tunnelEnabled();
    }

    @Override
    protected void init() {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;

        // Width buttons
        widthButtons = new Button[WIDTH_OPTIONS.length];
        int widthBtnY = top + 30;
        for (int i = 0; i < WIDTH_OPTIONS.length; i++) {
            final int w = WIDTH_OPTIONS[i];
            widthButtons[i] = this.addRenderableWidget(Button.builder(
                    Component.literal(String.valueOf(w)),
                    b -> selectWidth(w)
            ).bounds(left + 14 + i * 40, widthBtnY, 34, 18).build());
        }
        updateWidthHighlights();

        // Material buttons
        materialButtons = new Button[MATERIAL_OPTIONS.length];
        int matBtnY = top + 68;
        for (int i = 0; i < MATERIAL_OPTIONS.length; i++) {
            final int idx = i;
            materialButtons[i] = this.addRenderableWidget(Button.builder(
                    MATERIAL_LABELS[i],
                    b -> selectMaterial(idx)
            ).bounds(left + 14 + i * 44, matBtnY, 40, 18).build());
        }
        updateMaterialHighlights();

        // Tunnel toggle
        int tunnelY = top + 104;
        tunnelButton = this.addRenderableWidget(Button.builder(
                tunnelLabel(),
                b -> toggleTunnel()
        ).bounds(left + 14, tunnelY, 172, 18).build());

        // Confirm / Cancel
        int btnY = top + SCREEN_H - 28;
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.road_planner.config.confirm"),
                b -> confirm()
        ).bounds(left + 14, btnY, 80, 18).build());
        this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.road_planner.config.cancel"),
                b -> onClose()
        ).bounds(left + SCREEN_W - 74, btnY, 60, 18).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        guiGraphics.fill(left, top, left + SCREEN_W, top + SCREEN_H, 0xD1141920);
        guiGraphics.fill(left + 1, top + 1, left + SCREEN_W - 1, top + SCREEN_H - 1, 0xEE202933);
        guiGraphics.drawString(this.font, this.title, left + 14, top + 10, 0xFFF2DEAF);
        guiGraphics.drawString(this.font,
                Component.translatable("screen.sailboatmod.road_planner.config.width_label"),
                left + 14, top + 22, 0xFFB9CAD9);
        guiGraphics.drawString(this.font,
                Component.translatable("screen.sailboatmod.road_planner.config.material_label"),
                left + 14, top + 58, 0xFFB9CAD9);
        guiGraphics.drawString(this.font,
                Component.translatable("screen.sailboatmod.road_planner.config.tunnel_label"),
                left + 14, top + 94, 0xFFB9CAD9);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void selectWidth(int w) {
        selectedWidth = w;
        updateWidthHighlights();
    }

    private void selectMaterial(int index) {
        selectedMaterialIndex = index;
        updateMaterialHighlights();
    }

    private void toggleTunnel() {
        tunnelEnabled = !tunnelEnabled;
        if (tunnelButton != null) {
            tunnelButton.setMessage(tunnelLabel());
        }
    }

    private void updateWidthHighlights() {
        for (int i = 0; i < WIDTH_OPTIONS.length; i++) {
            widthButtons[i].active = WIDTH_OPTIONS[i] != selectedWidth;
        }
    }

    private void updateMaterialHighlights() {
        for (int i = 0; i < MATERIAL_OPTIONS.length; i++) {
            materialButtons[i].active = i != selectedMaterialIndex;
        }
    }

    private Component tunnelLabel() {
        return tunnelEnabled
                ? Component.translatable("screen.sailboatmod.road_planner.config.tunnel_on")
                : Component.translatable("screen.sailboatmod.road_planner.config.tunnel_off");
    }

    private int materialIndexFor(String materialPreset) {
        String normalized = materialPreset == null ? "auto" : materialPreset;
        for (int i = 0; i < MATERIAL_OPTIONS.length; i++) {
            if (MATERIAL_OPTIONS[i].equalsIgnoreCase(normalized)) {
                return i;
            }
        }
        return 0;
    }

    private void confirm() {
        reopenOptionsOnClose = false;
        ManualRoadPlannerConfig config = ManualRoadPlannerConfig.normalized(
                selectedWidth,
                MATERIAL_OPTIONS[selectedMaterialIndex],
                tunnelEnabled
        );
        RoadPlannerClientHooks.rememberPlannerConfig(config);
        RoadPlannerClientHooks.exitConfigModeAwaitPreview();
        ModNetwork.CHANNEL.sendToServer(new ConfigureRoadPlannerPacket(
                config.width(), "default", config.materialPreset(), config.tunnelEnabled()));
        onClose();
    }

    @Override
    public void onClose() {
        if (reopenOptionsOnClose) {
            RoadPlannerClientHooks.returnToOptionSelection();
            return;
        }
        super.onClose();
    }
}
