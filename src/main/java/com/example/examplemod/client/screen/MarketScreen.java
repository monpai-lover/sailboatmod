package com.example.examplemod.client.screen;

import com.example.examplemod.client.MarketClientHooks;
import com.example.examplemod.market.MarketOverviewData;
import com.example.examplemod.menu.MarketMenu;
import com.example.examplemod.network.ModNetwork;
import com.example.examplemod.network.packet.CancelMarketListingPacket;
import com.example.examplemod.network.packet.ClaimMarketCreditsPacket;
import com.example.examplemod.network.packet.CreateMarketListingPacket;
import com.example.examplemod.network.packet.DispatchMarketOrderPacket;
import com.example.examplemod.network.packet.MarketGuiActionPacket;
import com.example.examplemod.network.packet.PurchaseMarketListingPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

public class MarketScreen extends AbstractContainerScreen<MarketMenu> {
    private static final int PANEL_W = 118;
    private static final int PANEL_H = 100;
    private static final int PANEL_CONTENT_Y = 22;
    private static final int PANEL_ROW_H = 10;
    private static final int PANEL_VISIBLE_ROWS = 7;

    private MarketOverviewData data;
    private EditBox listingQtyInput;
    private EditBox listingPriceInput;
    private EditBox buyQtyInput;
    private int selectedListingIndex = 0;
    private int selectedOrderIndex = 0;
    private int selectedBoatIndex = 0;
    private int listingScroll = 0;
    private int orderScroll = 0;
    private int boatScroll = 0;
    private Button listButton;
    private Button buyButton;
    private Button dispatchButton;
    private Button cancelButton;
    private Button claimButton;
    private String hoveredLine;

    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 360;
        this.imageHeight = 176;
        MarketOverviewData initial = MarketClientHooks.consumeFor(menu.getMarketPos());
        this.data = initial != null ? initial : empty(menu.getMarketPos());
    }

    public boolean isForMarket(net.minecraft.core.BlockPos pos) {
        return data.marketPos().equals(pos);
    }

    public void updateData(MarketOverviewData updated) {
        this.data = updated;
        selectedListingIndex = clampSelection(selectedListingIndex, data.listingLines());
        selectedOrderIndex = clampSelection(selectedOrderIndex, data.orderLines());
        selectedBoatIndex = clampSelection(selectedBoatIndex, data.shippingLines());
        listingScroll = clampScroll(listingScroll, data.listingLines());
        orderScroll = clampScroll(orderScroll, data.orderLines());
        boatScroll = clampScroll(boatScroll, data.shippingLines());
        ensureSelectionVisible();
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.refresh"), b ->
                send(MarketGuiActionPacket.Action.REFRESH)).bounds(leftPos + 262, topPos + 10, 90, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.bind_dock"), b ->
                send(MarketGuiActionPacket.Action.BIND_NEAREST_DOCK)).bounds(leftPos + 262, topPos + 36, 90, 20).build());
        claimButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.claim"), b ->
                ModNetwork.CHANNEL.sendToServer(new ClaimMarketCreditsPacket(data.marketPos()))).bounds(leftPos + 262, topPos + 62, 90, 16).build());
        listingQtyInput = addRenderableWidget(new EditBox(font, leftPos + 136, topPos + 8, 26, 16, Component.literal("Qty")));
        listingQtyInput.setValue("1");
        listingQtyInput.setMaxLength(3);
        listingPriceInput = addRenderableWidget(new EditBox(font, leftPos + 170, topPos + 8, 44, 16, Component.literal("Price")));
        listingPriceInput.setValue("10");
        listingPriceInput.setMaxLength(7);
        buyQtyInput = addRenderableWidget(new EditBox(font, leftPos + 136, topPos + 32, 26, 16, Component.literal("Buy Qty")));
        buyQtyInput.setValue("1");
        buyQtyInput.setMaxLength(3);
        listButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.list_hand"), b ->
                ModNetwork.CHANNEL.sendToServer(new CreateMarketListingPacket(
                        data.marketPos(),
                        parsePositive(listingQtyInput.getValue(), 1),
                        parsePositive(listingPriceInput.getValue(), 10)
                ))).bounds(leftPos + 220, topPos + 8, 34, 16).build());
        buyButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.buy"), b ->
                ModNetwork.CHANNEL.sendToServer(new PurchaseMarketListingPacket(
                        data.marketPos(),
                        selectedListingIndex,
                        parsePositive(buyQtyInput.getValue(), 1)
                ))).bounds(leftPos + 170, topPos + 32, 40, 16).build());
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.cancel"), b ->
                ModNetwork.CHANNEL.sendToServer(new CancelMarketListingPacket(data.marketPos(), selectedListingIndex)))
                .bounds(leftPos + 214, topPos + 8, 40, 16).build());
        dispatchButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.dispatch"), b ->
                ModNetwork.CHANNEL.sendToServer(new DispatchMarketOrderPacket(
                        data.marketPos(),
                        selectedOrderIndex,
                        selectedBoatIndex
                ))).bounds(leftPos + 220, topPos + 32, 34, 16).build());
        send(MarketGuiActionPacket.Action.REFRESH);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        updateButtonState();
        hoveredLine = null;
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xCC111A20);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xCC1B2A33);

        guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.market.title"), leftPos + 10, topPos + 10, 0xFFE7C977);
        guiGraphics.drawString(font, Component.literal(data.marketName()), leftPos + 10, topPos + 24, 0xFFE0F0FF);
        guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.owner_full", data.ownerName(), data.ownerUuid()), leftPos + 10, topPos + 36, 0xFFA7D7F3);
        guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.market.pending", data.pendingCredits()), leftPos + 10, topPos + 12, 0xFFD4E8C4);
        guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.market.qty"), leftPos + 102, topPos + 12, 0xFFE7C977);
        guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.market.price"), leftPos + 166, topPos + 12, 0xFFE7C977);
        guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.market.buy_qty"), leftPos + 98, topPos + 36, 0xFFE7C977);

        String dockLine = data.linkedDock()
                ? Component.translatable("screen.sailboatmod.market.linked_dock_value", data.linkedDockName(), data.linkedDockPosText()).getString()
                : Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString();
        guiGraphics.drawString(font, Component.literal(trimToWidth(dockLine, 230)), leftPos + 10, topPos + 50, 0xFFD4E8C4);

        drawPanel(guiGraphics, leftPos + 10, topPos + 68, Component.translatable("screen.sailboatmod.market.tab.goods"), data.listingLines(), selectedListingIndex, listingScroll, mouseX, mouseY);
        drawPanel(guiGraphics, leftPos + 126, topPos + 68, Component.translatable("screen.sailboatmod.market.tab.orders"), data.orderLines(), selectedOrderIndex, orderScroll, mouseX, mouseY);
        drawPanel(guiGraphics, leftPos + 242, topPos + 68, Component.translatable("screen.sailboatmod.market.tab.shipping"), data.shippingLines(), selectedBoatIndex, boatScroll, mouseX, mouseY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        renderTooltip(guiGraphics, mouseX, mouseY);
        if (hoveredLine != null && !hoveredLine.isBlank()) {
            guiGraphics.renderTooltip(this.font, Component.literal(hoveredLine), mouseX, mouseY);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && trySelectFromPanel(mouseX, mouseY, leftPos + 10, topPos + 68, data.listingLines(), 0, listingScroll)) {
            return true;
        }
        if (button == 0 && trySelectFromPanel(mouseX, mouseY, leftPos + 126, topPos + 68, data.orderLines(), 1, orderScroll)) {
            return true;
        }
        if (button == 0 && trySelectFromPanel(mouseX, mouseY, leftPos + 242, topPos + 68, data.shippingLines(), 2, boatScroll)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int direction = delta > 0 ? -1 : 1;
        if (isInsidePanel(mouseX, mouseY, leftPos + 10, topPos + 68, data.listingLines())) {
            listingScroll = clampScroll(listingScroll + direction, data.listingLines());
            return true;
        }
        if (isInsidePanel(mouseX, mouseY, leftPos + 126, topPos + 68, data.orderLines())) {
            orderScroll = clampScroll(orderScroll + direction, data.orderLines());
            return true;
        }
        if (isInsidePanel(mouseX, mouseY, leftPos + 242, topPos + 68, data.shippingLines())) {
            boatScroll = clampScroll(boatScroll + direction, data.shippingLines());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void drawPanel(GuiGraphics g, int x, int y, Component title, List<String> lines, int selectedIndex, int scroll, int mouseX, int mouseY) {
        g.fill(x, y, x + PANEL_W, y + PANEL_H, 0x66203037);
        g.fill(x + 1, y + 1, x + PANEL_W - 1, y + PANEL_H - 1, 0x66131C23);
        g.drawString(font, title, x + 6, y + 6, 0xFFE7C977);
        if (lines == null || lines.isEmpty()) {
            g.drawString(font, Component.translatable("screen.sailboatmod.market.empty"), x + 6, y + 22, 0xFF8D98A3);
            return;
        }
        int maxScroll = Math.max(0, lines.size() - PANEL_VISIBLE_ROWS);
        int safeScroll = Math.max(0, Math.min(scroll, maxScroll));
        int start = safeScroll;
        int end = Math.min(lines.size(), start + PANEL_VISIBLE_ROWS);
        int drawY = y + PANEL_CONTENT_Y;
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            if (i == selectedIndex) {
                g.fill(x + 3, drawY - 1, x + PANEL_W - 3, drawY + 8, 0x55457A9A);
            }
            g.drawString(font, Component.literal(trimToWidth(line, PANEL_W - 12)), x + 6, drawY, 0xFFE0E0E0);
            if (mouseX >= x + 3 && mouseX < x + PANEL_W - 3 && mouseY >= drawY - 1 && mouseY < drawY + 9) {
                hoveredLine = line;
            }
            drawY += PANEL_ROW_H;
        }
        if (maxScroll > 0) {
            String pageLine = (safeScroll + 1) + "/" + (maxScroll + 1);
            g.drawString(font, Component.literal(pageLine), x + PANEL_W - 26, y + PANEL_H - 10, 0xFF8D98A3);
        }
    }

    private void send(MarketGuiActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new MarketGuiActionPacket(data.marketPos(), action));
    }

    private boolean trySelectFromPanel(double mouseX, double mouseY, int x, int y, List<String> lines, int panelIndex, int scroll) {
        if (lines == null || lines.isEmpty()) {
            return false;
        }
        if (mouseX < x || mouseX >= x + PANEL_W || mouseY < y + 20 || mouseY >= y + PANEL_H) {
            return false;
        }
        int row = (int) ((mouseY - (y + PANEL_CONTENT_Y)) / PANEL_ROW_H);
        int idx = scroll + row;
        if (row < 0 || row >= PANEL_VISIBLE_ROWS || idx < 0 || idx >= lines.size()) {
            return false;
        }
        if (panelIndex == 0) {
            selectedListingIndex = idx;
        } else if (panelIndex == 1) {
            selectedOrderIndex = idx;
        } else {
            selectedBoatIndex = idx;
        }
        ensureSelectionVisible();
        return true;
    }

    private int clampSelection(int current, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(current, lines.size() - 1));
    }

    private int clampScroll(int current, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(current, Math.max(0, lines.size() - PANEL_VISIBLE_ROWS)));
    }

    private void ensureSelectionVisible() {
        listingScroll = ensureVisible(listingScroll, selectedListingIndex, data.listingLines());
        orderScroll = ensureVisible(orderScroll, selectedOrderIndex, data.orderLines());
        boatScroll = ensureVisible(boatScroll, selectedBoatIndex, data.shippingLines());
    }

    private int ensureVisible(int scroll, int selectedIndex, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        int clampedScroll = clampScroll(scroll, lines);
        if (selectedIndex < clampedScroll) {
            return selectedIndex;
        }
        if (selectedIndex >= clampedScroll + PANEL_VISIBLE_ROWS) {
            return selectedIndex - PANEL_VISIBLE_ROWS + 1;
        }
        return clampedScroll;
    }

    private boolean isInsidePanel(double mouseX, double mouseY, int x, int y, List<String> lines) {
        return lines != null && !lines.isEmpty()
                && mouseX >= x && mouseX < x + PANEL_W
                && mouseY >= y + 20 && mouseY < y + PANEL_H;
    }

    private int parsePositive(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void updateButtonState() {
        if (listButton != null) {
            listButton.active = data.linkedDock();
        }
        if (cancelButton != null) {
            cancelButton.active = data.linkedDock() && !data.listingLines().isEmpty();
        }
        if (buyButton != null) {
            buyButton.active = data.linkedDock() && !data.listingLines().isEmpty();
        }
        if (dispatchButton != null) {
            dispatchButton.active = data.linkedDock() && !data.orderLines().isEmpty() && !data.shippingLines().isEmpty();
        }
        if (claimButton != null) {
            claimButton.active = data.pendingCredits() > 0;
        }
    }

    private String trimToWidth(String src, int maxPixels) {
        if (src == null || src.isEmpty()) {
            return "";
        }
        if (maxPixels <= 0 || font.width(src) <= maxPixels) {
            return src;
        }
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        int end = src.length();
        while (end > 0 && font.width(src.substring(0, end)) + ellipsisWidth > maxPixels) {
            end--;
        }
        return src.substring(0, Math.max(0, end)) + ellipsis;
    }

    private MarketOverviewData empty(net.minecraft.core.BlockPos pos) {
        return new MarketOverviewData(pos, "Market", "-", "", 0, false, "-", "-", List.of(), List.of(), List.of());
    }
}
