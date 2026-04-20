package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SelectRoadPlannerPreviewOptionPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public class RoadPlannerOptionSelectionScreen extends Screen {
    private final String sourceTownName;
    private final String targetTownName;
    private final List<RoadPlannerClientHooks.PreviewOption> options;
    private final String selectedOptionId;
    private int selectedIndex = -1;
    private Button selectButton;

    public RoadPlannerOptionSelectionScreen(String sourceTownName,
                                            String targetTownName,
                                            List<RoadPlannerClientHooks.PreviewOption> options,
                                            String selectedOptionId) {
        super(Component.translatable("screen.sailboatmod.road_planner.option.title"));
        this.sourceTownName = sourceTownName == null ? "-" : sourceTownName;
        this.targetTownName = targetTownName == null ? "-" : targetTownName;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.selectedOptionId = selectedOptionId == null ? "" : selectedOptionId;
    }

    @Override
    protected void init() {
        super.init();
        this.selectedIndex = defaultSelectedIndex();
        this.selectButton = this.addRenderableWidget(Button.builder(
                Component.translatable("screen.sailboatmod.road_planner.option.select"),
                button -> choose()
        ).bounds(this.width / 2 - 45, this.height - 30, 90, 20).build());
        this.selectButton.active = this.selectedIndex >= 0;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFF3E4B0);
        graphics.drawCenteredString(this.font,
                Component.translatable("screen.sailboatmod.road_planner.option.route", sourceTownName, targetTownName),
                this.width / 2, 34, 0xFFE6EEF5);

        int top = 56;
        int left = this.width / 2 - 110;
        int rowHeight = 28;
        for (int i = 0; i < options.size(); i++) {
            RoadPlannerClientHooks.PreviewOption option = options.get(i);
            int y = top + i * (rowHeight + 6);
            boolean selected = i == selectedIndex;
            graphics.fill(left, y, left + 220, y + rowHeight, selected ? 0xCC3A4A59 : 0xCC1D2731);
            graphics.drawString(this.font, option.label(), left + 8, y + 6, 0xFFF1D9A0, false);
            graphics.drawString(this.font,
                    Component.literal(option.pathNodeCount() + " nodes" + (option.bridgeBacked() ? " | bridge" : " | detour")),
                    left + 8, y + 16, 0xFFB7C8D6, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int top = 56;
        int left = this.width / 2 - 110;
        int rowHeight = 28;
        for (int i = 0; i < options.size(); i++) {
            int y = top + i * (rowHeight + 6);
            if (mouseX >= left && mouseX <= left + 220 && mouseY >= y && mouseY <= y + rowHeight) {
                selectedIndex = i;
                if (selectButton != null) {
                    selectButton.active = true;
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void choose() {
        if (selectedIndex < 0 || selectedIndex >= options.size()) {
            return;
        }
        ModNetwork.CHANNEL.sendToServer(new SelectRoadPlannerPreviewOptionPacket(options.get(selectedIndex).optionId()));
        RoadPlannerClientHooks.enterConfigMode();
        net.minecraft.client.Minecraft.getInstance().setScreen(new RoadPlannerConfigScreen());
    }

    private int defaultSelectedIndex() {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).optionId().equalsIgnoreCase(selectedOptionId)) {
                return i;
            }
        }
        return options.isEmpty() ? -1 : 0;
    }
}
