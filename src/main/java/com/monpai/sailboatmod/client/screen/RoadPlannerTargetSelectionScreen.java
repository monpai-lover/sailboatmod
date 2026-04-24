package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SelectRoadPlannerTargetPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

public class RoadPlannerTargetSelectionScreen extends Screen {
    private static final int SCREEN_W = 300;
    private static final int SCREEN_H = 224;
    private static final int ROW_H = 20;
    private static final int VISIBLE_ROWS = 8;

    private final boolean offhand;
    private final String sourceTownName;
    private final List<RoadPlannerClientHooks.TargetEntry> targets;
    private int scroll = 0;
    private int selectedIndex = -1;
    private Button selectButton;

    public RoadPlannerTargetSelectionScreen(boolean offhand,
                                            String sourceTownName,
                                            List<RoadPlannerClientHooks.TargetEntry> targets,
                                            String selectedTownId) {
        super(Component.translatable("screen.sailboatmod.road_planner.target.title"));
        this.offhand = offhand;
        this.sourceTownName = sourceTownName == null ? "" : sourceTownName;
        this.targets = targets == null ? List.of() : targets;
        for (int i = 0; i < this.targets.size(); i++) {
            if (this.targets.get(i).townId().equalsIgnoreCase(selectedTownId == null ? "" : selectedTownId)) {
                this.selectedIndex = i;
                break;
            }
        }
    }

    @Override
    protected void init() {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        this.selectButton = this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.road_planner.target.select"), b -> choose())
                .bounds(left + 14, top + SCREEN_H - 28, 96, 18).build());
        this.selectButton.active = this.selectedIndex >= 0;
        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_name.cancel"), b -> onClose())
                .bounds(left + SCREEN_W - 74, top + SCREEN_H - 28, 60, 18).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        guiGraphics.fill(left, top, left + SCREEN_W, top + SCREEN_H, 0xD1141920);
        guiGraphics.fill(left + 1, top + 1, left + SCREEN_W - 1, top + SCREEN_H - 1, 0xEE202933);
        guiGraphics.drawString(this.font, this.title, left + 14, top + 10, 0xFFF2DEAF);
        guiGraphics.drawString(this.font,
                Component.translatable("screen.sailboatmod.road_planner.target.source", safeTownName(sourceTownName)),
                left + 14, top + 24, 0xFFB9CAD9);
        guiGraphics.drawString(this.font,
                Component.translatable("screen.sailboatmod.road_planner.target.hint"),
                left + 14, top + 36, 0xFF8FA4B5);

        int listTop = top + 48;
        guiGraphics.fill(left + 14, listTop, left + SCREEN_W - 14, listTop + VISIBLE_ROWS * ROW_H, 0x55000000);
        if (targets.isEmpty()) {
            guiGraphics.drawCenteredString(this.font,
                    Component.translatable("screen.sailboatmod.road_planner.target.empty"),
                    left + SCREEN_W / 2, listTop + 68, 0xFF9EACB7);
        } else {
            for (int row = 0; row < VISIBLE_ROWS && (scroll + row) < targets.size(); row++) {
                int index = scroll + row;
                int rowY = listTop + row * ROW_H;
                RoadPlannerClientHooks.TargetEntry entry = targets.get(index);
                if (index == selectedIndex) {
                    guiGraphics.fill(left + 14, rowY, left + SCREEN_W - 14, rowY + ROW_H, 0x44E4C46B);
                }
                guiGraphics.drawString(this.font,
                        Component.translatable("screen.sailboatmod.road_planner.target.entry",
                                safeTownName(entry.townName()),
                                entry.distanceBlocks()),
                        left + 18, rowY + 6, 0xFFE5EEF5);
            }
        }
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        int listTop = top + 48;
        if (mouseX >= left + 14 && mouseX < left + SCREEN_W - 14 && mouseY >= listTop && mouseY < listTop + VISIBLE_ROWS * ROW_H) {
            int row = (int) ((mouseY - listTop) / ROW_H);
            int index = scroll + row;
            if (index >= 0 && index < targets.size()) {
                selectedIndex = index;
                if (selectButton != null) {
                    selectButton.active = true;
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        int listTop = top + 48;
        if (mouseX < left + 14 || mouseX >= left + SCREEN_W - 14 || mouseY < listTop || mouseY >= listTop + VISIBLE_ROWS * ROW_H) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        scroll = Mth.clamp(scroll - (int) delta, 0, Math.max(0, targets.size() - VISIBLE_ROWS));
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void choose() {
        if (selectedIndex < 0 || selectedIndex >= targets.size()) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new SelectRoadPlannerTargetPacket(offhand, targets.get(selectedIndex).townId()));
        onClose();
    }

    private Component safeTownName(String name) {
        if (name == null || name.isBlank()) {
            return Component.translatable("screen.sailboatmod.road_planner.target.unknown");
        }
        return Component.literal(name);
    }
}
