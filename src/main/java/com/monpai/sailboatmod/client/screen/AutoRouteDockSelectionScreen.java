package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CreateAutoRoutePacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

public class AutoRouteDockSelectionScreen extends Screen {
    private static final int SCREEN_W = 320;
    private static final int SCREEN_H = 240;
    private static final int ROW_H = 20;
    private static final int VISIBLE_ROWS = 8;

    private final BlockPos sourceDockPos;
    private final TransportTerminalKind terminalKind;
    private final List<AvailableDockEntry> docks;
    private Button createButton;
    private int scroll = 0;
    private int selectedIndex = -1;
    private Component statusLine = Component.empty();

    public AutoRouteDockSelectionScreen(BlockPos sourceDockPos, TransportTerminalKind terminalKind, List<AvailableDockEntry> docks) {
        super(Component.translatable(baseKey(terminalKind) + ".title"));
        this.sourceDockPos = sourceDockPos;
        this.terminalKind = terminalKind == null ? TransportTerminalKind.PORT : terminalKind;
        this.docks = docks;
    }

    @Override
    protected void init() {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;

        this.createButton = this.addRenderableWidget(Button.builder(autoRouteText("create"), b -> createRoute())
            .bounds(left + 12, top + SCREEN_H - 30, 120, 18).build());
        this.createButton.active = !this.docks.isEmpty();

        this.addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.route_name.cancel"), b -> onClose())
            .bounds(left + SCREEN_W - 72, top + SCREEN_H - 30, 60, 18).build());
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(g);
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;

        g.fill(left, top, left + SCREEN_W, top + SCREEN_H, 0xCC101820);
        g.fill(left + 1, top + 1, left + SCREEN_W - 1, top + SCREEN_H - 1, 0xCC182632);

        g.drawString(this.font, this.title, left + 12, top + 10, 0xFFE7C977);
        g.drawString(this.font, autoRouteText("subtitle"), left + 12, top + 20, 0xFF8D98A3);
        g.drawString(this.font, autoRouteText("columns"), left + 12, top + 32, 0xFFB8C0C8);

        int listY = top + 42;
        g.fill(left + 12, listY, left + SCREEN_W - 12, listY + VISIBLE_ROWS * ROW_H, 0x44000000);

        if (docks.isEmpty()) {
            g.drawCenteredString(this.font, autoRouteText("empty"),
                    left + SCREEN_W / 2, listY + 72, 0xFF8D98A3);
        } else {
            for (int i = 0; i < VISIBLE_ROWS && (scroll + i) < docks.size(); i++) {
                int idx = scroll + i;
                AvailableDockEntry dock = docks.get(idx);
                int rowY = listY + i * ROW_H;

                if (idx == selectedIndex) {
                    g.fill(left + 12, rowY, left + SCREEN_W - 12, rowY + ROW_H, 0x44FFFFFF);
                }

                g.drawString(this.font, rowText(dock), left + 16, rowY + 6, 0xFFDCEEFF);
            }
        }
        if (!this.statusLine.getString().isBlank()) {
            g.drawString(this.font, this.statusLine, left + 12, top + SCREEN_H - 54, 0xFFF1D98A);
        } else {
            g.drawString(this.font, autoRouteText("hint"),
                    left + 12, top + SCREEN_H - 54, 0xFF8D98A3);
        }

        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int left = (this.width - SCREEN_W) / 2;
        int top = (this.height - SCREEN_H) / 2;
        int listY = top + 42;

        if (mouseX >= left + 12 && mouseX < left + SCREEN_W - 12 && mouseY >= listY && mouseY < listY + VISIBLE_ROWS * ROW_H) {
            int row = (int) ((mouseY - listY) / ROW_H);
            int idx = scroll + row;
            if (idx < docks.size()) {
                selectedIndex = idx;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        scroll = Mth.clamp(scroll - (int) delta, 0, Math.max(0, docks.size() - VISIBLE_ROWS));
        return true;
    }

    private void createRoute() {
        if (selectedIndex >= 0 && selectedIndex < docks.size()) {
            ModNetwork.CHANNEL.sendToServer(new CreateAutoRoutePacket(sourceDockPos, docks.get(selectedIndex).pos()));
            onClose();
            return;
        }
        this.statusLine = autoRouteText("select_target");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component rowText(AvailableDockEntry dock) {
        return Component.translatable(
                autoRouteKeyPrefix() + ".entry",
                dockName(dock.dockName()),
                fallback(dock.ownerName(), "screen.sailboatmod.auto_route.unknown_owner"),
                fallback(dock.nationName(), "screen.sailboatmod.auto_route.no_nation"),
                dock.distance()
        );
    }

    private Component dockName(String name) {
        if (name == null || name.isBlank()) {
            return Component.translatable(terminalKind == TransportTerminalKind.POST_STATION
                    ? "block.sailboatmod.post_station"
                    : "block.sailboatmod.dock");
        }
        return Component.literal(name);
    }

    private Component autoRouteText(String suffix, Object... args) {
        return Component.translatable(autoRouteKeyPrefix() + "." + suffix, args);
    }

    private String autoRouteKeyPrefix() {
        return baseKey(terminalKind);
    }

    private static String baseKey(TransportTerminalKind terminalKind) {
        return terminalKind == TransportTerminalKind.POST_STATION
                ? "screen.sailboatmod.auto_route.post_station"
                : "screen.sailboatmod.auto_route.port";
    }

    private Component fallback(String value, String key) {
        if (value == null || value.isBlank()) {
            return Component.translatable(key);
        }
        return Component.literal(value);
    }
}
