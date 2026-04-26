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
    private static final Color PANEL = new Color(15, 23, 42, 235);
    private static final Color ACCENT = new Color(52, 211, 153, 255);

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
        map.addChild(label("真实快照 / B+C 走廊 / 节流瓦片上传", 18, 36, new Color(148, 163, 184)));

        UIBlock side = panel(962, 16, 302, 688);
        root.addChild(side);
        side.addChild(label("道路信息", 16, 14, Color.WHITE));
        side.addChild(label("选择工具可编辑/拆除道路", 16, 38, new Color(148, 163, 184)));
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
}
