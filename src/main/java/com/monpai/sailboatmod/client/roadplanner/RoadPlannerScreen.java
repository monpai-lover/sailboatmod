package com.monpai.sailboatmod.client.roadplanner;

import gg.essential.elementa.ElementaVersion;
import gg.essential.elementa.WindowScreen;
import gg.essential.elementa.components.UIBlock;
import gg.essential.elementa.components.UIText;
import gg.essential.elementa.constraints.PixelConstraint;

import java.awt.Color;
import java.util.UUID;

public class RoadPlannerScreen extends WindowScreen {
    private static final Color BACKGROUND = new Color(7, 17, 31, 232);
    private static final Color PANEL = new Color(16, 24, 39, 238);
    private static final Color MENU_PANEL = new Color(16, 24, 39, 238);
    private static final Color MENU_BORDER = new Color(47, 59, 82, 255);
    private static final Color MENU_HOVER = new Color(59, 130, 246, 102);
    private static final Color ACCENT = new Color(52, 211, 153, 255);
    private static final Color MUTED = new Color(148, 163, 184);

    private RoadPlannerClientState state;

    public RoadPlannerScreen(UUID sessionId) {
        super(ElementaVersion.V2, false, false, false);
        this.state = RoadPlannerClientState.open(sessionId);
        buildSkeleton();
    }

    public RoadPlannerClientState state() {
        return state;
    }

    private void buildSkeleton() {
        UIBlock root = new UIBlock(BACKGROUND);
        root.setX(new PixelConstraint(0));
        root.setY(new PixelConstraint(0));
        root.setWidth(new PixelConstraint(1280));
        root.setHeight(new PixelConstraint(720));
        getWindow().addChild(root);

        UIBlock toolbar = panel(16, 16, 76, 688);
        root.addChild(toolbar);
        toolbar.addChild(label("工具", 16, 12, ACCENT));

        UIBlock map = panel(106, 16, 842, 688);
        root.addChild(map);
        map.addChild(label("RoadPlanner 小地图", 18, 14, Color.WHITE));
        map.addChild(label("真实快照 / B+C 走廊 / 节流瓦片上传", 18, 36, MUTED));
        map.addChild(label("右键道路线条：RoadWeaver 风格编辑菜单", 18, 60, MUTED));

        UIBlock side = panel(962, 16, 302, 688);
        root.addChild(side);
        side.addChild(label("道路信息", 16, 14, Color.WHITE));
        side.addChild(label("选择工具可编辑、命名、拆除道路", 16, 38, MUTED));
        side.addChild(roadWeaverMenuPreview(16, 78));
    }

    private UIBlock panel(int x, int y, int width, int height) {
        UIBlock block = new UIBlock(PANEL);
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
        UIBlock shadow = new UIBlock(new Color(0, 0, 0, 96));
        shadow.setX(new PixelConstraint(x + 2));
        shadow.setY(new PixelConstraint(y + 2));
        shadow.setWidth(new PixelConstraint(178));
        shadow.setHeight(new PixelConstraint(132));

        UIBlock border = new UIBlock(MENU_BORDER);
        border.setX(new PixelConstraint(x));
        border.setY(new PixelConstraint(y));
        border.setWidth(new PixelConstraint(178));
        border.setHeight(new PixelConstraint(132));
        shadow.addChild(border);

        UIBlock menu = new UIBlock(MENU_PANEL);
        menu.setX(new PixelConstraint(1));
        menu.setY(new PixelConstraint(1));
        menu.setWidth(new PixelConstraint(176));
        menu.setHeight(new PixelConstraint(130));
        border.addChild(menu);

        menu.addChild(label("重命名道路", 6, 8, Color.WHITE));
        UIBlock hover = new UIBlock(MENU_HOVER);
        hover.setX(new PixelConstraint(3));
        hover.setY(new PixelConstraint(28));
        hover.setWidth(new PixelConstraint(170));
        hover.setHeight(new PixelConstraint(16));
        menu.addChild(hover);
        hover.addChild(label("编辑节点", 3, 2, Color.WHITE));
        menu.addChild(label("────────────", 6, 48, MUTED));
        menu.addChild(label("拆除本段", 6, 66, new Color(248, 113, 113)));
        menu.addChild(label("拆除分支", 6, 84, new Color(248, 113, 113)));
        menu.addChild(label("查看回滚账本", 6, 106, new Color(229, 231, 235)));
        return shadow;
    }
}
