package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

public class RoadPlannerBuildSettingsScreen extends Screen {
    private static final int PANEL_WIDTH = 240;
    private static final int PANEL_HEIGHT = 180;
    private static final int[] WIDTHS = {3, 5, 7};
    private static final String[] MATERIALS = {"smooth_stone", "stone_bricks", "cobblestone", "oak_planks", "spruce_planks"};
    private static final String[] MATERIAL_LABELS = {"平滑石", "石砖", "圆石", "橡木板", "云杉木板"};

    private final Screen parent;
    private final Consumer<RoadPlannerBuildSettings> onConfirm;
    private int selectedWidth;
    private int selectedMaterialIndex;
    private boolean streetlightsEnabled;

    public RoadPlannerBuildSettingsScreen(Screen parent,
                                          RoadPlannerBuildSettings initialSettings,
                                          Consumer<RoadPlannerBuildSettings> onConfirm) {
        super(Component.literal("道路建造设置"));
        RoadPlannerBuildSettings settings = initialSettings == null ? RoadPlannerBuildSettings.DEFAULTS : initialSettings;
        this.parent = parent;
        this.onConfirm = onConfirm;
        this.selectedWidth = settings.width();
        this.selectedMaterialIndex = materialIndex(settings.materialPreset());
        this.streetlightsEnabled = settings.streetlightsEnabled();
    }

    @Override
    protected void init() {
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        for (int i = 0; i < WIDTHS.length; i++) {
            int widthOption = WIDTHS[i];
            addRenderableWidget(Button.builder(widthLabel(widthOption), button -> {
                selectedWidth = widthOption;
                rebuildWidgets();
            }).bounds(left + 18 + i * 48, top + 42, 42, 20).build());
        }
        for (int i = 0; i < MATERIALS.length; i++) {
            int index = i;
            addRenderableWidget(Button.builder(materialLabel(index), button -> {
                selectedMaterialIndex = index;
                rebuildWidgets();
            }).bounds(left + 18 + (i % 3) * 68, top + 86 + (i / 3) * 24, 62, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal(streetlightsEnabled ? "路灯: 开" : "路灯: 关"), button -> {
            streetlightsEnabled = !streetlightsEnabled;
            rebuildWidgets();
        }).bounds(left + 18, top + 134, 92, 20).build());
        addRenderableWidget(Button.builder(Component.literal("开始预览"), button -> confirm()).bounds(left + 120, top + 134, 82, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), button -> onClose()).bounds(left + 18, top + 156, 184, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE012151B);
        graphics.fill(left + 1, top + 1, left + PANEL_WIDTH - 1, top + PANEL_HEIGHT - 1, 0xF0202630);
        graphics.drawString(font, title, left + 18, top + 14, 0xFFFFE0A3, false);
        graphics.drawString(font, "路宽", left + 18, top + 32, 0xFFC9D7E1, false);
        graphics.drawString(font, "路基材料", left + 18, top + 76, 0xFFC9D7E1, false);
        graphics.drawString(font, "确认后会退出 UI 并显示幽灵方块预览", left + 18, top + 66, 0xFF9FB0BC, false);
        super.render(graphics, mouseX, mouseY, partialTick);
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

    private Component widthLabel(int widthOption) {
        return Component.literal((selectedWidth == widthOption ? "✓ " : "") + widthOption);
    }

    private Component materialLabel(int index) {
        return Component.literal((selectedMaterialIndex == index ? "✓ " : "") + MATERIAL_LABELS[index]);
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
