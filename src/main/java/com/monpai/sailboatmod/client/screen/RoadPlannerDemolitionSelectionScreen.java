package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadDemolitionSelectionPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerSelectDemolitionRoadPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class RoadPlannerDemolitionSelectionScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private final List<OpenRoadDemolitionSelectionPacket.Entry> roads;
    private int scrollOffset;

    public RoadPlannerDemolitionSelectionScreen(List<OpenRoadDemolitionSelectionPacket.Entry> roads) {
        super(Component.literal("Road demolition"));
        this.roads = roads == null ? List.of() : List.copyOf(roads);
    }

    @Override
    protected void init() {
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        int listWidth = Math.min(360, width - 32);
        int left = width / 2 - listWidth / 2;
        int top = Math.max(42, height / 2 - 96);
        int visibleRows = visibleRows(top);
        int end = Math.min(roads.size(), scrollOffset + visibleRows);
        for (int index = scrollOffset; index < end; index++) {
            OpenRoadDemolitionSelectionPacket.Entry entry = roads.get(index);
            int y = top + (index - scrollOffset) * ROW_HEIGHT;
            addRenderableWidget(Button.builder(Component.literal(buttonLabel(entry)), button -> selectRoad(entry))
                    .bounds(left, y, listWidth, 20)
                    .build());
        }
        int footerY = top + visibleRows * ROW_HEIGHT + 8;
        if (scrollOffset > 0) {
            addRenderableWidget(Button.builder(Component.literal("Prev"), button -> {
                scrollOffset = Math.max(0, scrollOffset - visibleRows);
                rebuildButtons();
            }).bounds(left, footerY, 70, 20).build());
        }
        if (end < roads.size()) {
            addRenderableWidget(Button.builder(Component.literal("Next"), button -> {
                scrollOffset = Math.min(Math.max(0, roads.size() - 1), scrollOffset + visibleRows);
                rebuildButtons();
            }).bounds(left + listWidth - 70, footerY, 70, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(width / 2 - 45, footerY + 24, 90, 20)
                .build());
    }

    private int visibleRows(int top) {
        return Math.max(1, Math.min(8, (height - top - 68) / ROW_HEIGHT));
    }

    private void selectRoad(OpenRoadDemolitionSelectionPacket.Entry entry) {
        if (entry == null || entry.roadId().isBlank()) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new RoadPlannerSelectDemolitionRoadPacket(entry.roadId()));
        onClose();
    }

    private static String buttonLabel(OpenRoadDemolitionSelectionPacket.Entry entry) {
        if (entry == null) {
            return "-";
        }
        return entry.sourceName() + " -> " + entry.targetName()
                + "  " + entry.lengthBlocks() + "m  " + entry.sourceType();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (roads.isEmpty()) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        int previous = scrollOffset;
        scrollOffset = Math.max(0, Math.min(Math.max(0, roads.size() - 1), scrollOffset + (delta < 0 ? 1 : -1)));
        if (previous != scrollOffset) {
            rebuildButtons();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, Component.literal("Select a built road to demolish"), width / 2, 20, 0xFFF1D9A0);
        if (roads.isEmpty()) {
            graphics.drawCenteredString(font, Component.literal("No built roads available"), width / 2, height / 2 - 8, 0xFFB7C8D6);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
