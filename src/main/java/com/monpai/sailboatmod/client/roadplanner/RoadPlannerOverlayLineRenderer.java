package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.client.gui.GuiGraphics;

public final class RoadPlannerOverlayLineRenderer {
    public static void drawThickLine(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                     int color, int thickness,
                                     int left, int top, int right, int bottom) {
        drawThickPatternedLine(graphics, x1, y1, x2, y2, color, thickness, 0, 0, left, top, right, bottom);
    }

    public static void drawThickDashedLine(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                           int color, int thickness, int dash, int gap,
                                           int left, int top, int right, int bottom) {
        drawThickPatternedLine(graphics, x1, y1, x2, y2, color, thickness,
                Math.max(1, dash), Math.max(1, gap), left, top, right, bottom);
    }

    private static void drawThickPatternedLine(GuiGraphics graphics, int x1, int y1, int x2, int y2,
                                               int color, int thickness, int dash, int gap,
                                               int left, int top, int right, int bottom) {
        if (graphics == null) {
            return;
        }
        thickness = Math.max(1, thickness);
        int half = thickness / 2;
        for (int ox = -half; ox <= half; ox++) {
            for (int oy = -half; oy <= half; oy++) {
                drawLine(graphics, x1 + ox, y1 + oy, x2 + ox, y2 + oy, color, dash, gap, left, top, right, bottom);
            }
        }
    }

    private static void drawLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color,
                                 int dash, int gap, int left, int top, int right, int bottom) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        int pattern = dash <= 0 ? 0 : dash + gap;
        for (int step = 0; step <= steps; step++) {
            if (pattern > 0 && step % pattern >= dash) {
                continue;
            }
            double t = steps == 0 ? 0.0D : step / (double) steps;
            drawPoint(graphics, (int) Math.round(x1 + dx * t), (int) Math.round(y1 + dy * t),
                    color, left, top, right, bottom);
        }
    }

    private static void drawPoint(GuiGraphics graphics, int x, int y, int color, int left, int top, int right, int bottom) {
        if (x >= left && x <= right && y >= top && y <= bottom) {
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    private RoadPlannerOverlayLineRenderer() {
    }
}
