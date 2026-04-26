package com.monpai.sailboatmod.client.roadplanner;

import gg.essential.elementa.ElementaVersion;
import gg.essential.elementa.WindowScreen;
import gg.essential.elementa.components.UIBlock;
import gg.essential.elementa.components.UIText;
import gg.essential.elementa.constraints.PixelConstraint;

import java.awt.Color;
import java.util.UUID;

public class RoadPlannerScreen extends WindowScreen {
    private static final Color BACKGROUND = new Color(5, 12, 24, 190);
    private static final Color PANEL = new Color(13, 22, 38, 238);
    private static final Color PANEL_2 = new Color(18, 30, 50, 238);
    private static final Color MAP_BG = new Color(8, 18, 30, 245);
    private static final Color GRID = new Color(42, 62, 86, 180);
    private static final Color ACCENT = new Color(52, 211, 153, 255);
    private static final Color BLUE = new Color(96, 165, 250, 255);
    private static final Color RED = new Color(248, 113, 113, 255);
    private static final Color MUTED = new Color(148, 163, 184);
    private static final Color TEXT = new Color(229, 231, 235);
    private static final Color MENU_PANEL = new Color(16, 24, 39, 246);
    private static final Color MENU_BORDER = new Color(47, 59, 82, 255);
    private static final Color MENU_HOVER = new Color(59, 130, 246, 102);

    private final RoadPlannerScreenLayoutModel layout = RoadPlannerScreenLayoutModel.mvp();
    private RoadPlannerClientState state;
    private RoadWeaverStyleContextMenuModel contextMenu;
    private RoadPlannerTextInputModel textInput;

    public RoadPlannerScreen(UUID sessionId) {
        super(ElementaVersion.V2, false, false, false);
        this.state = RoadPlannerClientState.open(sessionId);
        buildLayout();
    }

    public RoadPlannerClientState state() {
        return state;
    }

    public void applyInteraction(RoadPlannerMapInteractionResult result) {
        this.state = result.state();
        this.contextMenu = result.contextMenu().orElse(null);
        this.textInput = result.textInput().orElse(null);
    }

    public RoadWeaverStyleContextMenuModel contextMenuForRender() {
        return contextMenu;
    }

    public RoadPlannerTextInputModel textInputForRender() {
        return textInput;
    }

    private void buildLayout() {
        UIBlock root = block(BACKGROUND, 0, 0, 1280, 720);
        getWindow().addChild(root);

        UIBlock toolbar = block(PANEL, 16, 16, 126, 688);
        root.addChild(toolbar);
        toolbar.addChild(label("工具", 18, 16, ACCENT));
        for (int i = 0; i < layout.tools().size(); i++) {
            toolbar.addChild(toolButton(layout.tools().get(i), 14, 58 + i * 54, i == 0));
        }
        toolbar.addChild(label("宽度", 18, 362, MUTED));
        toolbar.addChild(chip("3", 14, 392, false));
        toolbar.addChild(chip("5", 52, 392, true));
        toolbar.addChild(chip("7", 90, 392, false));

        UIBlock mapPanel = block(PANEL, 158, 16, 804, 688);
        root.addChild(mapPanel);
        mapPanel.addChild(label("RoadPlanner 道路规划小地图", 22, 18, TEXT));
        mapPanel.addChild(label("拖动画线路径节点；右键已建道路打开 RoadWeaver 风格编辑菜单", 22, 44, MUTED));
        mapPanel.addChild(minimap(22, 82, 760, 520));
        addButtons(mapPanel, 22, 620);

        UIBlock side = block(PANEL, 978, 16, 286, 688);
        root.addChild(side);
        side.addChild(label("道路信息", 18, 18, TEXT));
        for (int i = 0; i < layout.statusLines().size(); i++) {
            side.addChild(label(layout.statusLines().get(i), 18, 52 + i * 24, MUTED));
        }
        side.addChild(label("目标", 18, 190, TEXT));
        side.addChild(label("未设置目的地", 18, 218, MUTED));
        side.addChild(label("编辑菜单预览", 18, 270, TEXT));
        side.addChild(roadWeaverMenuPreview(18, 306));
    }

    private UIBlock minimap(int x, int y, int width, int height) {
        UIBlock map = block(MAP_BG, x, y, width, height);
        for (int gx = 0; gx <= width; gx += 76) {
            map.addChild(block(GRID, gx, 0, 1, height));
        }
        for (int gy = 0; gy <= height; gy += 52) {
            map.addChild(block(GRID, 0, gy, width, 1));
        }
        map.addChild(block(ACCENT, 58, height - 78, 14, 14));
        map.addChild(label("起点", 78, height - 80, TEXT));
        map.addChild(block(BLUE, width / 2 - 120, height / 2, 220, 5));
        map.addChild(block(BLUE, width / 2 + 92, height / 2 - 46, 5, 50));
        map.addChild(label("当前节点线", width / 2 - 116, height / 2 + 14, TEXT));
        map.addChild(block(RED, width - 84, 46, 24, 6));
        map.addChild(block(RED, width - 66, 36, 6, 26));
        map.addChild(label("目的地方向", width - 176, 34, RED));
        map.addChild(label("128x128 区域 / 真实地形快照加载中", 18, 16, MUTED));
        return map;
    }

    private void addButtons(UIBlock parent, int x, int y) {
        int cursor = x;
        for (String button : layout.buttons()) {
            int width = button.length() >= 4 ? 88 : 72;
            parent.addChild(button(button, cursor, y, width));
            cursor += width + 10;
        }
    }

    private UIBlock toolButton(String text, int x, int y, boolean active) {
        UIBlock button = block(active ? new Color(22, 101, 82, 235) : PANEL_2, x, y, 98, 38);
        button.addChild(label(text, 18, 11, active ? ACCENT : TEXT));
        return button;
    }

    private UIBlock chip(String text, int x, int y, boolean active) {
        UIBlock chip = block(active ? new Color(37, 99, 235, 235) : PANEL_2, x, y, 28, 24);
        chip.addChild(label(text, 9, 6, TEXT));
        return chip;
    }

    private UIBlock button(String text, int x, int y, int width) {
        UIBlock button = block(PANEL_2, x, y, width, 34);
        button.addChild(label(text, 12, 10, TEXT));
        return button;
    }

    private UIBlock block(Color color, int x, int y, int width, int height) {
        UIBlock block = new UIBlock(color);
        block.setX(new PixelConstraint(x));
        block.setY(new PixelConstraint(y));
        block.setWidth(new PixelConstraint(width));
        block.setHeight(new PixelConstraint(height));
        return block;
    }

    private UIText label(String text, int x, int y, Color color) {
        UIText label = new UIText(text);
        label.setX(new PixelConstraint(x));
        label.setY(new PixelConstraint(y));
        label.setColor(color);
        return label;
    }

    private UIBlock roadWeaverMenuPreview(int x, int y) {
        UIBlock shadow = block(new Color(0, 0, 0, 96), x + 2, y + 2, 178, 132);
        UIBlock border = block(MENU_BORDER, x, y, 178, 132);
        shadow.addChild(border);
        UIBlock menu = block(MENU_PANEL, 1, 1, 176, 130);
        border.addChild(menu);
        menu.addChild(label("重命名道路", 6, 8, TEXT));
        UIBlock hover = block(MENU_HOVER, 3, 28, 170, 16);
        menu.addChild(hover);
        hover.addChild(label("编辑节点", 3, 2, TEXT));
        menu.addChild(label("────────────", 6, 48, MUTED));
        menu.addChild(label("拆除本段", 6, 66, RED));
        menu.addChild(label("拆除分支", 6, 84, RED));
        menu.addChild(label("查看回滚账本", 6, 106, TEXT));
        return shadow;
    }
}
