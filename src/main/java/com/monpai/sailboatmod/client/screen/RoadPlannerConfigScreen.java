package com.monpai.sailboatmod.client.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.nation.service.ManualRoadPlannerConfig;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.ConfigureRoadPlannerPacket;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Width, material and tunnel choice for an automatic road. "Confirm" sends {@link ConfigureRoadPlannerPacket} as
 * before; closing any other way returns to the route options.
 */
public class RoadPlannerConfigScreen extends ThemedScreen {
    private static final int[] WIDTH_OPTIONS = {3, 5, 7};
    private static final String[] MATERIAL_OPTIONS = {"auto", "stone_brick", "sandstone", "cobblestone"};

    private int selectedWidth;
    private int selectedMaterialIndex;
    private boolean tunnelEnabled;
    private boolean reopenOptionsOnClose = true;
    private final List<Button> widthButtons = new ArrayList<>();
    private final List<Button> materialButtons = new ArrayList<>();
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
    protected float windowWidth() {
        return 230;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        UIElement widths = Ui.row();
        widths.layout(l -> l.gapAll(Ui.GAP_S));
        for (int option : WIDTH_OPTIONS) {
            Button button = Ui.button(Component.literal(Integer.toString(option)), Ui.Tone.SECONDARY, () -> {
                selectedWidth = option;
                refresh();
            });
            button.layout(l -> l.width(34));
            widthButtons.add(button);
            widths.addChild(button);
        }
        UIElement materials = Ui.wrapRow(Ui.GAP_S);
        materials.layout(l -> l.widthPercent(100));
        for (int i = 0; i < MATERIAL_OPTIONS.length; i++) {
            int index = i;
            Button button = Ui.button(Component.translatable("screen.sailboatmod.ui.road.config_material." + MATERIAL_OPTIONS[i]), Ui.Tone.SECONDARY, () -> {
                selectedMaterialIndex = index;
                refresh();
            });
            materialButtons.add(button);
            materials.addChild(button);
        }
        tunnelButton = Ui.button(Component.empty(), Ui.Tone.SECONDARY, () -> {
            tunnelEnabled = !tunnelEnabled;
            refresh();
        });
        tunnelButton.layout(l -> l.widthPercent(100));
        UIElement actions = Ui.row();
        actions.layout(l -> l.gapAll(Ui.GAP_S));
        actions.addChildren(Ui.spacer(),
                Ui.button(Component.translatable("screen.sailboatmod.road_planner.config.cancel"), Ui.Tone.SECONDARY, this::onClose),
                Ui.button(Component.translatable("screen.sailboatmod.road_planner.config.confirm"), Ui.Tone.PRIMARY, this::confirm));
        body.addChildren(
                Ui.muted(Component.translatable("screen.sailboatmod.road_planner.config.width_label")), widths,
                Ui.muted(Component.translatable("screen.sailboatmod.road_planner.config.material_label")), materials,
                Ui.muted(Component.translatable("screen.sailboatmod.road_planner.config.tunnel_label")), tunnelButton,
                actions);
        refresh();
        return body;
    }

    private void refresh() {
        for (int i = 0; i < WIDTH_OPTIONS.length; i++) {
            Ui.setClass(widthButtons.get(i), "sm-primary", WIDTH_OPTIONS[i] == selectedWidth);
        }
        for (int i = 0; i < materialButtons.size(); i++) {
            Ui.setClass(materialButtons.get(i), "sm-primary", i == selectedMaterialIndex);
        }
        tunnelButton.setText(tunnelEnabled
                ? Component.translatable("screen.sailboatmod.road_planner.config.tunnel_on")
                : Component.translatable("screen.sailboatmod.road_planner.config.tunnel_off"));
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
