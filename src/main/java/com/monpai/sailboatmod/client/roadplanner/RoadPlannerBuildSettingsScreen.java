package com.monpai.sailboatmod.client.roadplanner;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.monpai.sailboatmod.client.ui.kit.ThemedScreen;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ItemLike;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Road width, surface material and streetlights before the ghost-block preview. The chosen values are passed to the
 * planner exactly as before; "Back" returns to the planner.
 */
public class RoadPlannerBuildSettingsScreen extends ThemedScreen {
    private static final int[] WIDTHS = {3, 5, 7};
    private static final String[] MATERIALS = {"smooth_stone", "stone_bricks", "cobblestone", "oak_planks", "spruce_planks"};
    private static final ItemLike[] MATERIAL_ITEMS = {Items.SMOOTH_STONE, Items.STONE_BRICKS, Items.COBBLESTONE, Items.OAK_PLANKS, Items.SPRUCE_PLANKS};

    private final Screen parent;
    private final Consumer<RoadPlannerBuildSettings> onConfirm;
    private int selectedWidth;
    private int selectedMaterialIndex;
    private boolean streetlightsEnabled;
    private final List<Button> widthButtons = new ArrayList<>();
    private final List<UIElement> materialCards = new ArrayList<>();
    private Button lightsButton;

    public RoadPlannerBuildSettingsScreen(Screen parent,
                                          RoadPlannerBuildSettings initialSettings,
                                          Consumer<RoadPlannerBuildSettings> onConfirm) {
        super(Component.translatable("screen.sailboatmod.ui.road.build_settings"));
        RoadPlannerBuildSettings settings = initialSettings == null ? RoadPlannerBuildSettings.DEFAULTS : initialSettings;
        this.parent = parent;
        this.onConfirm = onConfirm;
        this.selectedWidth = settings.width();
        this.selectedMaterialIndex = materialIndex(settings.materialPreset());
        this.streetlightsEnabled = settings.streetlightsEnabled();
    }

    @Override
    protected float windowWidth() {
        return 250;
    }

    @Override
    protected UIElement buildBody() {
        UIElement body = Ui.column();
        UIElement widths = Ui.row();
        widths.layout(l -> l.gapAll(Ui.GAP_S));
        widths.addChild(Ui.muted(Component.translatable("screen.sailboatmod.ui.road.width")));
        for (int option : WIDTHS) {
            Button button = Ui.button(Component.literal(Integer.toString(option)), Ui.Tone.SECONDARY, () -> {
                selectedWidth = option;
                refresh();
            });
            button.layout(l -> l.width(32));
            widthButtons.add(button);
            widths.addChild(button);
        }

        UIElement materials = Ui.wrapRow(Ui.GAP_S);
        materials.layout(l -> l.widthPercent(100));
        for (int i = 0; i < MATERIALS.length; i++) {
            int index = i;
            UIElement card = Ui.row("sm-row");
            card.layout(l -> l.width(74).height(22).paddingHorizontal(Ui.GAP_XS).gapAll(Ui.GAP_XS));
            card.addChildren(Ui.slot(new ItemStack(MATERIAL_ITEMS[i]), Ui.SLOT),
                    Ui.text(Component.translatable("screen.sailboatmod.ui.road.material." + MATERIALS[i])));
            Ui.clickable(card, () -> {
                selectedMaterialIndex = index;
                refresh();
            });
            materialCards.add(card);
            materials.addChild(card);
        }

        lightsButton = Ui.button(Component.empty(), Ui.Tone.SECONDARY, () -> {
            streetlightsEnabled = !streetlightsEnabled;
            refresh();
        });
        UIElement actions = Ui.row();
        actions.layout(l -> l.gapAll(Ui.GAP_S));
        actions.addChildren(lightsButton, Ui.spacer(),
                Ui.button(Component.translatable("gui.back"), Ui.Tone.SECONDARY, this::onClose),
                Ui.button(Component.translatable("screen.sailboatmod.ui.road.start_preview"), Ui.Tone.PRIMARY, this::confirm));
        body.addChildren(widths, Ui.muted(Component.translatable("screen.sailboatmod.ui.road.material")), materials,
                Ui.paragraph(Component.translatable("screen.sailboatmod.ui.road.preview_hint"), "sm-muted"), actions);
        refresh();
        return body;
    }

    private void refresh() {
        for (int i = 0; i < WIDTHS.length; i++) {
            Ui.setClass(widthButtons.get(i), "sm-primary", WIDTHS[i] == selectedWidth);
        }
        for (int i = 0; i < materialCards.size(); i++) {
            Ui.setClass(materialCards.get(i), "selected", i == selectedMaterialIndex);
        }
        lightsButton.setText(Component.translatable("screen.sailboatmod.ui.road.streetlights",
                Component.translatable(streetlightsEnabled ? "screen.sailboatmod.toggle.on" : "screen.sailboatmod.toggle.off")));
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    private void confirm() {
        if (onConfirm != null) {
            onConfirm.accept(new RoadPlannerBuildSettings(selectedWidth, MATERIALS[selectedMaterialIndex], streetlightsEnabled));
        }
    }

    private int materialIndex(String materialPreset) {
        String normalized = RoadPlannerBuildSettings.normalizeMaterial(materialPreset);
        for (int i = 0; i < MATERIALS.length; i++) {
            if (MATERIALS[i].equals(normalized)) {
                return i;
            }
        }
        return 0;
    }
}
