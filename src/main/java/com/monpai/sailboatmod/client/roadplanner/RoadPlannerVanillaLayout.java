package com.monpai.sailboatmod.client.roadplanner;

import java.util.ArrayList;
import java.util.List;

public record RoadPlannerVanillaLayout(Rect toolbar, Rect map, Rect sidebar, List<Rect> bottomButtons) {
    public RoadPlannerVanillaLayout {
        if (toolbar == null || map == null || sidebar == null) {
            throw new IllegalArgumentException("layout rects cannot be null");
        }
        bottomButtons = bottomButtons == null ? List.of() : List.copyOf(bottomButtons);
    }

    public static RoadPlannerVanillaLayout compute(int screenWidth, int screenHeight) {
        int margin = screenWidth < 900 ? 8 : 16;
        int gap = screenWidth < 900 ? 8 : 12;
        int toolbarWidth = clamp(screenWidth / 8, 120, 160);
        int sidebarWidth = clamp(screenWidth / 5, 220, 300);
        int bottomHeight = 34;
        int buttonGap = 6;
        int availableHeight = screenHeight - margin * 2;
        int mapHeight = Math.max(260, availableHeight - bottomHeight - gap);
        int top = margin;
        int toolbarHeight = availableHeight;
        int mapX = margin + toolbarWidth + gap;
        int sidebarX = screenWidth - margin - sidebarWidth;
        int mapWidth = Math.max(360, sidebarX - gap - mapX);
        Rect toolbar = new Rect(margin, top, toolbarWidth, toolbarHeight);
        Rect map = new Rect(mapX, top, mapWidth, mapHeight);
        Rect sidebar = new Rect(sidebarX, top, sidebarWidth, availableHeight);
        List<Rect> buttons = new ArrayList<>();
        int buttonY = map.bottom() + gap;
        int buttonWidth = Math.max(64, (map.width() - buttonGap * 6) / 7);
        for (int index = 0; index < 7; index++) {
            buttons.add(new Rect(map.x() + index * (buttonWidth + buttonGap), buttonY, buttonWidth, bottomHeight));
        }
        return new RoadPlannerVanillaLayout(toolbar, map, sidebar, buttons);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Rect(int x, int y, int width, int height) {
        public int right() {
            return x + width;
        }

        public int bottom() {
            return y + height;
        }

        public boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < right() && mouseY >= y && mouseY < bottom();
        }
    }
}
