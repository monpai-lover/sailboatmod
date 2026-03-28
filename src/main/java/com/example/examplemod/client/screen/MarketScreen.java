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
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;

public class MarketScreen extends AbstractContainerScreen<MarketMenu> {
    private static final int PAGE_GOODS = 0;
    private static final int PAGE_SELL = 1;
    private static final int PAGE_DISPATCH = 2;
    private static final int PAGE_FINANCE = 3;

    private static final int PANEL_Y = 96;
    private static final int MAIN_LIST_X = 10;
    private static final int MAIN_LIST_W = 224;
    private static final int DETAIL_X = 242;
    private static final int DETAIL_W = 108;
    private static final int DISPATCH_LIST_W = 164;
    private static final int DISPATCH_RIGHT_X = 186;
    private static final int PANEL_H = 90;
    private static final int ROW_H = 10;
    private static final int LIST_CONTENT_Y = 22;
    private static final int VISIBLE_ROWS = 6;

    private MarketOverviewData data;
    private int activePage = PAGE_GOODS;
    private int selectedListingIndex = 0;
    private int selectedOrderIndex = 0;
    private int selectedBoatIndex = 0;
    private int selectedStorageIndex = 0;
    private int listingScroll = 0;
    private int orderScroll = 0;
    private int boatScroll = 0;
    private int storageScroll = 0;
    private String hoveredLine;

    private EditBox listingQtyInput;
    private EditBox listingPriceInput;
    private EditBox buyQtyInput;

    private Button listButton;
    private Button buyButton;
    private Button cancelButton;
    private Button dispatchButton;
    private Button claimButton;
    private Button goodsTabButton;
    private Button sellTabButton;
    private Button dispatchTabButton;
    private Button financeTabButton;

    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 360;
        this.imageHeight = 196;
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
        selectedStorageIndex = clampSelection(selectedStorageIndex, data.dockStorageLines());
        listingScroll = clampScroll(listingScroll, data.listingLines());
        orderScroll = clampScroll(orderScroll, data.orderLines());
        boatScroll = clampScroll(boatScroll, data.shippingLines());
        storageScroll = clampScroll(storageScroll, data.dockStorageLines());
        ensureSelectionVisible();
    }

    @Override
    protected void init() {
        super.init();
        goodsTabButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.page.goods"), b -> activePage = PAGE_GOODS)
                .bounds(leftPos + 10, topPos + 54, 78, 16).build());
        sellTabButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.page.sell"), b -> activePage = PAGE_SELL)
                .bounds(leftPos + 92, topPos + 54, 78, 16).build());
        dispatchTabButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.page.dispatch"), b -> activePage = PAGE_DISPATCH)
                .bounds(leftPos + 174, topPos + 54, 86, 16).build());
        financeTabButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.page.finance"), b -> activePage = PAGE_FINANCE)
                .bounds(leftPos + 264, topPos + 54, 86, 16).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.refresh"), b ->
                send(MarketGuiActionPacket.Action.REFRESH)).bounds(leftPos + 262, topPos + 10, 90, 16).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.bind_dock"), b ->
                send(MarketGuiActionPacket.Action.BIND_NEAREST_DOCK)).bounds(leftPos + 262, topPos + 28, 90, 16).build());

        listingQtyInput = addRenderableWidget(new EditBox(font, leftPos + 126, topPos + 74, 34, 16, Component.literal("Qty")));
        listingQtyInput.setValue("1");
        listingQtyInput.setMaxLength(3);
        listingPriceInput = addRenderableWidget(new EditBox(font, leftPos + 192, topPos + 74, 46, 16, Component.literal("Price")));
        listingPriceInput.setValue("10");
        listingPriceInput.setMaxLength(7);
        buyQtyInput = addRenderableWidget(new EditBox(font, leftPos + 126, topPos + 74, 34, 16, Component.literal("Buy Qty")));
        buyQtyInput.setValue("1");
        buyQtyInput.setMaxLength(3);

        listButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.list_hand"), b ->
                ModNetwork.CHANNEL.sendToServer(new CreateMarketListingPacket(
                        data.marketPos(),
                        selectedStorageIndex,
                        parsePositive(listingQtyInput.getValue(), 1),
                        parsePositive(listingPriceInput.getValue(), 10)
                ))).bounds(leftPos + 244, topPos + 74, 50, 16).build());
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.cancel"), b ->
                ModNetwork.CHANNEL.sendToServer(new CancelMarketListingPacket(data.marketPos(), selectedListingIndex)))
                .bounds(leftPos + 300, topPos + 74, 50, 16).build());
        buyButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.buy"), b ->
                ModNetwork.CHANNEL.sendToServer(new PurchaseMarketListingPacket(
                        data.marketPos(),
                        selectedListingIndex,
                        parsePositive(buyQtyInput.getValue(), 1)
                ))).bounds(leftPos + 168, topPos + 74, 56, 16).build());
        dispatchButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.dispatch"), b ->
                ModNetwork.CHANNEL.sendToServer(new DispatchMarketOrderPacket(
                        data.marketPos(),
                        selectedOrderIndex,
                        selectedBoatIndex
                ))).bounds(leftPos + 292, topPos + 74, 58, 16).build());
        claimButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.claim"), b ->
                ModNetwork.CHANNEL.sendToServer(new ClaimMarketCreditsPacket(data.marketPos())))
                .bounds(leftPos + 286, topPos + 74, 64, 16).build());

        send(MarketGuiActionPacket.Action.REFRESH);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        hoveredLine = null;
        updateControlVisibility();
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xCC6E4B2A);
        guiGraphics.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, 0xCC483322);

        guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.market.title"), leftPos + 10, topPos + 10, 0xFFF0CD87);
        guiGraphics.drawString(font, Component.literal(data.marketName()), leftPos + 10, topPos + 22, 0xFFE9E4D7);
        guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.owner_name", data.ownerName()), leftPos + 10, topPos + 34, 0xFFB7E7DE);
        guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.market.pending", data.pendingCredits()), leftPos + 126, topPos + 10, 0xFFB7E7DE);
        String dockLine = data.linkedDock()
                ? Component.translatable("screen.sailboatmod.market.linked_dock_value", data.linkedDockName(), data.linkedDockPosText()).getString()
                : Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString();
        guiGraphics.drawString(font, Component.literal(trimToWidth(dockLine, 238)), leftPos + 126, topPos + 22, 0xFFB7E7DE);
        guiGraphics.drawString(font, Component.translatable(pageTitleKey()), leftPos + 10, topPos + 76, 0xFFF0CD87);

        if (activePage == PAGE_GOODS) {
            guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.market.buy_qty"), leftPos + 98, topPos + 78, 0xFFF0CD87);
            drawSingleListPage(guiGraphics, mouseX, mouseY, Component.translatable("screen.sailboatmod.market.tab.goods"),
                    data.listingLines(), selectedListingIndex, listingScroll,
                    selectedListingIndex < data.listingLines().size() ? data.listingLines().get(selectedListingIndex) : Component.translatable("screen.sailboatmod.market.empty").getString());
        } else if (activePage == PAGE_SELL) {
            guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.market.qty"), leftPos + 98, topPos + 78, 0xFFF0CD87);
            guiGraphics.drawString(font, Component.translatable("screen.sailboatmod.market.price"), leftPos + 166, topPos + 78, 0xFFF0CD87);
            String detailText = !data.dockStorageAccessible()
                    ? Component.translatable("screen.sailboatmod.market.storage_owner_only").getString()
                    : selectedStorageIndex < data.dockStorageLines().size()
                    ? data.dockStorageLines().get(selectedStorageIndex)
                    : Component.translatable("screen.sailboatmod.market.storage_empty").getString();
            drawSingleListPage(guiGraphics, mouseX, mouseY, Component.translatable("screen.sailboatmod.market.storage_title"),
                    data.dockStorageLines(), selectedStorageIndex, storageScroll,
                    detailText + "\n" + Component.translatable("screen.sailboatmod.market.sell_help").getString());
        } else if (activePage == PAGE_DISPATCH) {
            drawListPanel(guiGraphics, leftPos + MAIN_LIST_X, topPos + PANEL_Y, DISPATCH_LIST_W, PANEL_H,
                    Component.translatable("screen.sailboatmod.market.tab.orders"), data.orderLines(), selectedOrderIndex, orderScroll, mouseX, mouseY);
            drawListPanel(guiGraphics, leftPos + DISPATCH_RIGHT_X, topPos + PANEL_Y, DISPATCH_LIST_W, PANEL_H,
                    Component.translatable("screen.sailboatmod.market.tab.shipping"), data.shippingLines(), selectedBoatIndex, boatScroll, mouseX, mouseY);
        } else {
            drawDetailPanel(guiGraphics, leftPos + MAIN_LIST_X, topPos + PANEL_Y, 340, PANEL_H,
                    Component.translatable("screen.sailboatmod.market.page.finance"),
                    List.of(
                            Component.translatable("screen.sailboatmod.market.pending", data.pendingCredits()).getString(),
                            Component.translatable("screen.sailboatmod.market.finance_help").getString(),
                            data.linkedDock()
                                    ? Component.translatable("screen.sailboatmod.market.linked_dock_value", data.linkedDockName(), data.linkedDockPosText()).getString()
                                    : Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString()
                    ));
        }
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
        if (button == 0) {
            if (activePage == PAGE_GOODS
                    && trySelectFromList(mouseX, mouseY, leftPos + MAIN_LIST_X, topPos + PANEL_Y, MAIN_LIST_W, data.listingLines(), 0, listingScroll)) {
                return true;
            }
            if (activePage == PAGE_SELL
                    && trySelectFromList(mouseX, mouseY, leftPos + MAIN_LIST_X, topPos + PANEL_Y, MAIN_LIST_W, data.dockStorageLines(), 3, storageScroll)) {
                return true;
            }
            if (activePage == PAGE_DISPATCH) {
                if (trySelectFromList(mouseX, mouseY, leftPos + MAIN_LIST_X, topPos + PANEL_Y, DISPATCH_LIST_W, data.orderLines(), 1, orderScroll)) {
                    return true;
                }
                if (trySelectFromList(mouseX, mouseY, leftPos + DISPATCH_RIGHT_X, topPos + PANEL_Y, DISPATCH_LIST_W, data.shippingLines(), 2, boatScroll)) {
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int direction = delta > 0 ? -1 : 1;
        if (activePage == PAGE_GOODS
                && isInsideList(mouseX, mouseY, leftPos + MAIN_LIST_X, topPos + PANEL_Y, MAIN_LIST_W)) {
            listingScroll = clampScroll(listingScroll + direction, data.listingLines());
            return true;
        }
        if (activePage == PAGE_SELL
                && isInsideList(mouseX, mouseY, leftPos + MAIN_LIST_X, topPos + PANEL_Y, MAIN_LIST_W)) {
            storageScroll = clampScroll(storageScroll + direction, data.dockStorageLines());
            return true;
        }
        if (activePage == PAGE_DISPATCH) {
            if (isInsideList(mouseX, mouseY, leftPos + MAIN_LIST_X, topPos + PANEL_Y, DISPATCH_LIST_W)) {
                orderScroll = clampScroll(orderScroll + direction, data.orderLines());
                return true;
            }
            if (isInsideList(mouseX, mouseY, leftPos + DISPATCH_RIGHT_X, topPos + PANEL_Y, DISPATCH_LIST_W)) {
                boatScroll = clampScroll(boatScroll + direction, data.shippingLines());
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private void drawSingleListPage(GuiGraphics g, int mouseX, int mouseY, Component listTitle,
                                    List<String> lines, int selectedIndex, int scroll, String detailText) {
        drawListPanel(g, leftPos + MAIN_LIST_X, topPos + PANEL_Y, MAIN_LIST_W, PANEL_H, listTitle, lines, selectedIndex, scroll, mouseX, mouseY);
        drawDetailPanel(g, leftPos + DETAIL_X, topPos + PANEL_Y, DETAIL_W, PANEL_H,
                Component.translatable("screen.sailboatmod.market.detail"),
                List.of(detailText));
    }

    private void drawListPanel(GuiGraphics g, int x, int y, int w, int h, Component title,
                               List<String> lines, int selectedIndex, int scroll, int mouseX, int mouseY) {
        drawPanelFrame(g, x, y, w, h);
        g.drawString(font, title, x + 6, y + 6, 0xFFF0CD87);
        if (lines == null || lines.isEmpty()) {
            g.drawString(font, Component.translatable("screen.sailboatmod.market.empty"), x + 6, y + 22, 0xFF9F998F);
            return;
        }
        int safeScroll = clampScroll(scroll, lines);
        int end = Math.min(lines.size(), safeScroll + VISIBLE_ROWS);
        int drawY = y + LIST_CONTENT_Y;
        for (int i = safeScroll; i < end; i++) {
            String line = lines.get(i);
            if (i == selectedIndex) {
                g.fill(x + 3, drawY - 1, x + w - 3, drawY + 8, 0x8861C3B0);
            }
            g.drawString(font, Component.literal(trimToWidth(line, w - 12)), x + 6, drawY, 0xFFE0E0E0);
            if (mouseX >= x + 3 && mouseX < x + w - 3 && mouseY >= drawY - 1 && mouseY < drawY + 9) {
                hoveredLine = line;
            }
            drawY += ROW_H;
        }
        int maxScroll = Math.max(0, lines.size() - VISIBLE_ROWS);
        if (maxScroll > 0) {
            String page = (safeScroll + 1) + "/" + (maxScroll + 1);
            g.drawString(font, Component.literal(page), x + w - 26, y + h - 10, 0xFF9F998F);
        }
    }

    private void drawDetailPanel(GuiGraphics g, int x, int y, int w, int h, Component title, List<String> paragraphs) {
        drawPanelFrame(g, x, y, w, h);
        g.drawString(font, title, x + 6, y + 6, 0xFFF0CD87);
        int drawY = y + 22;
        for (String paragraph : paragraphs) {
            if (paragraph == null || paragraph.isBlank()) {
                continue;
            }
            for (FormattedCharSequence sequence : wrapText(font, paragraph, w - 12)) {
                if (drawY > y + h - 10) {
                    return;
                }
                g.drawString(font, sequence, x + 6, drawY, 0xFFB7E7DE);
                drawY += 10;
            }
            drawY += 2;
        }
    }

    private void drawPanelFrame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xAA6A4B2A);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xAA221A13);
    }

    private boolean trySelectFromList(double mouseX, double mouseY, int x, int y, int w, List<String> lines, int listType, int scroll) {
        if (lines == null || lines.isEmpty() || !isInsideList(mouseX, mouseY, x, y, w)) {
            return false;
        }
        int row = (int) ((mouseY - (y + LIST_CONTENT_Y)) / ROW_H);
        int idx = scroll + row;
        if (row < 0 || row >= VISIBLE_ROWS || idx < 0 || idx >= lines.size()) {
            return false;
        }
        if (listType == 0) {
            selectedListingIndex = idx;
        } else if (listType == 1) {
            selectedOrderIndex = idx;
        } else if (listType == 2) {
            selectedBoatIndex = idx;
        } else {
            selectedStorageIndex = idx;
        }
        ensureSelectionVisible();
        return true;
    }

    private boolean isInsideList(double mouseX, double mouseY, int x, int y, int w) {
        return mouseX >= x && mouseX < x + w && mouseY >= y + 20 && mouseY < y + PANEL_H;
    }

    private void send(MarketGuiActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new MarketGuiActionPacket(data.marketPos(), action));
    }

    private void updateControlVisibility() {
        boolean onGoods = activePage == PAGE_GOODS;
        boolean onSell = activePage == PAGE_SELL;
        boolean onDispatch = activePage == PAGE_DISPATCH;
        boolean onFinance = activePage == PAGE_FINANCE;

        if (listingQtyInput != null) {
            listingQtyInput.visible = onSell;
            listingQtyInput.active = onSell;
        }
        if (listingPriceInput != null) {
            listingPriceInput.visible = onSell;
            listingPriceInput.active = onSell;
        }
        if (buyQtyInput != null) {
            buyQtyInput.visible = onGoods;
            buyQtyInput.active = onGoods;
        }
        if (listButton != null) {
            listButton.visible = onSell;
            listButton.active = onSell && data.linkedDock() && data.dockStorageAccessible() && !data.dockStorageLines().isEmpty();
        }
        if (cancelButton != null) {
            cancelButton.visible = onGoods;
            cancelButton.active = onGoods && data.linkedDock() && !data.listingLines().isEmpty();
        }
        if (buyButton != null) {
            buyButton.visible = onGoods;
            buyButton.active = onGoods && data.linkedDock() && !data.listingLines().isEmpty();
        }
        if (dispatchButton != null) {
            dispatchButton.visible = onDispatch;
            dispatchButton.active = onDispatch && data.linkedDock() && !data.orderLines().isEmpty() && !data.shippingLines().isEmpty();
        }
        if (claimButton != null) {
            claimButton.visible = onFinance;
            claimButton.active = onFinance && data.pendingCredits() > 0;
        }

        if (goodsTabButton != null) {
            goodsTabButton.active = !onGoods;
        }
        if (sellTabButton != null) {
            sellTabButton.active = !onSell;
        }
        if (dispatchTabButton != null) {
            dispatchTabButton.active = !onDispatch;
        }
        if (financeTabButton != null) {
            financeTabButton.active = !onFinance;
        }
    }

    private String pageTitleKey() {
        return switch (activePage) {
            case PAGE_SELL -> "screen.sailboatmod.market.page.sell";
            case PAGE_DISPATCH -> "screen.sailboatmod.market.page.dispatch";
            case PAGE_FINANCE -> "screen.sailboatmod.market.page.finance";
            default -> "screen.sailboatmod.market.page.goods";
        };
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
        return Math.max(0, Math.min(current, Math.max(0, lines.size() - VISIBLE_ROWS)));
    }

    private void ensureSelectionVisible() {
        listingScroll = ensureVisible(listingScroll, selectedListingIndex, data.listingLines());
        orderScroll = ensureVisible(orderScroll, selectedOrderIndex, data.orderLines());
        boatScroll = ensureVisible(boatScroll, selectedBoatIndex, data.shippingLines());
        storageScroll = ensureVisible(storageScroll, selectedStorageIndex, data.dockStorageLines());
    }

    private int ensureVisible(int scroll, int selectedIndex, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        int safeScroll = clampScroll(scroll, lines);
        if (selectedIndex < safeScroll) {
            return selectedIndex;
        }
        if (selectedIndex >= safeScroll + VISIBLE_ROWS) {
            return selectedIndex - VISIBLE_ROWS + 1;
        }
        return safeScroll;
    }

    private int parsePositive(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static List<FormattedCharSequence> wrapText(Font font, String text, int width) {
        return font.split(Component.literal(text), width);
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
        return new MarketOverviewData(pos, "Market", "-", "", 0, false, "-", "-", false, List.of(), List.of(), List.of(), List.of());
    }
}
