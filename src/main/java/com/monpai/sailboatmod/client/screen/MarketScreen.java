package com.monpai.sailboatmod.client.screen;

import com.monpai.sailboatmod.client.MarketClientHooks;
import com.monpai.sailboatmod.client.MarketOverviewConsumer;
import com.monpai.sailboatmod.economy.GoldStandardEconomy;
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
import gg.essential.elementa.ElementaVersion;
import gg.essential.elementa.UIComponent;
import gg.essential.elementa.WindowScreen;
import gg.essential.elementa.components.ScrollComponent;
import gg.essential.elementa.components.UIBlock;
import gg.essential.elementa.components.UIRoundedRectangle;
import gg.essential.elementa.components.UIText;
import gg.essential.elementa.components.UIWrappedText;
import gg.essential.elementa.components.Window;
import gg.essential.elementa.components.input.UITextInput;
import gg.essential.elementa.constraints.CramSiblingConstraint;
import gg.essential.elementa.constraints.ChildBasedSizeConstraint;
import gg.essential.elementa.constraints.PixelConstraint;
import gg.essential.elementa.effects.OutlineEffect;
import kotlin.Unit;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class MarketScreen extends WindowScreen implements MenuAccess<MarketMenu>, MarketOverviewConsumer {
    private static final int MAX_PANEL_WIDTH = 1220;
    private static final int MAX_PANEL_HEIGHT = 760;
    private static final int MIN_PANEL_WIDTH = 980;
    private static final int MIN_PANEL_HEIGHT = 620;
    private static final int NAV_WIDTH = 168;
    private static final int HEADER_HEIGHT = 156;
    private static final int PANEL_PADDING = 24;
    private static final int SECTION_GAP = 16;
    private static final int ROW_HEIGHT = 58;

    private static final Color OVERLAY = new Color(5, 10, 16, 208);
    private static final Color WINDOW_BG = new Color(16, 22, 30, 246);
    private static final Color CHROME_BG = new Color(21, 30, 39, 244);
    private static final Color CHROME_MUTED = new Color(32, 43, 54, 230);
    private static final Color CARD_BG = new Color(23, 30, 39, 232);
    private static final Color CARD_BG_SOFT = new Color(28, 38, 49, 220);
    private static final Color ROW_BG = new Color(18, 25, 33, 230);
    private static final Color ROW_HOVER = new Color(31, 46, 60, 238);
    private static final Color ROW_SELECTED = new Color(57, 86, 105, 244);
    private static final Color ACCENT = new Color(240, 186, 96, 255);
    private static final Color ACCENT_DIM = new Color(198, 146, 76, 255);
    private static final Color POSITIVE = new Color(121, 199, 150, 255);
    private static final Color NEGATIVE = new Color(217, 110, 103, 255);
    private static final Color TEXT_PRIMARY = new Color(234, 241, 248, 255);
    private static final Color TEXT_MUTED = new Color(153, 171, 186, 255);
    private static final Color TEXT_SOFT = new Color(118, 136, 152, 255);
    private static final Color BORDER = new Color(255, 255, 255, 20);

    private final MarketMenu menu;
    private final Component title;

    private MarketOverviewData data;
    private MarketPage activePage = MarketPage.GOODS;
    private boolean initialRefreshSent;
    private boolean closingContainer;

    private int selectedListingIndex;
    private int selectedOrderIndex;
    private int selectedBoatIndex;
    private int selectedStorageIndex;
    private int selectedBuyOrderIndex;

    private String listingQtyValue = "1";
    private String listingPriceAdjustValue = "0";
    private String buyQtyValue = "1";
    private String buyOrderCommodityValue = "";
    private String buyOrderQtyValue = "1";
    private String buyOrderMinPriceValue = "-1000";
    private String buyOrderMaxPriceValue = "1000";

    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        super(ElementaVersion.V11, false, false, false);
        this.menu = menu;
        this.title = title;
        MarketOverviewData initial = MarketClientHooks.consumeFor(menu.getMarketPos());
        this.data = initial != null ? initial : empty(menu.getMarketPos());
    }

    @Override
    public MarketMenu getMenu() {
        return menu;
    }

    @Override
    public boolean isForMarket(BlockPos pos) {
        return data.marketPos().equals(pos);
    }

    @Override
    public void updateData(MarketOverviewData updated) {
        this.data = updated;
        clampSelections();
        rebuildUi();
    }

    @Override
    public void afterInitialization() {
        super.afterInitialization();
        rebuildUi();
        if (!initialRefreshSent) {
            send(MarketGuiActionPacket.Action.REFRESH);
            initialRefreshSent = true;
        }
    }

    @Override
    public void initScreen(int width, int height) {
        super.initScreen(width, height);
        rebuildUi();
    }

    @Override
    public void onTick() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        if (!menu.stillValid(minecraft.player)) {
            onClose();
        }
    }

    @Override
    public void onScreenClose() {
        try {
            if (!closingContainer && minecraft != null && minecraft.player != null && minecraft.player.containerMenu == menu) {
                closingContainer = true;
                minecraft.player.closeContainer();
            }
        } finally {
            closingContainer = false;
        }
        super.onScreenClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void rebuildUi() {
        if (width <= 0 || height <= 0) {
            return;
        }
        clampSelections();

        Window window = getWindow();
        window.clearChildren();

        int panelWidth = clamp(width - 72, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
        int panelHeight = clamp(height - 52, MIN_PANEL_HEIGHT, MAX_PANEL_HEIGHT);
        int panelX = (width - panelWidth) / 2;
        int panelY = (height - panelHeight) / 2;

        UIBlock overlay = new UIBlock(OVERLAY);
        overlay.setX(new PixelConstraint(0));
        overlay.setY(new PixelConstraint(0));
        overlay.setWidth(new PixelConstraint(width));
        overlay.setHeight(new PixelConstraint(height));
        overlay.setChildOf(window);

        createGlow(overlay, -120, 72, 360, 240, new Color(234, 164, 70, 28));
        createGlow(overlay, width - 260, height - 220, 300, 220, new Color(74, 132, 176, 26));

        UIRoundedRectangle mainPanel = createPanel(overlay, panelX, panelY, panelWidth, panelHeight, 18f, WINDOW_BG);
        mainPanel.enableEffect(new OutlineEffect(BORDER, 1f));

        buildHeader(mainPanel, panelWidth);
        buildTabs(mainPanel, panelWidth);

        int contentX = PANEL_PADDING;
        int contentY = HEADER_HEIGHT + 56;
        int contentWidth = panelWidth - PANEL_PADDING * 2;
        int contentHeight = panelHeight - contentY - PANEL_PADDING;

        switch (activePage) {
            case GOODS -> buildGoodsPage(mainPanel, contentX, contentY, contentWidth, contentHeight);
            case SELL -> buildSellPage(mainPanel, contentX, contentY, contentWidth, contentHeight);
            case DISPATCH -> buildDispatchPage(mainPanel, contentX, contentY, contentWidth, contentHeight);
            case FINANCE -> buildFinancePage(mainPanel, contentX, contentY, contentWidth, contentHeight);
            case BUY_ORDERS -> buildBuyOrdersPage(mainPanel, contentX, contentY, contentWidth, contentHeight);
        }
    }

    private void buildHeader(UIComponent parent, int panelWidth) {
        UIRoundedRectangle header = createPanel(parent, PANEL_PADDING, PANEL_PADDING, panelWidth - PANEL_PADDING * 2, HEADER_HEIGHT, 16f, CHROME_BG);
        createAccentBar(header, 0, 0, 180, 6, ACCENT);

        UIRoundedRectangle iconCard = createPanel(header, 18, 18, 104, 104, 14f, CARD_BG_SOFT);
        iconCard.enableEffect(new OutlineEffect(BORDER, 1f));
        createText(iconCard, 16, 24, "BUFF", 1.42f, ACCENT);
        createText(iconCard, 16, 54, activePage.kicker, 1.08f, TEXT_PRIMARY);
        createText(iconCard, 16, 76, "MARKET", 0.82f, TEXT_SOFT);

        createText(header, 142, 18, "饰品市场 / 商品详情", 0.84f, TEXT_SOFT);
        createText(header, 142, 42, marketTitleLine(), 1.38f, TEXT_PRIMARY);
        createText(header, 142, 66, marketSubtitleLine(), 0.92f, ACCENT_DIM);
        createText(header, 142, 88, currentDockLine(), 0.88f, data.linkedDock() ? TEXT_MUTED : NEGATIVE);

        int pillX = 330;
        for (String pill : buildHeaderPills()) {
            int pillWidth = Math.max(112, pill.length() * 7 + 28);
            createBadge(header, pillX, 114, pillWidth, 22, pill, CHROME_MUTED, TEXT_MUTED);
            pillX += pillWidth + 8;
        }

        String economy = buildEconomyHeaderLine();
        if (!economy.isBlank()) {
            int economyWidth = Math.min(360, Math.max(210, economy.length() * 7));
            UIWrappedText economyText = new UIWrappedText(economy);
            economyText.setTextScale(new PixelConstraint(0.88f));
            economyText.setColor(TEXT_PRIMARY);
            economyText.setX(new PixelConstraint(panelWidth - PANEL_PADDING * 2 - economyWidth - 206));
            economyText.setY(new PixelConstraint(20));
            economyText.setWidth(new PixelConstraint(economyWidth));
            economyText.setChildOf(header);
        }

        buildMetricTile(header, 142, 112, 118, 30, Component.translatable("screen.sailboatmod.market.metric.available").getString(),
                Integer.toString(data.listingEntries().size()), POSITIVE);
        buildMetricTile(header, 268, 112, 118, 30, Component.translatable("screen.sailboatmod.market.pending").getString(),
                formatCompactLong(data.pendingCredits()), ACCENT);
        buildMetricTile(header, 394, 112, 118, 30, Component.translatable("screen.sailboatmod.econbar.net").getString(),
                formatSignedLong(data.netBalance()), data.netBalance() >= 0 ? POSITIVE : NEGATIVE);

        createButton(header, panelWidth - PANEL_PADDING * 2 - 286, 104, 88, 24,
                Component.translatable("screen.sailboatmod.market.refresh").getString(), true, false, this::refreshMarket);
        createButton(header, panelWidth - PANEL_PADDING * 2 - 192, 104, 88, 24,
                Component.translatable("screen.sailboatmod.market.bind_dock").getString(), true, false,
                () -> send(MarketGuiActionPacket.Action.BIND_NEAREST_DOCK));
        createButton(header, panelWidth - PANEL_PADDING * 2 - 98, 104, 74, 24,
                Component.translatable("screen.sailboatmod.route_name.cancel").getString(), true, true, this::onClose);
    }

    private void buildTabs(UIComponent parent, int panelWidth) {
        UIRoundedRectangle tabs = createPanel(parent, PANEL_PADDING, HEADER_HEIGHT + 18, panelWidth - PANEL_PADDING * 2, 38, 14f, CHROME_BG);
        int x = 12;
        for (MarketPage page : MarketPage.values()) {
            x += createTabButton(tabs, x, page);
        }
    }

    private void buildGoodsPage(UIComponent parent, int x, int y, int width, int height) {
        int sidebarWidth = 340;
        int listWidth = width - sidebarWidth - SECTION_GAP;

        UIRoundedRectangle listPanel = createSection(parent, x, y, listWidth, height,
                Component.translatable("screen.sailboatmod.market.tab.goods").getString(),
                "Selling");
        buildListPanel(listPanel, data.listingEntries().isEmpty()
                        ? List.of(rowSpec(Component.translatable("screen.sailboatmod.market.empty").getString(), "", "", false, null))
                        : buildListingRows(),
                listWidth - 28, height - 68);

        UIRoundedRectangle actionPanel = createSection(parent, x + listWidth + SECTION_GAP, y, sidebarWidth, height,
                selectedListing() == null ? Component.translatable("screen.sailboatmod.market.page.goods").getString() : selectedListing().itemName(),
                actionCaption());
        buildGoodsActionPanel(actionPanel, sidebarWidth);
    }

    private void buildSellPage(UIComponent parent, int x, int y, int width, int height) {
        int sidebarWidth = 340;
        int listWidth = width - sidebarWidth - SECTION_GAP;

        UIRoundedRectangle listPanel = createSection(parent, x, y, listWidth, height,
                Component.translatable("screen.sailboatmod.market.storage_title").getString(),
                "Inventory");
        buildListPanel(listPanel, data.dockStorageEntries().isEmpty()
                        ? List.of(rowSpec(Component.translatable("screen.sailboatmod.market.storage_empty").getString(), "", "", false, null))
                        : buildStorageRows(),
                listWidth - 28, height - 68);

        UIRoundedRectangle actionPanel = createSection(parent, x + listWidth + SECTION_GAP, y, sidebarWidth, height,
                selectedStorage() == null ? Component.translatable("screen.sailboatmod.market.page.sell").getString() : selectedStorage().itemName(),
                data.dockStorageAccessible()
                        ? Component.translatable("screen.sailboatmod.market.auto_price_help").getString()
                        : Component.translatable("screen.sailboatmod.market.storage_owner_only").getString());
        buildSellActionPanel(actionPanel, sidebarWidth);
    }

    private void buildDispatchPage(UIComponent parent, int x, int y, int width, int height) {
        int orderWidth = 330;
        int boatWidth = 304;
        int actionWidth = width - orderWidth - boatWidth - SECTION_GAP * 2;

        UIRoundedRectangle orders = createSection(parent, x, y, orderWidth, height,
                Component.translatable("screen.sailboatmod.market.tab.orders").getString(),
                Component.translatable("screen.sailboatmod.market.dispatch.select_order").getString());
        buildListPanel(orders, data.orderEntries().isEmpty()
                        ? List.of(rowSpec(Component.translatable("screen.sailboatmod.market.empty").getString(), "", "", false, null))
                        : buildOrderRows(),
                orderWidth - 28, height - 68);

        UIRoundedRectangle boats = createSection(parent, x + orderWidth + SECTION_GAP, y, boatWidth, height,
                Component.translatable("screen.sailboatmod.market.tab.shipping").getString(),
                Component.translatable("screen.sailboatmod.market.dispatch.select_boat").getString());
        buildListPanel(boats, data.shippingEntries().isEmpty()
                        ? List.of(rowSpec(Component.translatable("screen.sailboatmod.market.empty").getString(), "", "", false, null))
                        : buildBoatRows(),
                boatWidth - 28, height - 68);

        UIRoundedRectangle action = createSection(parent, x + orderWidth + boatWidth + SECTION_GAP * 2, y, actionWidth, height,
                Component.translatable("screen.sailboatmod.market.page.dispatch").getString(),
                Component.translatable("screen.sailboatmod.market.dispatch").getString());
        buildDispatchActionPanel(action, actionWidth);
    }

    private void buildFinancePage(UIComponent parent, int x, int y, int width, int height) {
        int leftWidth = 364;
        int centerWidth = 292;
        int rightWidth = width - leftWidth - centerWidth - SECTION_GAP * 2;
        int topHeight = 168;
        int bottomHeight = height - topHeight - SECTION_GAP;

        UIRoundedRectangle summary = createSection(parent, x, y, leftWidth, topHeight,
                Component.translatable("screen.sailboatmod.market.page.finance").getString(),
                Component.translatable("screen.sailboatmod.market.finance_help").getString());
        buildFinanceSummary(summary, leftWidth);

        UIRoundedRectangle economy = createSection(parent, x + leftWidth + SECTION_GAP, y, centerWidth, topHeight,
                Component.translatable("screen.sailboatmod.market.finance.town", data.townName().isBlank() ? "-" : data.townName()).getString(),
                data.hasTownEconomy()
                        ? Component.translatable("screen.sailboatmod.market.finance.employment", formatPercent(data.employmentRate())).getString()
                        : Component.translatable("screen.sailboatmod.market.finance.no_town").getString());
        buildFinanceEconomy(economy, centerWidth);

        UIRoundedRectangle action = createSection(parent, x + leftWidth + centerWidth + SECTION_GAP * 2, y, rightWidth, topHeight,
                Component.translatable("screen.sailboatmod.market.claim").getString(),
                data.pendingCredits() > 0
                        ? Component.translatable("screen.sailboatmod.market.finance.claim_hint").getString()
                        : Component.translatable("screen.sailboatmod.market.finance.no_credits_hint").getString());
        buildFinanceAction(action, rightWidth);

        UIRoundedRectangle stockpile = createSection(parent, x, y + topHeight + SECTION_GAP, leftWidth, bottomHeight,
                Component.translatable("screen.sailboatmod.market.metric.stockpile").getString(),
                Component.translatable("screen.sailboatmod.market.metric.demand").getString());
        buildPreviewBlock(stockpile, data.stockpilePreviewLines(), leftWidth);

        UIRoundedRectangle procurement = createSection(parent, x + leftWidth + SECTION_GAP, y + topHeight + SECTION_GAP, centerWidth, bottomHeight,
                Component.translatable("screen.sailboatmod.market.finance.procurement").getString(),
                Component.translatable("screen.sailboatmod.market.finance.refresh_hint").getString());
        buildPreviewBlock(procurement, joinLines(data.demandPreviewLines(), data.procurementPreviewLines()), centerWidth);

        UIRoundedRectangle finance = createSection(parent, x + leftWidth + centerWidth + SECTION_GAP * 2, y + topHeight + SECTION_GAP, rightWidth, bottomHeight,
                Component.translatable("screen.sailboatmod.market.finance.market_name", data.marketName()).getString(),
                Component.translatable(data.canManage() ? "screen.sailboatmod.market.finance.owner_controls" : "screen.sailboatmod.market.finance.read_only").getString());
        buildPreviewBlock(finance, joinLines(data.financePreviewLines(), buildFinanceDetailLines()), rightWidth);
    }

    private void buildBuyOrdersPage(UIComponent parent, int x, int y, int width, int height) {
        int sidebarWidth = 340;
        int listWidth = width - sidebarWidth - SECTION_GAP;

        UIRoundedRectangle listPanel = createSection(parent, x, y, listWidth, height,
                Component.translatable("screen.sailboatmod.market.tab.buy_orders").getString(),
                "Purchase Orders");
        buildListPanel(listPanel, data.buyOrderEntries().isEmpty()
                        ? List.of(rowSpec(Component.translatable("screen.sailboatmod.market.empty").getString(), "", "", false, null))
                        : buildBuyOrderRows(),
                listWidth - 28, height - 68);

        UIRoundedRectangle actionPanel = createSection(parent, x + listWidth + SECTION_GAP, y, sidebarWidth, height,
                selectedBuyOrder() == null ? Component.translatable("screen.sailboatmod.market.page.buy_orders").getString() : selectedBuyOrder().commodityKey(),
                Component.translatable("screen.sailboatmod.market.buy_order.price_range_hint").getString());
        buildBuyOrderActionPanel(actionPanel, sidebarWidth);
    }

    private void buildGoodsActionPanel(UIComponent panel, int width) {
        MarketOverviewData.ListingEntry listing = selectedListing();
        int innerWidth = width - 28;
        buildTextStack(panel, 14, 48, innerWidth, buildListingDetailLines(), TEXT_MUTED);
        createMetricStrip(panel, 14, 124, innerWidth, buildActionMetrics());

        UITextInput qtyInput = createInput(panel, 14, 190, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.buy_qty").getString(), buyQtyValue, value -> buyQtyValue = value);
        qtyInput.onActivate(value -> {
            buyQtyValue = value;
            sendBuy();
            return Unit.INSTANCE;
        });

        createButton(panel, 14, 230, innerWidth, 34,
                Component.translatable("screen.sailboatmod.market.buy").getString(),
                listing != null && data.linkedDock(), false, this::sendBuy);
        createButton(panel, 14, 270, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.cancel").getString(),
                listing != null && data.canManage(), true, this::cancelListing);

        buildTextStack(panel, 14, 312, innerWidth, buildGoodsActionLines(), TEXT_MUTED);
    }

    private void buildSellActionPanel(UIComponent panel, int width) {
        int innerWidth = width - 28;
        buildTextStack(panel, 14, 48, innerWidth, buildStorageDetailLines(), TEXT_MUTED);
        createMetricStrip(panel, 14, 124, innerWidth, buildActionMetrics());

        UITextInput qtyInput = createInput(panel, 14, 190, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.qty").getString(), listingQtyValue, value -> listingQtyValue = value);
        qtyInput.onActivate(value -> {
            listingQtyValue = value;
            createListing();
            return Unit.INSTANCE;
        });

        createInput(panel, 14, 228, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.price_adjust").getString(), listingPriceAdjustValue,
                value -> listingPriceAdjustValue = value);

        createButton(panel, 14, 270, innerWidth, 34,
                Component.translatable("screen.sailboatmod.market.list_hand").getString(),
                selectedStorage() != null && data.dockStorageAccessible() && data.linkedDock(), false, this::createListing);

        buildTextStack(panel, 14, 318, innerWidth, buildSellActionLines(), TEXT_MUTED);
    }

    private void buildDispatchActionPanel(UIComponent panel, int width) {
        int innerWidth = width - 28;
        createMetricStrip(panel, 14, 48, innerWidth, buildDispatchMetrics());
        buildTextStack(panel, 14, 108, innerWidth, buildDispatchActionLines(), TEXT_MUTED);
        createButton(panel, 14, 300, innerWidth, 34,
                Component.translatable("screen.sailboatmod.market.dispatch").getString(),
                selectedOrder() != null && selectedShipping() != null, false, this::dispatchSelectedOrder);
    }

    private void buildFinanceSummary(UIComponent panel, int width) {
        int tileWidth = (width - 42) / 2;
        buildMetricTile(panel, 14, 48, tileWidth, 46, Component.translatable("screen.sailboatmod.market.pending").getString(),
                formatCompactLong(data.pendingCredits()), ACCENT);
        buildMetricTile(panel, 14 + tileWidth + 10, 48, tileWidth, 46, Component.translatable("screen.sailboatmod.econbar.net").getString(),
                formatSignedLong(data.netBalance()), data.netBalance() >= 0 ? POSITIVE : NEGATIVE);
        buildMetricTile(panel, 14, 102, tileWidth, 46, Component.translatable("screen.sailboatmod.econbar.income").getString(),
                formatCompactLong(data.totalIncome()), POSITIVE);
        buildMetricTile(panel, 14 + tileWidth + 10, 102, tileWidth, 46, Component.translatable("screen.sailboatmod.econbar.expense").getString(),
                formatCompactLong(data.totalExpense()), NEGATIVE);
    }

    private void buildFinanceEconomy(UIComponent panel, int width) {
        int innerWidth = width - 28;
        buildMetricTile(panel, 14, 48, innerWidth, 44,
                Component.translatable("screen.sailboatmod.market.metric.stockpile").getString(),
                data.stockpileCommodityTypes() + " / " + data.stockpileTotalUnits(), ACCENT_DIM);
        buildMetricTile(panel, 14, 98, innerWidth, 44,
                Component.translatable("screen.sailboatmod.market.metric.demand").getString(),
                data.openDemandCount() + " / " + data.openDemandUnits(), new Color(117, 170, 219));
    }

    private void buildFinanceAction(UIComponent panel, int width) {
        int innerWidth = width - 28;
        buildTextStack(panel, 14, 48, innerWidth, buildFinanceActionLines(), TEXT_MUTED);
        createButton(panel, 14, 118, innerWidth, 34,
                Component.translatable("screen.sailboatmod.market.claim").getString(),
                data.pendingCredits() > 0, false, this::claimCredits);
    }

    private void buildPreviewBlock(UIComponent panel, List<String> lines, int width) {
        buildTextStack(panel, 14, 48, width - 28, lines.isEmpty()
                ? List.of(Component.translatable("screen.sailboatmod.market.empty").getString())
                : lines, TEXT_MUTED);
    }

    private void buildBuyOrderActionPanel(UIComponent panel, int width) {
        int innerWidth = width - 28;
        buildTextStack(panel, 14, 48, innerWidth, buildBuyOrderDetailLines(), TEXT_MUTED);
        createInput(panel, 14, 132, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.buy_order.commodity").getString(),
                buyOrderCommodityValue, value -> buyOrderCommodityValue = value);
        createInput(panel, 14, 170, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.buy_order.qty").getString(),
                buyOrderQtyValue, value -> buyOrderQtyValue = value);
        createInput(panel, 14, 208, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.buy_order.min_price").getString(),
                buyOrderMinPriceValue, value -> buyOrderMinPriceValue = value);
        createInput(panel, 14, 246, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.buy_order.max_price").getString(),
                buyOrderMaxPriceValue, value -> buyOrderMaxPriceValue = value);

        createButton(panel, 14, 288, innerWidth, 34,
                Component.translatable("screen.sailboatmod.market.buy_order.create").getString(),
                !buyOrderCommodityValue.isBlank(), false, this::createBuyOrder);
        createButton(panel, 14, 328, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.buy_order.cancel").getString(),
                selectedBuyOrder() != null, true, this::cancelBuyOrder);

        buildTextStack(panel, 14, 372, innerWidth, buildBuyOrderActionLines(), TEXT_MUTED);
    }

    private void buildDetailSection(UIComponent panel, String headline, List<MetricCard> metrics, List<String> lines, int width, int height) {
        createText(panel, 14, 48, headline.isBlank() ? Component.translatable("screen.sailboatmod.market.empty").getString() : headline, 1.18f, TEXT_PRIMARY);
        createMetricStrip(panel, 14, 80, width, metrics);
        buildTextStack(panel, 14, 148, width, lines, TEXT_MUTED);
    }

    private void buildListPanel(UIComponent panel, List<RowSpec> rows, int width, int height) {
        ScrollComponent scroll = new ScrollComponent();
        scroll.setX(new PixelConstraint(14));
        scroll.setY(new PixelConstraint(48));
        scroll.setWidth(new PixelConstraint(width));
        scroll.setHeight(new PixelConstraint(height));
        scroll.setChildOf(panel);
        scroll.setColor(new Color(0, 0, 0, 0));

        for (RowSpec row : rows) {
            createRow(scroll, width - 8, row);
        }
    }

    private void createRow(UIComponent parent, int width, RowSpec spec) {
        Color rowColor = spec.selected ? ROW_SELECTED : ROW_BG;
        UIRoundedRectangle row = new UIRoundedRectangle(12f);
        row.setX(new PixelConstraint(0));
        row.setY(new CramSiblingConstraint(8f));
        row.setWidth(new PixelConstraint(width));
        row.setHeight(new PixelConstraint(ROW_HEIGHT));
        row.setColor(rowColor);
        row.setChildOf(parent);
        row.enableEffect(new OutlineEffect(new Color(255, 255, 255, spec.selected ? 30 : 12), 1f));

        if (spec.action != null) {
            row.onMouseEnterRunnable(() -> {
                if (!spec.selected) {
                    row.setColor(ROW_HOVER);
                }
            });
            row.onMouseLeaveRunnable(() -> {
                if (!spec.selected) {
                    row.setColor(ROW_BG);
                }
            });
            row.onMouseClickConsumer(event -> spec.action.run());
        }

        createAccentBar(row, 0, 0, spec.selected ? 6 : 4, ROW_HEIGHT, spec.selected ? ACCENT : new Color(255, 255, 255, 18));
        createText(row, 18, 12, shorten(spec.title, 46), 1.0f, TEXT_PRIMARY);
        if (!spec.trailing.isBlank()) {
            createText(row, width - 104, 12, shorten(spec.trailing, 14), 0.9f, spec.selected ? ACCENT : TEXT_MUTED);
        }
        if (!spec.subtitle.isBlank()) {
            UIWrappedText subtitle = new UIWrappedText(spec.subtitle);
            subtitle.setX(new PixelConstraint(18));
            subtitle.setY(new PixelConstraint(30));
            subtitle.setWidth(new PixelConstraint(width - 36));
            subtitle.setHeight(new ChildBasedSizeConstraint(0f));
            subtitle.setTextScale(new PixelConstraint(0.88f));
            subtitle.setColor(TEXT_MUTED);
            subtitle.setChildOf(row);
        }
    }

    private List<RowSpec> buildListingRows() {
        List<RowSpec> rows = new ArrayList<>();
        for (int i = 0; i < data.listingEntries().size(); i++) {
            MarketOverviewData.ListingEntry entry = data.listingEntries().get(i);
            int index = i;
            rows.add(rowSpec(
                    entry.itemName(),
                    Component.translatable("screen.sailboatmod.market.action.buy_for",
                            entry.availableCount(), entry.unitPrice()).getString(),
                    entry.sellerName(),
                    index == selectedListingIndex,
                    () -> {
                        selectedListingIndex = index;
                        rebuildUi();
                    }
            ));
        }
        return rows;
    }

    private List<RowSpec> buildStorageRows() {
        List<RowSpec> rows = new ArrayList<>();
        for (int i = 0; i < data.dockStorageEntries().size(); i++) {
            MarketOverviewData.StorageEntry entry = data.dockStorageEntries().get(i);
            int index = i;
            rows.add(rowSpec(
                    entry.itemName(),
                    entry.detail(),
                    "x" + entry.quantity(),
                    index == selectedStorageIndex,
                    () -> {
                        selectedStorageIndex = index;
                        rebuildUi();
                    }
            ));
        }
        return rows;
    }

    private List<RowSpec> buildOrderRows() {
        List<RowSpec> rows = new ArrayList<>();
        for (int i = 0; i < data.orderEntries().size(); i++) {
            MarketOverviewData.OrderEntry entry = data.orderEntries().get(i);
            int index = i;
            rows.add(rowSpec(
                    entry.sourceDockName() + " -> " + entry.targetDockName(),
                    Component.translatable("screen.sailboatmod.market.dispatch.qty", entry.quantity()).getString(),
                    entry.status(),
                    index == selectedOrderIndex,
                    () -> {
                        selectedOrderIndex = index;
                        rebuildUi();
                    }
            ));
        }
        return rows;
    }

    private List<RowSpec> buildBoatRows() {
        List<RowSpec> rows = new ArrayList<>();
        for (int i = 0; i < data.shippingEntries().size(); i++) {
            MarketOverviewData.ShippingEntry entry = data.shippingEntries().get(i);
            int index = i;
            rows.add(rowSpec(
                    entry.boatName(),
                    entry.routeName().isBlank() ? Component.translatable("screen.sailboatmod.market.dispatch.select_boat").getString() : entry.routeName(),
                    entry.mode().isBlank() ? "" : entry.mode(),
                    index == selectedBoatIndex,
                    () -> {
                        selectedBoatIndex = index;
                        rebuildUi();
                    }
            ));
        }
        return rows;
    }

    private List<RowSpec> buildBuyOrderRows() {
        List<RowSpec> rows = new ArrayList<>();
        for (int i = 0; i < data.buyOrderEntries().size(); i++) {
            MarketOverviewData.BuyOrderEntry entry = data.buyOrderEntries().get(i);
            int index = i;
            rows.add(rowSpec(
                    entry.commodityKey(),
                    Component.translatable("screen.sailboatmod.market.buy_order.quantity", entry.quantity()).getString(),
                    entry.status(),
                    index == selectedBuyOrderIndex,
                    () -> {
                        selectedBuyOrderIndex = index;
                        rebuildUi();
                    }
            ));
        }
        return rows;
    }

    private List<String> buildHeaderPills() {
        List<String> pills = new ArrayList<>();
        pills.add(data.canManage()
                ? Component.translatable("screen.sailboatmod.market.finance.owner_controls").getString()
                : Component.translatable("screen.sailboatmod.market.finance.read_only").getString());
        pills.add(data.linkedDock()
                ? Component.translatable("screen.sailboatmod.market.linked_dock_value", data.linkedDockName(), data.linkedDockPosText()).getString()
                : Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString());
        if (data.hasTownEconomy()) {
            pills.add(Component.translatable("screen.sailboatmod.market.finance.town", data.townName()).getString());
        }
        return pills;
    }

    private String marketTitleLine() {
        return data.marketName().isBlank() ? title.getString() : data.marketName();
    }

    private String marketSubtitleLine() {
        return data.ownerName().isBlank()
                ? Component.translatable("screen.sailboatmod.market.finance.no_town").getString()
                : Component.translatable("screen.sailboatmod.market.finance.owner", data.ownerName()).getString();
    }

    private String activePageStatusText() {
        return switch (activePage) {
            case GOODS -> Component.translatable("screen.sailboatmod.market.action.buy_for",
                    data.listingEntries().size(), data.pendingCredits()).getString();
            case SELL -> data.dockStorageAccessible()
                    ? Component.translatable("screen.sailboatmod.market.sell_help").getString()
                    : Component.translatable("screen.sailboatmod.market.storage_owner_only").getString();
            case DISPATCH -> Component.translatable("screen.sailboatmod.market.dispatch.status",
                    selectedOrder() == null ? "-" : selectedOrder().status()).getString();
            case FINANCE -> Component.translatable("screen.sailboatmod.econbar.net", formatSignedLong(data.netBalance())).getString();
            case BUY_ORDERS -> Component.translatable("screen.sailboatmod.market.buy_order.price_range_hint").getString();
        };
    }

    private List<MetricCard> buildListingMetrics() {
        List<MetricCard> metrics = new ArrayList<>();
        MarketOverviewData.ListingEntry entry = selectedListing();
        if (entry != null) {
            int qty = parsePositive(buyQtyValue, 1);
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.price").getString(),
                    formatCompactLong(entry.unitPrice()), ACCENT));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.available").getString(),
                    Integer.toString(entry.availableCount()), POSITIVE));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.reserved").getString(),
                    Integer.toString(entry.reservedCount()), NEGATIVE));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.total").getString(),
                    formatCompactLong((long) entry.unitPrice() * qty), ACCENT_DIM));
        }
        return metrics;
    }

    private List<MetricCard> buildStorageMetrics() {
        List<MetricCard> metrics = new ArrayList<>();
        MarketOverviewData.StorageEntry entry = selectedStorage();
        if (entry != null) {
            int qty = parsePositive(listingQtyValue, 1);
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.storage").getString(),
                    Integer.toString(entry.quantity()), POSITIVE));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.price").getString(),
                    formatCompactLong(entry.suggestedUnitPrice()), ACCENT));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.total").getString(),
                    formatCompactLong((long) entry.suggestedUnitPrice() * qty), ACCENT_DIM));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.auto_price").getString(),
                    Component.translatable("screen.sailboatmod.market.metric.auto").getString(), new Color(95, 162, 198)));
        }
        return metrics;
    }

    private List<MetricCard> buildBuyOrderMetrics() {
        List<MetricCard> metrics = new ArrayList<>();
        MarketOverviewData.BuyOrderEntry entry = selectedBuyOrder();
        if (entry != null) {
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.buy_order.qty").getString(),
                    Integer.toString(entry.quantity()), POSITIVE));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.buy_order.min_price").getString(),
                    entry.minPriceBp() + " bp", ACCENT));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.buy_order.max_price").getString(),
                    entry.maxPriceBp() + " bp", ACCENT_DIM));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.status").getString(),
                    entry.status(), new Color(108, 146, 206)));
        }
        return metrics;
    }

    private List<MetricCard> buildActionMetrics() {
        List<MetricCard> metrics = new ArrayList<>();
        if (activePage == MarketPage.GOODS) {
            MarketOverviewData.ListingEntry entry = selectedListing();
            if (entry != null) {
                int qty = parsePositive(buyQtyValue, 1);
                metrics.add(metric(Component.translatable("screen.sailboatmod.market.qty").getString(), Integer.toString(qty), POSITIVE));
                metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.total").getString(),
                        formatCompactLong((long) entry.unitPrice() * qty), ACCENT_DIM));
            }
        } else if (activePage == MarketPage.SELL) {
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.qty").getString(),
                    Integer.toString(parsePositive(listingQtyValue, 1)), POSITIVE));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.price_adjust").getString(),
                    parsePriceAdjustment(listingPriceAdjustValue) + " bp", ACCENT));
        }
        return metrics;
    }

    private List<MetricCard> buildDispatchMetrics() {
        List<MetricCard> metrics = new ArrayList<>();
        MarketOverviewData.OrderEntry order = selectedOrder();
        MarketOverviewData.ShippingEntry ship = selectedShipping();
        if (order != null) {
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.dispatch.qty", order.quantity()).getString(),
                    order.sourceDockName(), ACCENT));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.dispatch.status", order.status()).getString(),
                    order.targetDockName(), ACCENT_DIM));
        }
        if (ship != null) {
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.dispatch.boat", ship.boatName()).getString(),
                    ship.routeName().isBlank() ? "-" : ship.routeName(), POSITIVE));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.dispatch.mode", ship.mode().isBlank() ? "-" : ship.mode()).getString(),
                    "", new Color(111, 149, 198)));
        }
        return metrics;
    }

    private List<String> buildListingDetailLines() {
        List<String> lines = new ArrayList<>();
        MarketOverviewData.ListingEntry entry = selectedListing();
        if (entry != null) {
            lines.add(Component.translatable("screen.sailboatmod.market.finance.owner", entry.sellerName()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.linked_dock_value", entry.sourceDockName(), entry.nationId()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.auto_price_help").getString());
            if (!entry.sellerNote().isBlank()) {
                lines.add(entry.sellerNote());
            }
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.market.empty").getString());
        return lines;
    }

    private List<String> buildStorageDetailLines() {
        List<String> lines = new ArrayList<>();
        MarketOverviewData.StorageEntry entry = selectedStorage();
        if (entry != null) {
            lines.add(entry.detail());
            lines.add(Component.translatable("screen.sailboatmod.market.action.post_at_rate",
                    parsePositive(listingQtyValue, 1)).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.auto_price_help").getString());
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.market.storage_empty").getString());
        return lines;
    }

    private List<String> buildGoodsActionLines() {
        List<String> lines = new ArrayList<>();
        if (!data.linkedDock()) {
            lines.add(Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString());
            lines.add(Component.translatable("screen.sailboatmod.market.action.bind_dock_hint").getString());
            return lines;
        }
        MarketOverviewData.ListingEntry entry = selectedListing();
        if (entry != null) {
            int qty = parsePositive(buyQtyValue, 1);
            lines.add(Component.translatable("screen.sailboatmod.market.action.buy_for",
                    qty, entry.unitPrice() * qty).getString());
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
        MarketOverviewData.StorageEntry entry = selectedStorage();
        if (entry != null) {
            lines.add(Component.translatable("screen.sailboatmod.market.action.post_at_rate",
                    parsePositive(listingQtyValue, 1)).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.sell_help").getString());
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.market.storage_empty").getString());
        return lines;
    }

    private List<String> buildDispatchActionLines() {
        List<String> lines = new ArrayList<>();
        MarketOverviewData.OrderEntry order = selectedOrder();
        MarketOverviewData.ShippingEntry boat = selectedShipping();
        if (order != null) {
            lines.add(order.sourceDockName() + " -> " + order.targetDockName());
            lines.add(Component.translatable("screen.sailboatmod.market.dispatch.qty", order.quantity()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.dispatch.status", order.status()).getString());
        } else {
            lines.add(Component.translatable("screen.sailboatmod.market.dispatch.select_order").getString());
        }
        if (boat != null) {
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

    private List<String> buildFinanceDetailLines() {
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.market.finance.market_name", data.marketName()).getString());
        lines.add(currentDockLine());
        lines.add(Component.translatable(data.dockStorageAccessible()
                ? "screen.sailboatmod.market.finance.storage_available"
                : "screen.sailboatmod.market.finance.storage_locked").getString());
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
        MarketOverviewData.BuyOrderEntry entry = selectedBuyOrder();
        if (entry != null) {
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
        return List.of(
                Component.translatable("screen.sailboatmod.market.buy_order.create_hint").getString(),
                Component.translatable("screen.sailboatmod.market.buy_order.price_range_hint").getString()
        );
    }

    private void refreshMarket() {
        send(MarketGuiActionPacket.Action.REFRESH);
    }

    private void createListing() {
        ModNetwork.CHANNEL.sendToServer(new CreateMarketListingPacket(
                data.marketPos(),
                selectedStorageIndex,
                parsePositive(listingQtyValue, 1),
                0,
                parsePriceAdjustment(listingPriceAdjustValue)
        ));
    }

    private void sendBuy() {
        ModNetwork.CHANNEL.sendToServer(new PurchaseMarketListingPacket(
                data.marketPos(),
                selectedListingIndex,
                parsePositive(buyQtyValue, 1)
        ));
    }

    private void cancelListing() {
        ModNetwork.CHANNEL.sendToServer(new CancelMarketListingPacket(data.marketPos(), selectedListingIndex));
    }

    private void dispatchSelectedOrder() {
        ModNetwork.CHANNEL.sendToServer(new DispatchMarketOrderPacket(data.marketPos(), selectedOrderIndex, selectedBoatIndex));
    }

    private void claimCredits() {
        ModNetwork.CHANNEL.sendToServer(new ClaimMarketCreditsPacket(data.marketPos()));
    }

    private void createBuyOrder() {
        ModNetwork.CHANNEL.sendToServer(new CreateBuyOrderPacket(
                data.marketPos(),
                buyOrderCommodityValue.trim(),
                parsePositive(buyOrderQtyValue, 1),
                parsePriceAdjustment(buyOrderMinPriceValue),
                parsePriceAdjustment(buyOrderMaxPriceValue)
        ));
    }

    private void cancelBuyOrder() {
        MarketOverviewData.BuyOrderEntry entry = selectedBuyOrder();
        if (entry != null) {
            ModNetwork.CHANNEL.sendToServer(new CancelBuyOrderPacket(data.marketPos(), entry.orderId()));
        }
    }

    private void send(MarketGuiActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new MarketGuiActionPacket(data.marketPos(), action));
    }

    private UIRoundedRectangle createSection(UIComponent parent, int x, int y, int width, int height, String title, String subtitle) {
        UIRoundedRectangle section = createPanel(parent, x, y, width, height, 16f, CARD_BG);
        section.enableEffect(new OutlineEffect(BORDER, 1f));
        createText(section, 14, 12, title, 1.0f, TEXT_SOFT);
        createText(section, 14, 28, subtitle, 0.92f, TEXT_PRIMARY);
        return section;
    }

    private UIRoundedRectangle createPanel(UIComponent parent, int x, int y, int width, int height, float radius, Color color) {
        UIRoundedRectangle panel = new UIRoundedRectangle(radius);
        panel.setX(new PixelConstraint(x));
        panel.setY(new PixelConstraint(y));
        panel.setWidth(new PixelConstraint(width));
        panel.setHeight(new PixelConstraint(height));
        panel.setColor(color);
        panel.setChildOf(parent);
        return panel;
    }

    private void createGlow(UIComponent parent, int x, int y, int width, int height, Color color) {
        UIBlock glow = new UIBlock(color);
        glow.setX(new PixelConstraint(x));
        glow.setY(new PixelConstraint(y));
        glow.setWidth(new PixelConstraint(width));
        glow.setHeight(new PixelConstraint(height));
        glow.setChildOf(parent);
    }

    private void createAccentBar(UIComponent parent, int x, int y, int width, int height, Color color) {
        UIBlock bar = new UIBlock(color);
        bar.setX(new PixelConstraint(x));
        bar.setY(new PixelConstraint(y));
        bar.setWidth(new PixelConstraint(width));
        bar.setHeight(new PixelConstraint(height));
        bar.setChildOf(parent);
    }

    private UIText createText(UIComponent parent, int x, int y, String text, float scale, Color color) {
        UIText label = new UIText(text);
        label.setX(new PixelConstraint(x));
        label.setY(new PixelConstraint(y));
        label.setTextScale(new PixelConstraint(scale));
        label.setColor(color);
        label.setChildOf(parent);
        return label;
    }

    private void createBadge(UIComponent parent, int x, int y, int width, int height, String text, Color color, Color textColor) {
        UIRoundedRectangle badge = createPanel(parent, x, y, width, height, 11f, color);
        badge.enableEffect(new OutlineEffect(BORDER, 1f));
        createText(badge, 12, 7, shorten(text, Math.max(12, width / 7)), 0.88f, textColor);
    }

    private UITextInput createInput(UIComponent parent, int x, int y, int width, int height,
                                    String placeholder, String value, Consumer<String> update) {
        UIRoundedRectangle frame = createPanel(parent, x, y, width, height, 12f, CARD_BG_SOFT);
        frame.enableEffect(new OutlineEffect(BORDER, 1f));

        UITextInput input = new UITextInput(placeholder);
        input.setX(new PixelConstraint(10));
        input.setY(new PixelConstraint(7));
        input.setWidth(new PixelConstraint(width - 20));
        input.setHeight(new PixelConstraint(height - 12));
        input.setColor(TEXT_PRIMARY);
        input.setText(value == null ? "" : value);
        input.onUpdate(text -> {
            update.accept(text);
            return Unit.INSTANCE;
        });
        input.setChildOf(frame);
        return input;
    }

    private void createButton(UIComponent parent, int x, int y, int width, int height,
                              String label, boolean enabled, boolean subtle, Runnable action) {
        Color base = enabled
                ? (subtle ? CHROME_MUTED : new Color(62, 91, 113, 255))
                : new Color(44, 52, 60, 230);
        Color hover = enabled
                ? (subtle ? new Color(73, 86, 97, 255) : new Color(80, 118, 145, 255))
                : base;

        UIRoundedRectangle button = createPanel(parent, x, y, width, height, 12f, base);
        button.enableEffect(new OutlineEffect(new Color(255, 255, 255, enabled ? 24 : 10), 1f));
        createText(button, 14, height / 2 - 6, label, 0.94f, enabled ? TEXT_PRIMARY : TEXT_SOFT);

        if (enabled) {
            button.onMouseEnterRunnable(() -> button.setColor(hover));
            button.onMouseLeaveRunnable(() -> button.setColor(base));
            button.onMouseClickConsumer(event -> action.run());
        }
    }

    private void createNavButton(UIComponent parent, MarketPage page) {
        boolean active = page == activePage;
        UIRoundedRectangle button = new UIRoundedRectangle(14f);
        button.setX(new PixelConstraint(0));
        button.setY(new CramSiblingConstraint(8f));
        button.setWidth(new PixelConstraint(NAV_WIDTH - 28));
        button.setHeight(new PixelConstraint(44));
        button.setColor(active ? ROW_SELECTED : ROW_BG);
        button.setChildOf(parent);
        button.enableEffect(new OutlineEffect(new Color(255, 255, 255, active ? 26 : 14), 1f));
        createAccentBar(button, 0, 0, active ? 6 : 4, 44, active ? ACCENT : new Color(255, 255, 255, 16));
        createText(button, 18, 11, Component.translatable(page.key).getString(), 1.0f, active ? TEXT_PRIMARY : TEXT_MUTED);
        if (active) {
            createText(button, 18, 25, page.kicker, 0.84f, ACCENT);
        }
        button.onMouseEnterRunnable(() -> {
            if (page != activePage) {
                button.setColor(ROW_HOVER);
            }
        });
        button.onMouseLeaveRunnable(() -> {
            if (page != activePage) {
                button.setColor(ROW_BG);
            }
        });
        button.onMouseClickConsumer(event -> {
            activePage = page;
            rebuildUi();
        });
    }

    private int createTabButton(UIComponent parent, int x, MarketPage page) {
        String label = Component.translatable(page.key).getString();
        int width = Math.max(92, label.length() * 10 + 28);
        boolean active = page == activePage;
        UIRoundedRectangle button = createPanel(parent, x, 6, width, 26, 10f, active ? ROW_SELECTED : ROW_BG);
        button.enableEffect(new OutlineEffect(new Color(255, 255, 255, active ? 24 : 10), 1f));
        createText(button, 12, 8, label, 0.92f, active ? TEXT_PRIMARY : TEXT_MUTED);
        button.onMouseEnterRunnable(() -> {
            if (page != activePage) {
                button.setColor(ROW_HOVER);
            }
        });
        button.onMouseLeaveRunnable(() -> {
            if (page != activePage) {
                button.setColor(ROW_BG);
            }
        });
        button.onMouseClickConsumer(event -> {
            activePage = page;
            rebuildUi();
        });
        return width + 8;
    }

    private void createMetricStrip(UIComponent parent, int x, int y, int width, List<MetricCard> metrics) {
        if (metrics.isEmpty()) {
            return;
        }
        int cardCount = Math.min(4, metrics.size());
        int cardWidth = (width - (cardCount - 1) * 8) / cardCount;
        for (int i = 0; i < cardCount; i++) {
            buildMetricTile(parent, x + i * (cardWidth + 8), y, cardWidth, 52,
                    metrics.get(i).label, metrics.get(i).value, metrics.get(i).accent);
        }
    }

    private void buildMetricTile(UIComponent parent, int x, int y, int width, int height, String label, String value, Color accent) {
        UIRoundedRectangle tile = createPanel(parent, x, y, width, height, 12f, CARD_BG_SOFT);
        tile.enableEffect(new OutlineEffect(new Color(255, 255, 255, 14), 1f));
        createAccentBar(tile, 0, 0, 4, height, accent);
        createText(tile, 14, 11, shorten(label, Math.max(10, width / 7)), 0.84f, TEXT_SOFT);
        createText(tile, 14, 27, shorten(value, Math.max(10, width / 7)), 1.04f, TEXT_PRIMARY);
    }

    private void buildTextStack(UIComponent parent, int x, int y, int width, List<String> lines, Color color) {
        UIBlock holder = new UIBlock(new Color(0, 0, 0, 0));
        holder.setX(new PixelConstraint(x));
        holder.setY(new PixelConstraint(y));
        holder.setWidth(new PixelConstraint(width));
        holder.setHeight(new ChildBasedSizeConstraint(0f));
        holder.setChildOf(parent);

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            UIWrappedText text = new UIWrappedText(line);
            text.setX(new PixelConstraint(0));
            text.setY(new CramSiblingConstraint(7f));
            text.setWidth(new PixelConstraint(width));
            text.setHeight(new ChildBasedSizeConstraint(0f));
            text.setTextScale(new PixelConstraint(0.9f));
            text.setColor(color);
            text.setChildOf(holder);
        }
    }

    private void clampSelections() {
        selectedListingIndex = clampSelection(selectedListingIndex, data.listingEntries().size());
        selectedOrderIndex = clampSelection(selectedOrderIndex, data.orderEntries().size());
        selectedBoatIndex = clampSelection(selectedBoatIndex, data.shippingEntries().size());
        selectedStorageIndex = clampSelection(selectedStorageIndex, data.dockStorageEntries().size());
        selectedBuyOrderIndex = clampSelection(selectedBuyOrderIndex, data.buyOrderEntries().size());
    }

    private int clampSelection(int current, int size) {
        if (size <= 0) {
            return 0;
        }
        return Math.max(0, Math.min(current, size - 1));
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
            return Math.max(-1000, Math.min(1000, Integer.parseInt(value.trim())));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String currentDockLine() {
        return data.linkedDock()
                ? Component.translatable("screen.sailboatmod.market.linked_dock_value", data.linkedDockName(), data.linkedDockPosText()).getString()
                : Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString();
    }

    private String detailHeadline() {
        return switch (activePage) {
            case GOODS -> selectedListing() == null ? "" : selectedListing().itemName();
            case SELL -> selectedStorage() == null ? "" : selectedStorage().itemName();
            case BUY_ORDERS -> selectedBuyOrder() == null ? "" : selectedBuyOrder().commodityKey();
            default -> "";
        };
    }

    private String actionCaption() {
        if (activePage == MarketPage.GOODS) {
            if (!data.linkedDock()) {
                return Component.translatable("screen.sailboatmod.market.status.bind_dock_first").getString();
            }
            return selectedListing() == null ? Component.translatable("screen.sailboatmod.market.empty").getString() : selectedListing().sellerName();
        }
        if (activePage == MarketPage.SELL) {
            if (!data.dockStorageAccessible()) {
                return Component.translatable("screen.sailboatmod.market.status.owner_required").getString();
            }
            return selectedStorage() == null ? Component.translatable("screen.sailboatmod.market.empty").getString()
                    : Component.translatable("screen.sailboatmod.market.auto_price_help").getString();
        }
        return "";
    }

    private String buildEconomyHeaderLine() {
        List<String> parts = new ArrayList<>();
        parts.add(Component.translatable("screen.sailboatmod.econbar.pending", formatCompactLong(data.pendingCredits())).getString());
        parts.add(Component.translatable("screen.sailboatmod.econbar.net", formatSignedLong(data.netBalance())).getString());
        if (data.hasTownEconomy()) {
            parts.add(Component.translatable("screen.sailboatmod.econbar.income", formatCompactLong(data.totalIncome())).getString());
            parts.add(Component.translatable("screen.sailboatmod.econbar.jobs", formatPercent(data.employmentRate())).getString());
        }
        return String.join("   ", parts);
    }

    private String formatPercent(float value) {
        return Math.round(Math.max(0.0F, value) * 100.0F) + "%";
    }

    private String formatSignedLong(long value) {
        return GoldStandardEconomy.formatSignedBalance(value);
    }

    private String formatCompactLong(long value) {
        return GoldStandardEconomy.formatBalance(value);
    }

    private String shorten(String value, int maxChars) {
        if (value == null || value.isBlank() || maxChars <= 0 || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private List<String> joinLines(List<String> first, List<String> second) {
        List<String> lines = new ArrayList<>();
        if (first != null) {
            lines.addAll(first);
        }
        if (second != null) {
            lines.addAll(second);
        }
        return lines;
    }

    private MarketOverviewData.ListingEntry selectedListing() {
        return selectedListingIndex >= 0 && selectedListingIndex < data.listingEntries().size()
                ? data.listingEntries().get(selectedListingIndex) : null;
    }

    private MarketOverviewData.StorageEntry selectedStorage() {
        return selectedStorageIndex >= 0 && selectedStorageIndex < data.dockStorageEntries().size()
                ? data.dockStorageEntries().get(selectedStorageIndex) : null;
    }

    private MarketOverviewData.OrderEntry selectedOrder() {
        return selectedOrderIndex >= 0 && selectedOrderIndex < data.orderEntries().size()
                ? data.orderEntries().get(selectedOrderIndex) : null;
    }

    private MarketOverviewData.ShippingEntry selectedShipping() {
        return selectedBoatIndex >= 0 && selectedBoatIndex < data.shippingEntries().size()
                ? data.shippingEntries().get(selectedBoatIndex) : null;
    }

    private MarketOverviewData.BuyOrderEntry selectedBuyOrder() {
        return selectedBuyOrderIndex >= 0 && selectedBuyOrderIndex < data.buyOrderEntries().size()
                ? data.buyOrderEntries().get(selectedBuyOrderIndex) : null;
    }

    private MetricCard metric(String label, String value, Color accent) {
        return new MetricCard(label, value, accent);
    }

    private RowSpec rowSpec(String title, String subtitle, String trailing, boolean selected, Runnable action) {
        return new RowSpec(title, subtitle, trailing, selected, action);
    }

    private MarketOverviewData empty(BlockPos pos) {
        return new MarketOverviewData(pos, "Market", "-", "", 0, false, "-", "-", false, false,
                "", "", 0, 0, 0, 0, 0, 0L, 0L, 0L, 0.0F,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private enum MarketPage {
        GOODS("screen.sailboatmod.market.page.goods", "BROWSE"),
        SELL("screen.sailboatmod.market.page.sell", "SELL"),
        DISPATCH("screen.sailboatmod.market.page.dispatch", "SHIP"),
        FINANCE("screen.sailboatmod.market.page.finance", "FUNDS"),
        BUY_ORDERS("screen.sailboatmod.market.page.buy_orders", "ORDERS");

        private final String key;
        private final String kicker;

        MarketPage(String key, String kicker) {
            this.key = key;
            this.kicker = kicker;
        }
    }

    private record MetricCard(String label, String value, Color accent) {
    }

    private record RowSpec(String title, String subtitle, String trailing, boolean selected, Runnable action) {
    }
}
