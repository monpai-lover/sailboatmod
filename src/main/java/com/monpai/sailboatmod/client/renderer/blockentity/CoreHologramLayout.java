package com.monpai.sailboatmod.client.renderer.blockentity;

import java.util.ArrayList;
import java.util.List;

public final class CoreHologramLayout {
    public record HologramLine(String text, int color) {}

    private CoreHologramLayout() {
    }

    public static List<HologramLine> nationLines(String title, String nationName, int primaryColor, boolean activeWar, String warStatus) {
        List<HologramLine> lines = new ArrayList<>();
        addIfPresent(lines, title, 0xFFF3E7C7);
        addIfPresent(lines, nationName, primaryColor);
        if (activeWar) {
            addIfPresent(lines, warStatus, 0xFFE25A4F);
        }
        return List.copyOf(lines);
    }

    public static List<HologramLine> townLines(String title, String townName, int townColor, String nationName) {
        List<HologramLine> lines = new ArrayList<>();
        addIfPresent(lines, title, 0xFFF3E7C7);
        addIfPresent(lines, townName, townColor);
        addIfPresent(lines, nationName, 0xFFC7D5E0);
        return List.copyOf(lines);
    }

    private static void addIfPresent(List<HologramLine> lines, String text, int color) {
        if (text != null && !text.isBlank() && !"-".equals(text.trim())) {
            lines.add(new HologramLine(text, color));
        }
    }
}
