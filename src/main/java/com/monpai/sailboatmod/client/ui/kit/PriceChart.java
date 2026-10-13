package com.monpai.sailboatmod.client.ui.kit;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * Candlestick or line chart drawn with the game's own GUI renderer.
 *
 * <p>The mouse wheel over the chart zooms it and is consumed here, so it never also scrolls the panel behind it.
 * The zoom level is kept by the element, so data refreshes do not reset what the player was looking at.</p>
 */
public final class PriceChart extends UIElement {
    /** One bucket: candles use all four values, line charts use {@code close} only. */
    public record Point(int open, int high, int low, int close) {
        public static Point line(int value) {
            return new Point(value, value, value, value);
        }
    }

    /** Theme colours as ARGB. */
    public record Palette(int grid, int line, int up, int down, int empty) {
    }

    private static final int MIN_VISIBLE = 6;

    private final boolean candles;
    private List<Point> points = List.of();
    private Palette palette = new Palette(0x22FFFFFF, 0xFF6EA8FF, 0xFF3CCF7E, 0xFFFF6B6B, 0x40FFFFFF);
    private int visible;

    public PriceChart(boolean candles, int defaultVisible) {
        this.candles = candles;
        this.visible = defaultVisible;
        addClass("sm-chart");
        addEventListener(UIEvents.MOUSE_WHEEL, event -> {
            zoom(event.deltaY > 0 ? -2 : 2);
            event.stopPropagation();
        });
    }

    public PriceChart setPoints(List<Point> values) {
        this.points = values == null ? List.of() : new ArrayList<>(values);
        return this;
    }

    public PriceChart setPalette(Palette value) {
        this.palette = value;
        return this;
    }

    public boolean isEmpty() {
        return points.isEmpty();
    }

    /** Positive steps show more buckets (zoom out), negative steps show fewer (zoom in). */
    public void zoom(int steps) {
        int max = Math.max(MIN_VISIBLE, points.size());
        visible = Math.max(MIN_VISIBLE, Math.min(max, visible + steps));
    }


    @Override
    public void drawContents(GUIContext context) {
        super.drawContents(context);
        GuiGraphics g = context.graphics;
        int x0 = Math.round(getContentX());
        int y0 = Math.round(getContentY());
        int w = Math.round(getContentWidth());
        int h = Math.round(getContentHeight());
        if (w < 8 || h < 8) {
            return;
        }
        for (int i = 1; i < 4; i++) {
            int gy = y0 + h * i / 4;
            g.fill(x0, gy, x0 + w, gy + 1, palette.grid());
        }
        List<Point> shown = ChartWindow.newest(points, visible);
        if (shown.isEmpty()) {
            g.fill(x0 + 2, y0 + h / 2, x0 + w - 2, y0 + h / 2 + 1, palette.empty());
            return;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Point p : shown) {
            min = Math.min(min, candles ? p.low() : p.close());
            max = Math.max(max, candles ? p.high() : p.close());
        }
        if (max == min) {
            max = min + 1;
        }
        float step = (float) w / shown.size();
        int previousX = -1;
        int previousY = -1;
        for (int i = 0; i < shown.size(); i++) {
            Point p = shown.get(i);
            int cx = x0 + Math.round(step * i + step / 2f);
            if (candles) {
                int color = p.close() >= p.open() ? palette.up() : palette.down();
                int top = yFor(Math.max(p.open(), p.close()), min, max, y0, h);
                int bottom = yFor(Math.min(p.open(), p.close()), min, max, y0, h);
                int body = Math.max(1, Math.round(step * 0.6f) / 2);
                g.fill(cx, yFor(p.high(), min, max, y0, h), cx + 1, yFor(p.low(), min, max, y0, h) + 1, color);
                g.fill(cx - body, top, cx + body + 1, Math.max(top + 1, bottom + 1), color);
            } else {
                int cy = yFor(p.close(), min, max, y0, h);
                if (previousX >= 0) {
                    drawSegment(g, previousX, previousY, cx, cy, palette.line());
                }
                previousX = cx;
                previousY = cy;
            }
        }
    }

    private static int yFor(int value, int min, int max, int y0, int h) {
        return y0 + h - 2 - Math.round((float) (value - min) / (max - min) * (h - 4));
    }

    private static void drawSegment(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int s = 0; s <= steps; s++) {
            int x = x1 + Math.round((float) (x2 - x1) * s / Math.max(1, steps));
            int y = y1 + Math.round((float) (y2 - y1) * s / Math.max(1, steps));
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
