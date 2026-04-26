package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

public class RoadPlannerMapCanvas {
    private static final int MAP_BG = 0xF508121E;
    private static final int GRID = 0xAA2A3E56;
    private static final int TEXT = 0xFFE5E7EB;
    private static final int MUTED = 0xFF94A3B8;
    private static final int ACCENT = 0xFF34D399;
    private static final int BLUE = 0xFF60A5FA;
    private static final int RED = 0xFFF87171;

    private final RoadPlannerVanillaLayout.Rect rect;
    private final RoadPlannerMapComponent component;

    public RoadPlannerMapCanvas(RoadPlannerVanillaLayout.Rect rect, RoadPlannerMapComponent component) {
        this.rect = rect;
        this.component = component;
    }

    public boolean contains(double mouseX, double mouseY) {
        return rect.contains(mouseX, mouseY);
    }

    public BlockPos mouseToWorld(double mouseX, double mouseY) {
        return component.guiToWorldXZ(mouseX, mouseY);
    }

    public RoadPlannerMapInteractionResult rightClickGraph(RoadPlannerClientState state,
                                                          RoadNetworkGraph graph,
                                                          double worldX,
                                                          double worldZ,
                                                          int mouseX,
                                                          int mouseY) {
        return new RoadPlannerMapInteraction(graph).rightClickRoadLine(state, worldX, worldZ, mouseX, mouseY, 8.0D);
    }

    public void renderPlaceholder(GuiGraphics graphics, Font font) {
        graphics.fill(rect.x(), rect.y(), rect.right(), rect.bottom(), MAP_BG);
        for (int x = rect.x(); x <= rect.right(); x += Math.max(48, rect.width() / 8)) {
            graphics.fill(x, rect.y(), x + 1, rect.bottom(), GRID);
        }
        for (int y = rect.y(); y <= rect.bottom(); y += Math.max(40, rect.height() / 6)) {
            graphics.fill(rect.x(), y, rect.right(), y + 1, GRID);
        }
        graphics.drawString(font, "128x128 区域 / 真实地形快照加载中", rect.x() + 18, rect.y() + 16, MUTED, false);
        graphics.fill(rect.x() + 58, rect.bottom() - 78, rect.x() + 72, rect.bottom() - 64, ACCENT);
        graphics.drawString(font, "起点", rect.x() + 78, rect.bottom() - 80, TEXT, false);
        int centerX = rect.x() + rect.width() / 2;
        int centerY = rect.y() + rect.height() / 2;
        graphics.fill(centerX - 120, centerY, centerX + 100, centerY + 5, BLUE);
        graphics.fill(centerX + 92, centerY - 46, centerX + 97, centerY + 4, BLUE);
        graphics.drawString(font, "当前节点线", centerX - 116, centerY + 14, TEXT, false);
        graphics.fill(rect.right() - 84, rect.y() + 46, rect.right() - 60, rect.y() + 52, RED);
        graphics.fill(rect.right() - 66, rect.y() + 36, rect.right() - 60, rect.y() + 62, RED);
        graphics.drawString(font, "目的地方向", rect.right() - 176, rect.y() + 34, RED, false);
    }
}
