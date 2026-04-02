package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.MarketClientHooks;
import com.monpai.sailboatmod.client.MarketOverviewConsumer;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.menu.MarketMenu;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.CancelBuyOrderPacket;
import com.monpai.sailboatmod.network.packet.CancelMarketListingPacket;
import com.monpai.sailboatmod.network.packet.ClaimMarketCreditsPacket;
import com.monpai.sailboatmod.network.packet.CreateBuyOrderPacket;
import com.monpai.sailboatmod.network.packet.CreateMarketListingPacket;
import com.monpai.sailboatmod.network.packet.DispatchMarketOrderPacket;
import com.monpai.sailboatmod.network.packet.MarketGuiActionPacket;
import com.monpai.sailboatmod.network.packet.PurchaseMarketListingPacket;
import com.monpai.sailboatmod.network.packet.RenameMarketPacket;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.List;

public class MarketScreen extends AbstractContainerScreen<MarketMenu> implements MarketOverviewConsumer {
    private static final ResourceLocation BACKGROUND_TEXTURE = new ResourceLocation(SailboatMod.MODID, "textures/gui/market_background.png");
    private static final int BACKGROUND_TEXTURE_W = 440;
    private static final int BACKGROUND_TEXTURE_H = 250;

    private static final int PAGE_GOODS = 0;
    private static final int PAGE_SELL = 1;
    private static final int PAGE_DISPATCH = 2;
    private static final int PAGE_FINANCE = 3;
    private static final int PAGE_BUY_ORDERS = 4;

    private static final int SUMMARY_Y = 84;
    private static final int SUMMARY_W = 126;
    private static final int SUMMARY_H = 20;
    private static final int TAB_Y = 110;
    private static final int CONTENT_Y = 130;
    private static final int PANEL_H = 104;
    private static final int LEFT_X = 18;
    private static final int LEFT_W = 132;
    private static final int CENTER_X = 158;
    private static final int CENTER_W = 132;
    private static final int RIGHT_X = 298;
    private static final int RIGHT_W = 124;
    private static final int ROW_H = 12;
    private static final int LIST_CONTENT_Y = 20;

    private MarketOverviewData data;
    private int activePage = PAGE_GOODS;
    private int selectedListingIndex = 0;
    private int selectedOrderIndex = 0;
    private int selectedBoatIndex = 0;
    private int selectedStorageIndex = 0;
    private int selectedBuyOrderIndex = 0;
    private int listingScroll = 0;
    private int orderScroll = 0;
    private int boatScroll = 0;
    private int storageScroll = 0;
    private int buyOrderScroll = 0;
    private String hoveredLine;

    private EditBox listingQtyInput;
    private EditBox listingPriceAdjustInput;
    private EditBox buyQtyInput;
    private EditBox marketNameInput;
    private EditBox buyOrderCommodityInput;
    private EditBox buyOrderQtyInput;
    private EditBox buyOrderMinPriceInput;
    private EditBox buyOrderMaxPriceInput;

    private Button listButton;
    private Button buyButton;
    private Button cancelButton;
    private Button dispatchButton;
    private Button claimButton;
    private Button goodsTabButton;
    private Button sellTabButton;
    private Button dispatchTabButton;
    private Button financeTabButton;
    private Button buyOrdersTabButton;
    private Button refreshButton;
    private Button bindDockButton;
    private Button saveNameButton;
    private Button createBuyOrderButton;
    private Button cancelBuyOrderButton;

    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 440;
        this.imageHeight = 250;
        MarketOverviewData initial = MarketClientHooks.consumeFor(menu.getMarketPos());
        this.data = initial != null ? initial : empty(menu.getMarketPos());
    }

    public boolean isForMarket(BlockPos pos) {
        return data.marketPos().equals(pos);
    }

    public void updateData(MarketOverviewData updated) {
        this.data = updated;
        selectedListingIndex = clampSelection(selectedListingIndex, data.listingLines());
        selectedOrderIndex = clampSelection(selectedOrderIndex, data.orderLines());
        selectedBoatIndex = clampSelection(selectedBoatIndex, data.shippingLines());
        selectedStorageIndex = clampSelection(selectedStorageIndex, data.dockStorageLines());
        selectedBuyOrderIndex = clampSelection(selectedBuyOrderIndex, data.buyOrderLines());
        listingScroll = clampScroll(listingScroll, data.listingLines());
        orderScroll = clampScroll(orderScroll, data.orderLines());
        boatScroll = clampScroll(boatScroll, data.shippingLines());
        storageScroll = clampScroll(storageScroll, data.dockStorageLines());
        buyOrderScroll = clampScroll(buyOrderScroll, data.buyOrderLines());
        ensureSelectionVisible();
    }

    @Override
    protected void init() {
        super.init();
        goodsTabButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.page.goods"), b -> activePage = PAGE_GOODS)
                .bounds(leftPos + 18, topPos + TAB_Y, 70, 16).build());
        sellTabButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.page.sell"), b -> activePage = PAGE_SELL)
                .bounds(leftPos + 90, topPos + TAB_Y, 70, 16).build());
        dispatchTabButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.page.dispatch"), b -> activePage = PAGE_DISPATCH)
                .bounds(leftPos + 162, topPos + TAB_Y, 70, 16).build());
        financeTabButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.page.finance"), b -> activePage = PAGE_FINANCE)
                .bounds(leftPos + 234, topPos + TAB_Y, 70, 16).build());
        buyOrdersTabButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.page.buy_orders"), b -> activePage = PAGE_BUY_ORDERS)
                .bounds(leftPos + 306, topPos + TAB_Y, 70, 16).build());
        refreshButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.refresh"), b ->
                send(MarketGuiActionPacket.Action.REFRESH)).bounds(leftPos + 330, topPos + 18, 84, 16).build());
        bindDockButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.bind_dock"), b ->
                send(MarketGuiActionPacket.Action.BIND_NEAREST_DOCK)).bounds(leftPos + 330, topPos + 38, 84, 16).build());

        marketNameInput = new EditBox(font, leftPos + 22, topPos + 30, 206, 12, Component.translatable("screen.sailboatmod.market.name_input"));
        marketNameInput.setMaxLength(64);
        marketNameInput.setValue(data.marketName());
        addRenderableWidget(marketNameInput);
        saveNameButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.name_save"), b ->
                ModNetwork.CHANNEL.sendToServer(new RenameMarketPacket(data.marketPos(), marketNameInput.getValue())))
                .bounds(leftPos + 232, topPos + 29, 46, 14).build());

        int actionInnerX = leftPos + RIGHT_X + 60;
        int actionInputY = topPos + CONTENT_Y + 46;
        listingQtyInput = addRenderableWidget(new EditBox(font, actionInnerX, actionInputY, 44, 16, Component.translatable("screen.sailboatmod.market.qty")));
        listingQtyInput.setValue("1");
        listingQtyInput.setMaxLength(3);
        listingPriceAdjustInput = addRenderableWidget(new EditBox(font, actionInnerX, actionInputY + 20, 44, 16, Component.translatable("screen.sailboatmod.market.price_adjust")));
        listingPriceAdjustInput.setValue("0");
        listingPriceAdjustInput.setMaxLength(4);
        buyQtyInput = addRenderableWidget(new EditBox(font, actionInnerX, actionInputY, 44, 16, Component.translatable("screen.sailboatmod.market.buy_qty")));
        buyQtyInput.setValue("1");
        buyQtyInput.setMaxLength(3);

        buyOrderCommodityInput = addRenderableWidget(new EditBox(font, leftPos + RIGHT_X + 10, topPos + CONTENT_Y + 22, 104, 14, Component.translatable("screen.sailboatmod.market.buy_order.commodity")));
        buyOrderCommodityInput.setMaxLength(64);
        buyOrderQtyInput = addRenderableWidget(new EditBox(font, leftPos + RIGHT_X + 10, topPos + CONTENT_Y + 38, 104, 14, Component.translatable("screen.sailboatmod.market.buy_order.qty")));
        buyOrderQtyInput.setValue("1");
        buyOrderQtyInput.setMaxLength(5);
        buyOrderMinPriceInput = addRenderableWidget(new EditBox(font, leftPos + RIGHT_X + 10, topPos + CONTENT_Y + 54, 50, 14, Component.translatable("screen.sailboatmod.market.buy_order.min_price")));
        buyOrderMinPriceInput.setValue("-1000");
        buyOrderMinPriceInput.setMaxLength(6);
        buyOrderMaxPriceInput = addRenderableWidget(new EditBox(font, leftPos + RIGHT_X + 64, topPos + CONTENT_Y + 54, 50, 14, Component.translatable("screen.sailboatmod.market.buy_order.max_price")));
        buyOrderMaxPriceInput.setValue("1000");
        buyOrderMaxPriceInput.setMaxLength(6);

        listButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.list_hand"), b ->
                ModNetwork.CHANNEL.sendToServer(new CreateMarketListingPacket(data.marketPos(), selectedStorageIndex, parsePositive(listingQtyInput.getValue(), 1), 0, parsePriceAdjustment(listingPriceAdjustInput.getValue()))))
                .bounds(leftPos + RIGHT_X + 10, topPos + CONTENT_Y + 68, 104, 16).build());
        cancelButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.cancel"), b ->
                ModNetwork.CHANNEL.sendToServer(new CancelMarketListingPacket(data.marketPos(), selectedListingIndex)))
                .bounds(leftPos + RIGHT_X + 10, topPos + CONTENT_Y + 88, 104, 16).build());
        buyButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.buy"), b ->
                ModNetwork.CHANNEL.sendToServer(new PurchaseMarketListingPacket(data.marketPos(), selectedListingIndex, parsePositive(buyQtyInput.getValue(), 1))))
                .bounds(leftPos + RIGHT_X + 10, topPos + CONTENT_Y + 68, 104, 16).build());
        dispatchButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.dispatch"), b ->
                ModNetwork.CHANNEL.sendToServer(new DispatchMarketOrderPacket(data.marketPos(), selectedOrderIndex, selectedBoatIndex)))
                .bounds(leftPos + RIGHT_X + 10, topPos + CONTENT_Y + 84, 104, 16).build());
        claimButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.claim"), b ->
                ModNetwork.CHANNEL.sendToServer(new ClaimMarketCreditsPacket(data.marketPos())))
                .bounds(leftPos + RIGHT_X + 10, topPos + CONTENT_Y + 76, 104, 16).build());
        createBuyOrderButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.buy_order.create"), b ->
                ModNetwork.CHANNEL.sendToServer(new CreateBuyOrderPacket(data.marketPos(), buyOrderCommodityInput.getValue(),
                        parsePositive(buyOrderQtyInput.getValue(), 1), parsePriceAdjustment(buyOrderMinPriceInput.getValue()),
                        parsePriceAdjustment(buyOrderMaxPriceInput.getValue()))))
                .bounds(leftPos + RIGHT_X + 10, topPos + CONTENT_Y + 72, 104, 16).build());
        cancelBuyOrderButton = addRenderableWidget(Button.builder(Component.translatable("screen.sailboatmod.market.buy_order.cancel"), b -> {
                    if (selectedBuyOrderIndex >= 0 && selectedBuyOrderIndex < data.buyOrderEntries().size()) {
                        ModNetwork.CHANNEL.sendToServer(new CancelBuyOrderPacket(data.marketPos(), data.buyOrderEntries().get(selectedBuyOrderIndex).orderId()));
                    }
                })
                .bounds(leftPos + RIGHT_X + 10, topPos + CONTENT_Y + 90, 104, 16).build());

        send(MarketGuiActionPacket.Action.REFRESH);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        hoveredLine = null;
        updateControlVisibility();
        guiGraphics.blit(BACKGROUND_TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, BACKGROUND_TEXTURE_W, BACKGROUND_TEXTURE_H);
        drawHeader(guiGraphics);
        drawSummaryStrip(guiGraphics);

        if (activePage == PAGE_GOODS) {
            drawListPanel(guiGraphics, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H, Component.translatable("screen.sailboatmod.market.tab.goods"), data.listingLines(), selectedListingIndex, listingScroll, mouseX, mouseY);
            drawDetailPanel(guiGraphics, leftPos + CENTER_X, topPos + CONTENT_Y, CENTER_W, PANEL_H, Component.translatable("screen.sailboatmod.market.detail"), buildListingDetailLines());
            drawActionPanel(guiGraphics, leftPos + RIGHT_X, topPos + CONTENT_Y, RIGHT_W, PANEL_H, Component.translatable("screen.sailboatmod.market.page.goods"), buildGoodsActionLines());
        } else if (activePage == PAGE_SELL) {
            drawListPanel(guiGraphics, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H, Component.translatable("screen.sailboatmod.market.storage_title"), data.dockStorageLines(), selectedStorageIndex, storageScroll, mouseX, mouseY);
            drawDetailPanel(guiGraphics, leftPos + CENTER_X, topPos + CONTENT_Y, CENTER_W, PANEL_H, Component.translatable("screen.sailboatmod.market.detail"), buildStorageDetailLines());
            drawActionPanel(guiGraphics, leftPos + RIGHT_X, topPos + CONTENT_Y, RIGHT_W, PANEL_H, Component.translatable("screen.sailboatmod.market.page.sell"), buildSellActionLines());
        } else if (activePage == PAGE_DISPATCH) {
            drawListPanel(guiGraphics, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H, Component.translatable("screen.sailboatmod.market.tab.orders"), data.orderLines(), selectedOrderIndex, orderScroll, mouseX, mouseY);
            drawListPanel(guiGraphics, leftPos + CENTER_X, topPos + CONTENT_Y, CENTER_W, PANEL_H, Component.translatable("screen.sailboatmod.market.tab.shipping"), data.shippingLines(), selectedBoatIndex, boatScroll, mouseX, mouseY);
            drawActionPanel(guiGraphics, leftPos + RIGHT_X, topPos + CONTENT_Y, RIGHT_W, PANEL_H, Component.translatable("screen.sailboatmod.market.page.dispatch"), buildDispatchActionLines());
        } else if (activePage == PAGE_BUY_ORDERS) {
            drawListPanel(guiGraphics, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H, Component.translatable("screen.sailboatmod.market.tab.buy_orders"), data.buyOrderLines(), selectedBuyOrderIndex, buyOrderScroll, mouseX, mouseY);
            drawDetailPanel(guiGraphics, leftPos + CENTER_X, topPos + CONTENT_Y, CENTER_W, PANEL_H, Component.translatable("screen.sailboatmod.market.detail"), buildBuyOrderDetailLines());
            drawActionPanel(guiGraphics, leftPos + RIGHT_X, topPos + CONTENT_Y, RIGHT_W, PANEL_H, Component.translatable("screen.sailboatmod.market.page.buy_orders"), buildBuyOrderActionLines());
        } else {
            drawDetailPanel(guiGraphics, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H, Component.translatable("screen.sailboatmod.market.page.finance"), buildFinanceSummaryLines());
            drawDetailPanel(guiGraphics, leftPos + CENTER_X, topPos + CONTENT_Y, CENTER_W, PANEL_H, Component.translatable("screen.sailboatmod.market.detail"), buildFinanceDetailLines());
            drawActionPanel(guiGraphics, leftPos + RIGHT_X, topPos + CONTENT_Y, RIGHT_W, PANEL_H, Component.translatable("screen.sailboatmod.market.claim"), buildFinanceActionLines());
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
            if (activePage == PAGE_GOODS && trySelectFromList(mouseX, mouseY, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H, data.listingLines(), 0, listingScroll)) {
                return true;
            }
            if (activePage == PAGE_SELL && trySelectFromList(mouseX, mouseY, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H, data.dockStorageLines(), 3, storageScroll)) {
                return true;
            }
            if (activePage == PAGE_DISPATCH) {
                if (trySelectFromList(mouseX, mouseY, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H, data.orderLines(), 1, orderScroll)) {
                    return true;
                }
                if (trySelectFromList(mouseX, mouseY, leftPos + CENTER_X, topPos + CONTENT_Y, CENTER_W, PANEL_H, data.shippingLines(), 2, boatScroll)) {
                    return true;
                }
            }
            if (activePage == PAGE_BUY_ORDERS && trySelectFromList(mouseX, mouseY, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H, data.buyOrderLines(), 4, buyOrderScroll)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int direction = delta > 0 ? -1 : 1;
        if (activePage == PAGE_GOODS && isInsideList(mouseX, mouseY, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H)) {
            listingScroll = clampScroll(listingScroll + direction, data.listingLines());
            return true;
        }
        if (activePage == PAGE_SELL && isInsideList(mouseX, mouseY, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H)) {
            storageScroll = clampScroll(storageScroll + direction, data.dockStorageLines());
            return true;
        }
        if (activePage == PAGE_DISPATCH) {
            if (isInsideList(mouseX, mouseY, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H)) {
                orderScroll = clampScroll(orderScroll + direction, data.orderLines());
                return true;
            }
            if (isInsideList(mouseX, mouseY, leftPos + CENTER_X, topPos + CONTENT_Y, CENTER_W, PANEL_H)) {
                boatScroll = clampScroll(boatScroll + direction, data.shippingLines());
                return true;
            }
        }
        if (activePage == PAGE_BUY_ORDERS && isInsideList(mouseX, mouseY, leftPos + LEFT_X, topPos + CONTENT_Y, LEFT_W, PANEL_H)) {
            buyOrderScroll = clampScroll(buyOrderScroll + direction, data.buyOrderLines());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    private void drawHeader(GuiGraphics g) {
        g.drawString(font, Component.translatable("screen.sailboatmod.market.title"), leftPos + 22, topPos + 18, 0xFFD8BC86);
        if (!data.canManage()) {
            g.drawString(font, Component.literal(trimToWidth(data.marketName(), 214)), leftPos + 22, topPos + 32, 0xFFF0E4C3);
        } else if (marketNameInput != null && !marketNameInput.isFocused()) {
            marketNameInput.setValue(data.marketName());
        }
        g.drawString(font, Component.translatable("screen.sailboatmod.owner_name", data.ownerName()), leftPos + 22, topPos + 48, 0xFFC9D5C4);
        g.drawString(font, Component.literal(trimToWidth(currentDockLine(), 292)), leftPos + 22, topPos + 62, 0xFFD8BC86);
        g.fill(leftPos + 18, topPos + 76, leftPos + imageWidth - 18, topPos + 77, 0x668A6A42);
    }

    private void drawSummaryStrip(GuiGraphics g) {
        drawSummaryCard(g, leftPos + 18, topPos + SUMMARY_Y, SUMMARY_W, SUMMARY_H, pageTitle(), pageStatusText(), 0xC08D6B3F);
        drawSummaryCard(g, leftPos + 154, topPos + SUMMARY_Y, SUMMARY_W, SUMMARY_H, Component.translatable("screen.sailboatmod.market.summary.pending"), String.valueOf(data.pendingCredits()), 0xC06C7E49);
        drawSummaryCard(g, leftPos + 290, topPos + SUMMARY_Y, SUMMARY_W, SUMMARY_H, Component.translatable("screen.sailboatmod.market.summary.dock"), data.linkedDock() ? trimToWidth(data.linkedDockName(), 92) : Component.translatable("screen.sailboatmod.market.summary.unlinked").getString(), 0xC0576C7A);
    }

    private void drawSummaryCard(GuiGraphics g, int x, int y, int w, int h, Component label, String value, int accent) {
        g.fill(x, y, x + w, y + h, 0x7A18100B);
        g.fill(x, y, x + w, y + 1, accent);
        g.fill(x, y + h - 1, x + w, y + h, 0xAA3E2B18);
        g.drawString(font, label, x + 6, y + 5, 0xFFD0BA8C);
        g.drawString(font, Component.literal(trimToWidth(value, w - 54)), x + 50, y + 5, 0xFFF1E3C6);
    }

    private void drawListPanel(GuiGraphics g, int x, int y, int w, int h, Component title, List<String> lines, int selectedIndex, int scroll, int mouseX, int mouseY) {
        drawPanelFrame(g, x, y, w, h);
        g.drawString(font, title, x + 8, y + 7, 0xFFD8BC86);
        if (lines == null || lines.isEmpty()) {
            g.drawString(font, Component.translatable("screen.sailboatmod.market.empty"), x + 8, y + 24, 0xFFAA977D);
            return;
        }
        int safeScroll = clampScroll(scroll, lines);
        int end = Math.min(lines.size(), safeScroll + visibleRows());
        int drawY = y + LIST_CONTENT_Y;
        for (int i = safeScroll; i < end; i++) {
            String line = lines.get(i);
            if (i == selectedIndex) {
                g.fill(x + 4, drawY - 1, x + w - 4, drawY + 10, 0x884E3922);
                g.fill(x + 4, drawY - 1, x + w - 4, drawY, 0xCCB7925B);
            }
            g.drawString(font, Component.literal(trimToWidth(line, w - 16)), x + 8, drawY, 0xFFE7DBC0);
            if (mouseX >= x + 4 && mouseX < x + w - 4 && mouseY >= drawY - 1 && mouseY < drawY + 10) {
                hoveredLine = line;
            }
            drawY += ROW_H;
        }
        int maxScroll = Math.max(0, lines.size() - visibleRows());
        if (maxScroll > 0) {
            String page = (safeScroll + 1) + "/" + (maxScroll + 1);
            g.drawString(font, Component.literal(page), x + w - 30, y + h - 11, 0xFFAA977D);
        }
    }

    private void drawDetailPanel(GuiGraphics g, int x, int y, int w, int h, Component title, List<String> lines) {
        drawPanelFrame(g, x, y, w, h);
        g.drawString(font, title, x + 8, y + 7, 0xFFD8BC86);
        drawParagraphLines(g, x + 8, y + 22, w - 16, y + h - 10, lines, 0xFFE2D3B3);
    }

    private void drawActionPanel(GuiGraphics g, int x, int y, int w, int h, Component title, List<String> lines) {
        drawPanelFrame(g, x, y, w, h);
        g.drawString(font, title, x + 8, y + 7, 0xFFD8BC86);
        int textBottom = switch (activePage) {
            case PAGE_GOODS, PAGE_SELL -> y + 42;
            case PAGE_BUY_ORDERS -> y + 20;
            default -> y + h - 48;
        };
        drawParagraphLines(g, x + 8, y + 22, w - 16, textBottom, lines, 0xFFF0E2C4);
        if (activePage != PAGE_BUY_ORDERS) {
            g.fill(x + 8, y + h - 44, x + w - 8, y + h - 43, 0x66543B22);
        }
        if (activePage == PAGE_GOODS) {
            g.drawString(font, Component.translatable("screen.sailboatmod.market.buy_qty"), x + 10, y + 50, 0xFFD8BC86);
        } else if (activePage == PAGE_SELL) {
            g.drawString(font, Component.translatable("screen.sailboatmod.market.qty"), x + 10, y + 50, 0xFFD8BC86);
            g.drawString(font, Component.translatable("screen.sailboatmod.market.price_adjust"), x + 10, y + 70, 0xFFD8BC86);
        } else if (activePage == PAGE_BUY_ORDERS) {
            g.drawString(font, Component.translatable("screen.sailboatmod.market.buy_order.create_hint"), x + 8, y + 20, 0xFFD8BC86);
        }
    }

    private void drawParagraphLines(GuiGraphics g, int x, int startY, int width, int maxY, List<String> lines, int color) {
        int drawY = startY;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                drawY += 2;
                continue;
            }
            for (FormattedCharSequence sequence : wrapText(font, line, width)) {
                if (drawY > maxY) {
                    return;
                }
                g.drawString(font, sequence, x, drawY, color);
                drawY += 10;
            }
            drawY += 2;
        }
    }

    private void drawPanelFrame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0x8C1B110C);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x8427150E);
        g.fill(x, y, x + w, y + 1, 0xCCB18A54);
        g.fill(x, y + h - 1, x + w, y + h, 0xCC6F5030);
        g.fill(x, y, x + 1, y + h, 0xCCB18A54);
        g.fill(x + w - 1, y, x + w, y + h, 0xCC6F5030);
    }

    private boolean trySelectFromList(double mouseX, double mouseY, int x, int y, int w, int h, List<String> lines, int listType, int scroll) {
        if (lines == null || lines.isEmpty() || !isInsideList(mouseX, mouseY, x, y, w, h)) {
            return false;
        }
        int row = (int) ((mouseY - (y + LIST_CONTENT_Y)) / ROW_H);
        int idx = scroll + row;
        if (row < 0 || row >= visibleRows() || idx < 0 || idx >= lines.size()) {
            return false;
        }
        if (listType == 0) {
            selectedListingIndex = idx;
        } else if (listType == 1) {
            selectedOrderIndex = idx;
        } else if (listType == 2) {
            selectedBoatIndex = idx;
        } else if (listType == 3) {
            selectedStorageIndex = idx;
        } else {
            selectedBuyOrderIndex = idx;
        }
        ensureSelectionVisible();
        return true;
    }

    private boolean isInsideList(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y + 18 && mouseY < y + h - 8;
    }

    private void send(MarketGuiActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new MarketGuiActionPacket(data.marketPos(), action));
    }

    private void updateControlVisibility() {
        boolean onGoods = activePage == PAGE_GOODS;
        boolean onSell = activePage == PAGE_SELL;
        boolean onDispatch = activePage == PAGE_DISPATCH;
        boolean onFinance = activePage == PAGE_FINANCE;
        boolean onBuyOrders = activePage == PAGE_BUY_ORDERS;
        boolean canManage = data.canManage();

        if (marketNameInput != null) {
            marketNameInput.visible = canManage;
            marketNameInput.active = canManage;
        }
        if (saveNameButton != null) {
            saveNameButton.visible = canManage;
            saveNameButton.active = canManage;
        }
        if (refreshButton != null) {
            refreshButton.visible = true;
            refreshButton.active = true;
        }
        if (bindDockButton != null) {
            bindDockButton.visible = true;
            bindDockButton.active = true;
        }
        if (listingQtyInput != null) {
            listingQtyInput.visible = onSell;
            listingQtyInput.active = onSell;
        }
        if (listingPriceAdjustInput != null) {
            listingPriceAdjustInput.visible = onSell;
            listingPriceAdjustInput.active = onSell;
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
            cancelButton.active = onGoods && !data.listingLines().isEmpty();
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
        if (buyOrderCommodityInput != null) {
            buyOrderCommodityInput.visible = onBuyOrders;
            buyOrderCommodityInput.active = onBuyOrders;
        }
        if (buyOrderQtyInput != null) {
            buyOrderQtyInput.visible = onBuyOrders;
            buyOrderQtyInput.active = onBuyOrders;
        }
        if (buyOrderMinPriceInput != null) {
            buyOrderMinPriceInput.visible = onBuyOrders;
            buyOrderMinPriceInput.active = onBuyOrders;
        }
        if (buyOrderMaxPriceInput != null) {
            buyOrderMaxPriceInput.visible = onBuyOrders;
            buyOrderMaxPriceInput.active = onBuyOrders;
        }
        if (createBuyOrderButton != null) {
            createBuyOrderButton.visible = onBuyOrders;
            createBuyOrderButton.active = onBuyOrders && !buyOrderCommodityInput.getValue().isBlank();
        }
        if (cancelBuyOrderButton != null) {
            cancelBuyOrderButton.visible = onBuyOrders;
            cancelBuyOrderButton.active = onBuyOrders && !data.buyOrderEntries().isEmpty();
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
        if (buyOrdersTabButton != null) {
            buyOrdersTabButton.active = activePage != PAGE_BUY_ORDERS;
        }
    }
    private Component pageTitle() {
        return Component.translatable(pageTitleKey());
    }

    private String pageTitleKey() {
        return switch (activePage) {
            case PAGE_SELL -> "screen.sailboatmod.market.page.sell";
            case PAGE_DISPATCH -> "screen.sailboatmod.market.page.dispatch";
            case PAGE_FINANCE -> "screen.sailboatmod.market.page.finance";
            case PAGE_BUY_ORDERS -> "screen.sailboatmod.market.page.buy_orders";
            default -> "screen.sailboatmod.market.page.goods";
        };
    }

    private String pageStatusText() {
        return switch (activePage) {
            case PAGE_SELL -> Component.translatable(data.dockStorageAccessible() ? "screen.sailboatmod.market.status.ready_to_post" : "screen.sailboatmod.market.status.owner_required").getString();
            case PAGE_DISPATCH -> Component.translatable(data.linkedDock() ? "screen.sailboatmod.market.status.dock_online" : "screen.sailboatmod.market.status.bind_dock_first").getString();
            case PAGE_FINANCE -> Component.translatable(data.pendingCredits() > 0 ? "screen.sailboatmod.market.status.credits_waiting" : "screen.sailboatmod.market.status.no_credits").getString();
            case PAGE_BUY_ORDERS -> Component.translatable("screen.sailboatmod.market.status.buy_orders_active").getString();
            default -> Component.translatable(data.linkedDock() ? "screen.sailboatmod.market.status.live_feed" : "screen.sailboatmod.market.status.dock_required").getString();
        };
    }

    private List<String> buildListingDetailLines() {
        List<String> lines = new ArrayList<>();
        if (selectedListingIndex >= 0 && selectedListingIndex < data.listingEntries().size()) {
            MarketOverviewData.ListingEntry entry = data.listingEntries().get(selectedListingIndex);
            int qty = parsePositive(buyQtyInput == null ? "1" : buyQtyInput.getValue(), 1);
            lines.add(entry.itemName());
            lines.add(Component.translatable("screen.sailboatmod.market.detail.unit_price", entry.unitPrice()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.detail.available", entry.availableCount()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.detail.reserved", entry.reservedCount()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.detail.seller", entry.sellerName()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.detail.dock", entry.sourceDockName()).getString());
            if (!entry.nationId().isBlank()) {
                lines.add(Component.translatable("screen.sailboatmod.market.detail.nation", entry.nationId()).getString());
            }
            lines.add(Component.translatable("screen.sailboatmod.market.detail.estimate", qty, entry.unitPrice() * qty).getString());
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.market.empty").getString());
        return lines;
    }

    private List<String> buildStorageDetailLines() {
        List<String> lines = new ArrayList<>();
        if (!data.dockStorageAccessible()) {
            lines.add(Component.translatable("screen.sailboatmod.market.storage_owner_only").getString());
            lines.add(Component.translatable("screen.sailboatmod.market.auto_price_help").getString());
            return lines;
        }
        if (selectedStorageIndex >= 0 && selectedStorageIndex < data.dockStorageEntries().size()) {
            MarketOverviewData.StorageEntry entry = data.dockStorageEntries().get(selectedStorageIndex);
            int qty = parsePositive(listingQtyInput == null ? "1" : listingQtyInput.getValue(), 1);
            lines.add(entry.itemName());
            lines.add(Component.translatable("screen.sailboatmod.market.detail.in_storage", entry.quantity()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.detail.suggested_price", entry.suggestedUnitPrice()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.detail.estimate", qty, entry.suggestedUnitPrice() * qty).getString());
            lines.add(entry.detail());
            lines.add(Component.translatable("screen.sailboatmod.market.auto_price_help").getString());
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.market.storage_empty").getString());
        lines.add(Component.translatable("screen.sailboatmod.market.auto_price_help").getString());
        return lines;
    }

    private List<String> buildGoodsActionLines() {
        List<String> lines = new ArrayList<>();
        if (!data.linkedDock()) {
            lines.add(Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString());
            lines.add(Component.translatable("screen.sailboatmod.market.action.bind_dock_hint").getString());
            return lines;
        }
        if (selectedListingIndex >= 0 && selectedListingIndex < data.listingEntries().size()) {
            MarketOverviewData.ListingEntry entry = data.listingEntries().get(selectedListingIndex);
            int qty = parsePositive(buyQtyInput == null ? "1" : buyQtyInput.getValue(), 1);
            lines.add(trimToWidth(entry.itemName(), RIGHT_W - 20));
            lines.add(Component.translatable("screen.sailboatmod.market.action.buy_for", qty, entry.unitPrice() * qty).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.action.cancel_hint").getString());
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.market.empty").getString());
        return lines;
    }

    private List<String> buildSellActionLines() {
        List<String> lines = new ArrayList<>();
        if (!data.linkedDock()) {
            lines.add(Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString());
            return lines;
        }
        if (!data.dockStorageAccessible()) {
            lines.add(Component.translatable("screen.sailboatmod.market.storage_owner_only").getString());
            return lines;
        }
        if (selectedStorageIndex >= 0 && selectedStorageIndex < data.dockStorageEntries().size()) {
            MarketOverviewData.StorageEntry entry = data.dockStorageEntries().get(selectedStorageIndex);
            int qty = parsePositive(listingQtyInput == null ? "1" : listingQtyInput.getValue(), 1);
            lines.add(trimToWidth(entry.itemName(), RIGHT_W - 20));
            lines.add(Component.translatable("screen.sailboatmod.market.action.post_at_rate", qty).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.sell_help").getString());
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.market.storage_empty").getString());
        return lines;
    }

    private List<String> buildDispatchActionLines() {
        List<String> lines = new ArrayList<>();
        if (selectedOrderIndex >= 0 && selectedOrderIndex < data.orderEntries().size()) {
            MarketOverviewData.OrderEntry order = data.orderEntries().get(selectedOrderIndex);
            lines.add(order.sourceDockName() + " -> " + order.targetDockName());
            lines.add(Component.translatable("screen.sailboatmod.market.dispatch.qty", order.quantity()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.dispatch.status", order.status()).getString());
        } else {
            lines.add(Component.translatable("screen.sailboatmod.market.dispatch.select_order").getString());
        }
        if (selectedBoatIndex >= 0 && selectedBoatIndex < data.shippingEntries().size()) {
            MarketOverviewData.ShippingEntry boat = data.shippingEntries().get(selectedBoatIndex);
            lines.add(Component.translatable("screen.sailboatmod.market.dispatch.boat", boat.boatName()).getString());
            if (!boat.routeName().isBlank()) {
                lines.add(Component.translatable("screen.sailboatmod.market.dispatch.route", boat.routeName()).getString());
            }
            if (!boat.mode().isBlank()) {
                lines.add(Component.translatable("screen.sailboatmod.market.dispatch.mode", boat.mode()).getString());
            }
        } else {
            lines.add(Component.translatable("screen.sailboatmod.market.dispatch.select_boat").getString());
        }
        return lines;
    }

    private List<String> buildFinanceSummaryLines() {
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.market.finance.pending_credits", data.pendingCredits()).getString());
        lines.add(Component.translatable("screen.sailboatmod.market.finance.owner", data.ownerName()).getString());
        lines.add(Component.translatable(data.canManage() ? "screen.sailboatmod.market.finance.owner_controls" : "screen.sailboatmod.market.finance.read_only").getString());
        lines.add(Component.translatable("screen.sailboatmod.market.finance_help").getString());
        return lines;
    }

    private List<String> buildFinanceDetailLines() {
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.market.finance.market_name", data.marketName()).getString());
        lines.add(currentDockLine());
        lines.add(Component.translatable(data.dockStorageAccessible() ? "screen.sailboatmod.market.finance.storage_available" : "screen.sailboatmod.market.finance.storage_locked").getString());
        lines.add(Component.translatable("screen.sailboatmod.market.finance.refresh_hint").getString());
        return lines;
    }

    private List<String> buildFinanceActionLines() {
        List<String> lines = new ArrayList<>();
        if (data.pendingCredits() > 0) {
            lines.add(Component.translatable("screen.sailboatmod.market.finance.claim_hint").getString());
            lines.add(Component.translatable("screen.sailboatmod.market.finance.deposit_fail_hint").getString());
        } else {
            lines.add(Component.translatable("screen.sailboatmod.market.finance.no_credits_hint").getString());
        }
        return lines;
    }

    private List<String> buildBuyOrderDetailLines() {
        List<String> lines = new ArrayList<>();
        if (selectedBuyOrderIndex >= 0 && selectedBuyOrderIndex < data.buyOrderEntries().size()) {
            MarketOverviewData.BuyOrderEntry entry = data.buyOrderEntries().get(selectedBuyOrderIndex);
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.commodity", entry.commodityKey()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.quantity", entry.quantity()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.price_range", entry.minPriceBp(), entry.maxPriceBp()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.buyer", entry.buyerName()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.status", entry.status()).getString());
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.market.empty").getString());
        return lines;
    }

    private List<String> buildBuyOrderActionLines() {
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.market.buy_order.create_hint").getString());
        lines.add(Component.translatable("screen.sailboatmod.market.buy_order.price_range_hint").getString());
        return lines;
    }

    private String currentDockLine() {
        return data.linkedDock()
                ? Component.translatable("screen.sailboatmod.market.linked_dock_value", data.linkedDockName(), data.linkedDockPosText()).getString()
                : Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString();
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
        return Math.max(0, Math.min(current, Math.max(0, lines.size() - visibleRows())));
    }

    private void ensureSelectionVisible() {
        listingScroll = ensureVisible(listingScroll, selectedListingIndex, data.listingLines());
        orderScroll = ensureVisible(orderScroll, selectedOrderIndex, data.orderLines());
        boatScroll = ensureVisible(boatScroll, selectedBoatIndex, data.shippingLines());
        storageScroll = ensureVisible(storageScroll, selectedStorageIndex, data.dockStorageLines());
        buyOrderScroll = ensureVisible(buyOrderScroll, selectedBuyOrderIndex, data.buyOrderLines());
    }

    private int ensureVisible(int scroll, int selectedIndex, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return 0;
        }
        int safeScroll = clampScroll(scroll, lines);
        if (selectedIndex < safeScroll) {
            return selectedIndex;
        }
        if (selectedIndex >= safeScroll + visibleRows()) {
            return selectedIndex - visibleRows() + 1;
        }
        return safeScroll;
    }

    private int visibleRows() {
        return Math.max(1, (PANEL_H - LIST_CONTENT_Y - 12) / ROW_H);
    }

    private int parsePositive(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int parsePriceAdjustment(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(-1000, Math.min(1000, parsed));
        } catch (Exception ignored) {
            return 0;
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

    private MarketOverviewData empty(BlockPos pos) {
        return new MarketOverviewData(pos, "Market", "-", "", 0, false, "-", "-", false, false,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
