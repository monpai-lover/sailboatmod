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
        dash = dash <= 0 ? 0 : Math.max(1, dash);
        gap = dash <= 0 ? 0 : Math.max(1, gap);
        if (x1 == x2 || y1 == y2) {
            drawAxisAlignedLine(graphics, x1, y1, x2, y2, color, thickness, dash, gap, left, top, right, bottom);
            return;
        }
        drawSampledLine(graphics, x1, y1, x2, y2, color, thickness, dash, gap, left, top, right, bottom);
    }

    static int estimatedFillCallsForTest(int x1, int y1, int x2, int y2, int thickness, int dash, int gap) {
        thickness = Math.max(1, thickness);
        dash = dash <= 0 ? 0 : Math.max(1, dash);
        gap = dash <= 0 ? 0 : Math.max(1, gap);
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (x1 == x2 || y1 == y2) {
            if (dash <= 0 || steps == 0) {
                return 1;
            }
            int pattern = dash + gap;
            int fills = 0;
            for (int start = 0; start <= steps; start += pattern) {
                fills++;
            }
            return fills;
        }
        if (dash <= 0) {
            return steps + 1;
        }
        int fills = 0;
        int pattern = dash + gap;
        for (int step = 0; step <= steps; step++) {
            if (step % pattern < dash) {
                fills++;
            }
        }
        return fills;
    }

    private static void drawAxisAlignedLine(GuiGraphics graphics,
                                            int x1,
                                            int y1,
                                            int x2,
                                            int y2,
                                            int color,
                                            int thickness,
                                            int dash,
                                            int gap,
                                            int left,
                                            int top,
                                            int right,
                                            int bottom) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        if (dash <= 0 || steps == 0) {
            fillAxisAlignedSegment(graphics, x1, y1, x2, y2, color, thickness, left, top, right, bottom);
            return;
        }
        int pattern = dash + gap;
        int dirX = Integer.compare(x2 - x1, 0);
        int dirY = Integer.compare(y2 - y1, 0);
        for (int start = 0; start <= steps; start += pattern) {
            int end = Math.min(steps, start + dash - 1);
            int sx = x1 + dirX * start;
            int sy = y1 + dirY * start;
            int ex = x1 + dirX * end;
            int ey = y1 + dirY * end;
            fillAxisAlignedSegment(graphics, sx, sy, ex, ey, color, thickness, left, top, right, bottom);
        }
    }

    private static void fillAxisAlignedSegment(GuiGraphics graphics,
                                               int x1,
                                               int y1,
                                               int x2,
                                               int y2,
                                               int color,
                                               int thickness,
                                               int left,
                                               int top,
                                               int right,
                                               int bottom) {
        int half = thickness / 2;
        int minX = Math.min(x1, x2) - half;
        int maxX = Math.max(x1, x2) + half + 1;
        int minY = Math.min(y1, y2) - half;
        int maxY = Math.max(y1, y2) + half + 1;
        fillClipped(graphics, minX, minY, maxX, maxY, color, left, top, right, bottom);
    }

    private static void drawSampledLine(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color,
                                        int thickness, int dash, int gap, int left, int top, int right, int bottom) {
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
                    color, thickness, left, top, right, bottom);
        }
    }

    private static void drawPoint(GuiGraphics graphics,
                                  int x,
                                  int y,
                                  int color,
                                  int thickness,
                                  int left,
                                  int top,
                                  int right,
                                  int bottom) {
        int half = thickness / 2;
        fillClipped(graphics, x - half, y - half, x + half + 1, y + half + 1, color, left, top, right, bottom);
    }

    private static void fillClipped(GuiGraphics graphics,
                                    int minX,
                                    int minY,
                                    int maxX,
                                    int maxY,
                                    int color,
                                    int left,
                                    int top,
                                    int right,
                                    int bottom) {
        int clippedLeft = Math.max(minX, left);
        int clippedTop = Math.max(minY, top);
        int clippedRight = Math.min(maxX, right + 1);
        int clippedBottom = Math.min(maxY, bottom + 1);
        if (clippedLeft < clippedRight && clippedTop < clippedBottom) {
            graphics.fill(clippedLeft, clippedTop, clippedRight, clippedBottom, color);
        }
    }

    private RoadPlannerOverlayLineRenderer() {
    }
}
