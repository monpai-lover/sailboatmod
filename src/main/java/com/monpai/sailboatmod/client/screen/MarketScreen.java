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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class MarketScreen extends WindowScreen implements MenuAccess<MarketMenu>, MarketOverviewConsumer {
    private static final int MAX_PANEL_WIDTH = 2400;
    private static final int MAX_PANEL_HEIGHT = 1600;
    private static final int NAV_WIDTH = 120;
    private static final int HEADER_HEIGHT = 48;
    private static final int PANEL_PADDING = 12;
    private static final int SECTION_GAP = 8;
    private static final int ROW_HEIGHT = 32;
    private static final int GOODS_CATALOG_PAGE_SIZE = 10;
    private static final DateTimeFormatter CHART_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter CHART_SHORT_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

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
    private GoodsPanelTab activeGoodsPanelTab = GoodsPanelTab.SELLING;
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
    private String goodsSearchValue = "";
    private String buyOrderCommodityValue = "";
    private String buyOrderQtyValue = "1";
    private String buyOrderMinPriceValue = "-1000";
    private String buyOrderMaxPriceValue = "1000";
    private String priceFilterMinValue = "";
    private String priceFilterMaxValue = "";
    private GoodsCatalogSort goodsCatalogSort = GoodsCatalogSort.PRICE_DESC;
    private GoodsCategoryFilter goodsCategoryFilter = GoodsCategoryFilter.ALL;
    private GoodsPriceBandFilter goodsPriceBandFilter = GoodsPriceBandFilter.ALL;
    private int goodsCatalogPage;
    private boolean showCategoryFilter = false;
    private boolean showPriceFilter = false;
    private float goodsViewportScrollOffset;
    private float buyOrdersViewportScrollOffset;
    private float goodsCatalogScrollOffset;
    private float buyOrdersListScrollOffset;
    private ScrollComponent goodsViewportScrollRef;
    private ScrollComponent buyOrdersViewportScrollRef;
    private ScrollComponent goodsCatalogScrollRef;
    private ScrollComponent buyOrdersListScrollRef;

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
        rememberScrollState();

        Window window = getWindow();
        window.clearChildren();

        int panelWidth = Math.min(MAX_PANEL_WIDTH, width - 8);
        int panelHeight = Math.min(MAX_PANEL_HEIGHT, height - 8);
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
        int contentY = HEADER_HEIGHT + 28;
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
        boolean compact = panelWidth < 800;
        UIRoundedRectangle header = createPanel(parent, PANEL_PADDING, PANEL_PADDING, panelWidth - PANEL_PADDING * 2, HEADER_HEIGHT, 12f, CHROME_BG);
        createAccentBar(header, 0, 0, 80, 3, ACCENT);

        UIRoundedRectangle iconCard = createPanel(header, 8, 6, 40, 40, 10f, CARD_BG_SOFT);
        iconCard.enableEffect(new OutlineEffect(BORDER, 1f));
        createText(iconCard, 6, 8, "BUFF", 0.7f, ACCENT);
        createText(iconCard, 6, 20, activePage.kicker, 0.55f, TEXT_PRIMARY);
        createText(iconCard, 6, 30, "MARKET", 0.48f, TEXT_SOFT);

        createText(header, 54, 8, marketTitleLine(), compact ? 0.75f : 0.85f, TEXT_PRIMARY);
        createText(header, 54, 22, marketSubtitleLine(), 0.65f, ACCENT_DIM);
        createText(header, 54, 35, currentDockLine(), 0.6f, data.linkedDock() ? TEXT_MUTED : NEGATIVE);

        int headerWidth = panelWidth - PANEL_PADDING * 2;
        int buttonY = 18;
        if (compact) {
            int actionX = headerWidth - 170;
            createButton(header, actionX, buttonY, 50, 20,
                    Component.translatable("screen.sailboatmod.market.refresh").getString(), true, false, this::refreshMarket);
            createButton(header, actionX + 55, buttonY, 65, 20,
                    Component.translatable("screen.sailboatmod.market.bind_dock").getString(), true, false,
                    () -> send(MarketGuiActionPacket.Action.BIND_NEAREST_DOCK));
            createButton(header, actionX + 125, buttonY, 45, 20,
                    Component.translatable("screen.sailboatmod.route_name.cancel").getString(), true, true, this::onClose);
        } else {
            int actionX = headerWidth - 190;
            createButton(header, actionX, buttonY, 55, 20,
                    Component.translatable("screen.sailboatmod.market.refresh").getString(), true, false, this::refreshMarket);
            createButton(header, actionX + 60, buttonY, 75, 20,
                    Component.translatable("screen.sailboatmod.market.bind_dock").getString(), true, false,
                    () -> send(MarketGuiActionPacket.Action.BIND_NEAREST_DOCK));
            createButton(header, actionX + 140, buttonY, 50, 20,
                    Component.translatable("screen.sailboatmod.route_name.cancel").getString(), true, true, this::onClose);
        }
    }

    private void buildTabs(UIComponent parent, int panelWidth) {
        UIRoundedRectangle tabs = createPanel(parent, PANEL_PADDING, HEADER_HEIGHT + 6, panelWidth - PANEL_PADDING * 2, 20, 10f, CHROME_BG);
        int x = 4;
        for (MarketPage page : MarketPage.values()) {
            x += createTabButton(tabs, x, page);
        }
    }

    private void buildGoodsPage(UIComponent parent, int x, int y, int width, int height) {
        if (height < 350) {
            ScrollComponent scroll = createViewportScroll(parent, x, y, width, height);
            goodsViewportScrollRef = scroll;
            buildGoodsPage(scroll, 0, 0, width, 450);
            restoreScroll(scroll, goodsViewportScrollOffset);
            return;
        }
        if (width < 850) {
            ScrollComponent scroll = createViewportScroll(parent, x, y, width, height);
            goodsViewportScrollRef = scroll;
            int overviewHeight = 140;
            int listHeight = 240;
            int actionHeight = Math.max(350, height - overviewHeight - listHeight - SECTION_GAP * 2);

            UIRoundedRectangle overviewPanel = createSection(scroll, 0, 0, width, overviewHeight,
                    Component.translatable("screen.sailboatmod.market.market_overview").getString(),
                    Component.translatable("screen.sailboatmod.market.status.live_feed").getString());
            buildGoodsOverviewPanel(overviewPanel, width);

            UIRoundedRectangle listPanel = createSection(scroll, 0, overviewHeight + SECTION_GAP, width, listHeight,
                    Component.translatable("screen.sailboatmod.market.tab.goods").getString(),
                    Component.translatable("screen.sailboatmod.market.goods_subtab.market").getString());
            buildGoodsCatalogPanel(listPanel, width, listHeight);

            UIRoundedRectangle actionPanel = createSection(scroll, 0, overviewHeight + listHeight + SECTION_GAP * 2, width, actionHeight,
                    selectedListing() == null ? Component.translatable("screen.sailboatmod.market.page.goods").getString() : selectedListing().itemName(),
                    goodsPanelSubtitle());
            buildGoodsActionPanel(actionPanel, width);
            restoreScroll(scroll, goodsViewportScrollOffset);
            return;
        }

        int sidebarWidth = responsiveSidebarWidth(width);
        int listWidth = width - sidebarWidth - SECTION_GAP;
        int overviewHeight = 140;
        int listHeight = height - overviewHeight - SECTION_GAP;

        UIRoundedRectangle overviewPanel = createSection(parent, x, y, listWidth, overviewHeight,
                Component.translatable("screen.sailboatmod.market.market_overview").getString(),
                Component.translatable("screen.sailboatmod.market.status.live_feed").getString());
        buildGoodsOverviewPanel(overviewPanel, listWidth);

        UIRoundedRectangle listPanel = createSection(parent, x, y + overviewHeight + SECTION_GAP, listWidth, listHeight,
                Component.translatable("screen.sailboatmod.market.tab.goods").getString(),
                Component.translatable("screen.sailboatmod.market.goods_subtab.market").getString());
        buildGoodsCatalogPanel(listPanel, listWidth, listHeight);

        UIRoundedRectangle actionPanel = createSection(parent, x + listWidth + SECTION_GAP, y, sidebarWidth, height,
                selectedListing() == null ? Component.translatable("screen.sailboatmod.market.page.goods").getString() : selectedListing().itemName(),
                goodsPanelSubtitle());
        buildGoodsActionPanel(actionPanel, sidebarWidth);
    }

    private void buildSellPage(UIComponent parent, int x, int y, int width, int height) {
        int sidebarWidth = Math.min(280, responsiveSidebarWidth(width));
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
        int orderWidth = Math.min(280, width / 3);
        int boatWidth = Math.min(260, width / 3);
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
        int leftWidth = Math.min(300, width / 3);
        int centerWidth = Math.min(240, width / 3);
        int rightWidth = width - leftWidth - centerWidth - SECTION_GAP * 2;
        int topHeight = 140;
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
        if (height < 350) {
            ScrollComponent scroll = createViewportScroll(parent, x, y, width, height);
            buyOrdersViewportScrollRef = scroll;
            buildBuyOrdersPage(scroll, 0, 0, width, 450);
            restoreScroll(scroll, buyOrdersViewportScrollOffset);
            return;
        }
        if (width < 850) {
            ScrollComponent scroll = createViewportScroll(parent, x, y, width, height);
            buyOrdersViewportScrollRef = scroll;
            int overviewHeight = 90;
            int listHeight = 220;
            int actionHeight = Math.max(350, height - overviewHeight - listHeight - SECTION_GAP * 2);

            UIRoundedRectangle overviewPanel = createSection(scroll, 0, 0, width, overviewHeight,
                    Component.translatable("screen.sailboatmod.market.buy_orders_overview").getString(),
                    Component.translatable("screen.sailboatmod.market.buy_order.create_hint").getString());
            buildBuyOrdersOverviewPanel(overviewPanel, width);

            UIRoundedRectangle listPanel = createSection(scroll, 0, overviewHeight + SECTION_GAP, width, listHeight,
                    Component.translatable("screen.sailboatmod.market.tab.buy_orders").getString(),
                    Component.translatable("screen.sailboatmod.market.buy_orders_manage").getString());
            buildBuyOrdersListPanel(listPanel, data.buyOrderEntries().isEmpty()
                            ? List.of(rowSpec(Component.translatable("screen.sailboatmod.market.empty").getString(), "", "", false, null))
                            : buildBuyOrderRows(),
                    width - 28, listHeight - 68);

            UIRoundedRectangle actionPanel = createSection(scroll, 0, overviewHeight + listHeight + SECTION_GAP * 2, width, actionHeight,
                    selectedBuyOrder() == null ? Component.translatable("screen.sailboatmod.market.page.buy_orders").getString() : displayCommodityName(selectedBuyOrder().commodityKey()),
                    buildBuyOrdersSubtitle());
            buildBuyOrderActionPanel(actionPanel, width);
            restoreScroll(scroll, buyOrdersViewportScrollOffset);
            return;
        }

        int sidebarWidth = responsiveSidebarWidth(width);
        int listWidth = width - sidebarWidth - SECTION_GAP;
        int overviewHeight = 90;
        int listHeight = height - overviewHeight - SECTION_GAP;

        UIRoundedRectangle overviewPanel = createSection(parent, x, y, listWidth, overviewHeight,
                Component.translatable("screen.sailboatmod.market.buy_orders_overview").getString(),
                Component.translatable("screen.sailboatmod.market.buy_order.create_hint").getString());
        buildBuyOrdersOverviewPanel(overviewPanel, listWidth);

        UIRoundedRectangle listPanel = createSection(parent, x, y + overviewHeight + SECTION_GAP, listWidth, listHeight,
                Component.translatable("screen.sailboatmod.market.tab.buy_orders").getString(),
                Component.translatable("screen.sailboatmod.market.buy_orders_manage").getString());
        buildBuyOrdersListPanel(listPanel, data.buyOrderEntries().isEmpty()
                        ? List.of(rowSpec(Component.translatable("screen.sailboatmod.market.empty").getString(), "", "", false, null))
                        : buildBuyOrderRows(),
                listWidth - 28, listHeight - 68);

        UIRoundedRectangle actionPanel = createSection(parent, x + listWidth + SECTION_GAP, y, sidebarWidth, height,
                selectedBuyOrder() == null ? Component.translatable("screen.sailboatmod.market.page.buy_orders").getString() : displayCommodityName(selectedBuyOrder().commodityKey()),
                buildBuyOrdersSubtitle());
        buildBuyOrderActionPanel(actionPanel, sidebarWidth);
    }

    private void buildGoodsActionPanel(UIComponent panel, int width) {
        MarketOverviewData.ListingEntry listing = selectedListing();
        int innerWidth = width - 28;
        createGoodsPanelTab(panel, 14, 48, 94,
                Component.translatable("screen.sailboatmod.market.goods_subtab.market").getString(),
                activeGoodsPanelTab == GoodsPanelTab.SELLING,
                () -> {
                    activeGoodsPanelTab = GoodsPanelTab.SELLING;
                    rebuildUi();
                });
        createGoodsPanelTab(panel, 114, 48, 94,
                Component.translatable("screen.sailboatmod.market.goods_subtab.buying").getString(),
                activeGoodsPanelTab == GoodsPanelTab.BUYING,
                () -> {
                    activeGoodsPanelTab = GoodsPanelTab.BUYING;
                    rebuildUi();
                });
        createGoodsPanelTab(panel, 214, 48, innerWidth - 200,
                Component.translatable("screen.sailboatmod.market.price_chart").getString(),
                activeGoodsPanelTab == GoodsPanelTab.PRICE_CHART,
                () -> {
                    activeGoodsPanelTab = GoodsPanelTab.PRICE_CHART;
                    rebuildUi();
                });

        if (activeGoodsPanelTab == GoodsPanelTab.BUYING) {
            buildBuyingPanel(panel, innerWidth);
            return;
        }
        if (activeGoodsPanelTab == GoodsPanelTab.PRICE_CHART) {
            buildPriceChartPanel(panel, innerWidth);
            return;
        }

        buildTextStack(panel, 14, 88, innerWidth, buildListingDetailLines(), TEXT_MUTED);
        createMetricStrip(panel, 14, 164, innerWidth, buildActionMetrics());

        UITextInput qtyInput = createInput(panel, 14, 230, innerWidth, 26,
                Component.translatable("screen.sailboatmod.market.buy_qty").getString(), buyQtyValue, value -> buyQtyValue = value);
        qtyInput.onActivate(value -> {
            buyQtyValue = value;
            sendBuy();
            return Unit.INSTANCE;
        });

        createButton(panel, 14, 262, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.buy").getString(),
                listing != null && data.linkedDock(), false, this::sendBuy);
        createButton(panel, 14, 298, innerWidth, 26,
                Component.translatable("screen.sailboatmod.market.cancel").getString(),
                listing != null && data.canManage(), true, this::cancelListing);

        buildTextStack(panel, 14, 330, innerWidth, buildGoodsActionLines(), TEXT_MUTED);
    }

    private void buildGoodsOverviewPanel(UIComponent panel, int width) {
        int innerWidth = width - 28;
        createMetricStrip(panel, 14, 44, innerWidth, buildGoodsOverviewMetrics());
        buildHotCommodityCards(panel, innerWidth);
        buildTextStack(panel, 14, 146, innerWidth, buildGoodsOverviewLines(), TEXT_MUTED);
    }

    private void buildHotCommodityCards(UIComponent panel, int innerWidth) {
        List<HotCommodity> hot = buildHotCommodities();
        if (hot.isEmpty()) {
            return;
        }
        int count = Math.min(3, hot.size());
        int cardWidth = (innerWidth - (count - 1) * 10) / count;
        for (int i = 0; i < count; i++) {
            HotCommodity commodity = hot.get(i);
            int x = 14 + i * (cardWidth + 10);
            UIRoundedRectangle card = createPanel(panel, x, 90, cardWidth, 40, 12f, CARD_BG_SOFT);
            card.enableEffect(new OutlineEffect(new Color(255, 255, 255, 14), 1f));
            createAccentBar(card, 0, 0, 4, 40, i == 0 ? ACCENT : i == 1 ? new Color(95, 162, 198) : POSITIVE);
            createText(card, 12, 7, shorten(commodity.itemName(), Math.max(10, cardWidth / 9)), 0.8f, TEXT_PRIMARY);
            createText(card, 12, 22, commodity.summary(), 0.68f, TEXT_SOFT);
            card.onMouseEnterRunnable(() -> card.setColor(ROW_HOVER));
            card.onMouseLeaveRunnable(() -> card.setColor(CARD_BG_SOFT));
            card.onMouseClickConsumer(event -> {
                selectedListingIndex = commodity.listingIndex();
                rebuildUi();
            });
        }
    }

    private void buildGoodsCatalogPanel(UIComponent panel, int width, int height) {
        int innerWidth = width - 28;
        int controlWidth = Math.min(250, Math.max(200, innerWidth / 3));
        UITextInput searchInput = createInput(panel, 14, 44, controlWidth, 24,
                Component.translatable("screen.sailboatmod.market.catalog.search").getString(),
                goodsSearchValue, value -> goodsSearchValue = value);
        searchInput.onActivate(value -> {
            goodsSearchValue = value;
            goodsCatalogPage = 0;
            rebuildUi();
            return Unit.INSTANCE;
        });

        int sortX = 14 + controlWidth + 8;
        for (GoodsCatalogSort sort : GoodsCatalogSort.values()) {
            int buttonWidth = Math.max(72, Component.translatable(sort.labelKey).getString().length() * 7 + 16);
            createGoodsPanelTab(panel, sortX, 44, buttonWidth, Component.translatable(sort.labelKey).getString(),
                    goodsCatalogSort == sort, () -> {
                        goodsCatalogSort = sort;
                        goodsCatalogPage = 0;
                        rebuildUi();
                    });
            sortX += buttonWidth + 8;
        }

        int filterX = 14;
        String categoryLabel = Component.translatable("screen.sailboatmod.market.catalog.filter.category").getString()
                + ": " + Component.translatable(goodsCategoryFilter.labelKey).getString();
        int categoryWidth = Math.max(100, categoryLabel.length() * 7 + 16);
        createGoodsPanelTab(panel, filterX, 72, categoryWidth, categoryLabel,
                showCategoryFilter, () -> {
                    showCategoryFilter = !showCategoryFilter;
                    showPriceFilter = false;
                    rebuildUi();
                });

        if (showCategoryFilter) {
            int dropY = 96;
            for (GoodsCategoryFilter filter : GoodsCategoryFilter.values()) {
                createDropdownOption(panel, filterX, dropY, categoryWidth, filter.labelKey,
                        goodsCategoryFilter == filter, () -> {
                            goodsCategoryFilter = filter;
                            showCategoryFilter = false;
                            goodsCatalogPage = 0;
                            rebuildUi();
                        });
                dropY += 24;
            }
        }

        int priceX = filterX + categoryWidth + 8;
        String priceLabel = Component.translatable("screen.sailboatmod.market.catalog.filter.price").getString();
        int priceWidth = Math.max(100, priceLabel.length() * 7 + 16);
        createGoodsPanelTab(panel, priceX, 72, priceWidth, priceLabel,
                showPriceFilter, () -> {
                    showPriceFilter = !showPriceFilter;
                    showCategoryFilter = false;
                    rebuildUi();
                });

        if (showPriceFilter) {
            UIRoundedRectangle pricePanel = createPanel(panel, priceX, 96, priceWidth + 120, 80, 10f, CARD_BG_SOFT);
            pricePanel.enableEffect(new OutlineEffect(BORDER, 1f));

            createText(pricePanel, 8, 6, Component.translatable("screen.sailboatmod.market.catalog.price.min").getString(), 0.7f, TEXT_SOFT);
            UITextInput minInput = createInput(pricePanel, 8, 20, (priceWidth + 120) / 2 - 12, 22,
                    "0", priceFilterMinValue, value -> priceFilterMinValue = value);
            minInput.onActivate(value -> {
                priceFilterMinValue = value;
                goodsCatalogPage = 0;
                rebuildUi();
                return Unit.INSTANCE;
            });

            createText(pricePanel, (priceWidth + 120) / 2 + 4, 6, Component.translatable("screen.sailboatmod.market.catalog.price.max").getString(), 0.7f, TEXT_SOFT);
            UITextInput maxInput = createInput(pricePanel, (priceWidth + 120) / 2 + 4, 20, (priceWidth + 120) / 2 - 12, 22,
                    "999999", priceFilterMaxValue, value -> priceFilterMaxValue = value);
            maxInput.onActivate(value -> {
                priceFilterMaxValue = value;
                goodsCatalogPage = 0;
                rebuildUi();
                return Unit.INSTANCE;
            });

            createButton(pricePanel, 8, 48, priceWidth + 104, 24,
                    Component.translatable("screen.sailboatmod.market.catalog.price.apply").getString(),
                    true, false, () -> {
                        showPriceFilter = false;
                        goodsCatalogPage = 0;
                        rebuildUi();
                    });
        }

        List<Integer> filtered = filteredListingIndices();
        goodsCatalogPage = clampGoodsCatalogPage(filtered.size());
        buildGoodsCatalogSummary(panel, innerWidth, filtered.size(), data.listingEntries().size());
        buildGoodsCatalogHeader(panel, innerWidth);
        buildGoodsCatalogRows(panel, innerWidth, Math.max(120, height - 172), filtered);
    }

    private void buildGoodsCatalogSummary(UIComponent panel, int innerWidth, int filteredCount, int totalCount) {
        createText(panel, 14, 100, buildGoodsCatalogSummaryText(filteredCount, totalCount), 0.76f, TEXT_MUTED);
        createText(panel, 14, 114, buildGoodsCatalogFilterSummary(), 0.7f, TEXT_SOFT);

        int pageCount = goodsCatalogPageCount(filteredCount);
        int baseX = innerWidth - 190;
        createButton(panel, baseX, 98, 58, 22,
                Component.translatable("screen.sailboatmod.market.catalog.prev").getString(),
                goodsCatalogPage > 0, true, () -> {
                    goodsCatalogPage = Math.max(0, goodsCatalogPage - 1);
                    rebuildUi();
                });
        createText(panel, baseX + 66, 105,
                Component.translatable("screen.sailboatmod.market.catalog.page", goodsCatalogPage + 1, pageCount).getString(),
                0.74f, TEXT_MUTED);
        createButton(panel, baseX + 126, 98, 58, 22,
                Component.translatable("screen.sailboatmod.market.catalog.next").getString(),
                goodsCatalogPage + 1 < pageCount, true, () -> {
                    goodsCatalogPage = Math.min(pageCount - 1, goodsCatalogPage + 1);
                    rebuildUi();
                });
    }

    private void buildGoodsCatalogHeader(UIComponent panel, int innerWidth) {
        UIRoundedRectangle header = createPanel(panel, 14, 132, innerWidth, 20, 8f, CHROME_MUTED);
        createText(header, 12, 5, Component.translatable("screen.sailboatmod.market.catalog.column.item").getString(), 0.68f, TEXT_SOFT);
        createText(header, goodsCatalogSellerX(innerWidth), 5, Component.translatable("screen.sailboatmod.market.catalog.column.seller").getString(), 0.68f, TEXT_SOFT);
        createText(header, goodsCatalogStockX(innerWidth), 5, Component.translatable("screen.sailboatmod.market.catalog.column.stock").getString(), 0.68f, TEXT_SOFT);
        createText(header, goodsCatalogPriceX(innerWidth), 5, Component.translatable("screen.sailboatmod.market.catalog.column.price").getString(), 0.68f, TEXT_SOFT);
    }

    private void buildGoodsCatalogRows(UIComponent panel, int width, int height, List<Integer> filtered) {
        List<Integer> visible = pagedListingIndices(filtered);
        if (!visible.isEmpty() && !visible.contains(selectedListingIndex)) {
            selectedListingIndex = visible.get(0);
        }

        ScrollComponent scroll = new ScrollComponent();
        scroll.setX(new PixelConstraint(14));
        scroll.setY(new PixelConstraint(156));
        scroll.setWidth(new PixelConstraint(width));
        scroll.setHeight(new PixelConstraint(height));
        scroll.setChildOf(panel);
        scroll.setColor(new Color(0, 0, 0, 0));
        goodsCatalogScrollRef = scroll;

        if (visible.isEmpty()) {
            createRow(scroll, width - 8,
                    rowSpec(Component.translatable("screen.sailboatmod.market.empty").getString(),
                            Component.translatable("screen.sailboatmod.market.catalog.empty_hint").getString(),
                            "", false, null));
            return;
        }

        for (int index : visible) {
            createGoodsCatalogRow(scroll, width - 8, data.listingEntries().get(index), index);
        }
        restoreScroll(scroll, goodsCatalogScrollOffset);
    }

    private void createGoodsCatalogRow(UIComponent parent, int width, MarketOverviewData.ListingEntry entry, int index) {
        boolean selected = index == selectedListingIndex;
        UIRoundedRectangle row = new UIRoundedRectangle(10f);
        row.setX(new PixelConstraint(0));
        row.setY(new CramSiblingConstraint(6f));
        row.setWidth(new PixelConstraint(width));
        row.setHeight(new PixelConstraint(36));
        row.setColor(selected ? ROW_SELECTED : ROW_BG);
        row.setChildOf(parent);
        row.enableEffect(new OutlineEffect(new Color(255, 255, 255, selected ? 28 : 10), 1f));

        row.onMouseEnterRunnable(() -> {
            if (!selected) {
                row.setColor(ROW_HOVER);
            }
        });
        row.onMouseLeaveRunnable(() -> {
            if (!selected) {
                row.setColor(ROW_BG);
            }
        });
        row.onMouseClickConsumer(event -> {
            selectedListingIndex = index;
            rebuildUi();
        });

        createAccentBar(row, 0, 0, selected ? 5 : 3, 36, selected ? ACCENT : new Color(255, 255, 255, 18));
        createText(row, 12, 5, shorten(entry.itemName(), 30), 0.72f, TEXT_PRIMARY);
        createText(row, 12, 18, shorten(entry.sourceDockName().isBlank() ? entry.commodityKey() : entry.sourceDockName(), 32), 0.58f, TEXT_SOFT);
        createText(row, goodsCatalogSellerX(width), 10, shorten(entry.sellerName(), 16), 0.65f, TEXT_MUTED);
        createText(row, goodsCatalogStockX(width), 10, Integer.toString(entry.availableCount()), 0.68f, entry.availableCount() > 0 ? POSITIVE : NEGATIVE);
        createText(row, goodsCatalogPriceX(width), 10, formatCompactLong(entry.unitPrice()), 0.72f, selected ? ACCENT : TEXT_PRIMARY);
    }

    private int goodsCatalogSellerX(int width) {
        return Math.max(140, width - 240);
    }

    private int goodsCatalogStockX(int width) {
        return Math.max(200, width - 140);
    }

    private int goodsCatalogPriceX(int width) {
        return Math.max(250, width - 80);
    }

    private void buildBuyingPanel(UIComponent panel, int innerWidth) {
        MarketOverviewData.ListingEntry listing = selectedListing();
        MarketOverviewData.CommodityBuyBook book = selectedBuyBook();
        if (listing == null) {
            buildTextStack(panel, 14, 92, innerWidth,
                    List.of(Component.translatable("screen.sailboatmod.market.empty").getString()), TEXT_MUTED);
            return;
        }
        buildTextStack(panel, 14, 88, innerWidth,
                List.of(Component.translatable("screen.sailboatmod.market.buy_order.commodity", selectedChartDisplayName()).getString()),
                TEXT_MUTED);
        createMetricStrip(panel, 14, 120, innerWidth, buildBuyingMetrics(listing, book));

        UIRoundedRectangle depthPanel = createPanel(panel, 14, 186, innerWidth, 188, 14f, CHROME_MUTED);
        depthPanel.enableEffect(new OutlineEffect(BORDER, 1f));
        createText(depthPanel, 14, 10, Component.translatable("screen.sailboatmod.market.goods_subtab.buying").getString(), 0.9f, TEXT_PRIMARY);
        buildListPanel(depthPanel, buildBuyingRows(listing, book), innerWidth - 28, 128);

        buildTextStack(panel, 14, 384, innerWidth, buildBuyingLines(listing, book), TEXT_MUTED);
    }

    private void buildBuyOrdersOverviewPanel(UIComponent panel, int width) {
        int innerWidth = width - 28;
        createMetricStrip(panel, 14, 42, innerWidth, buildBuyOrdersOverviewMetrics());
        buildTextStack(panel, 14, 94, innerWidth, buildBuyOrdersOverviewLines(), TEXT_MUTED);
    }

    private void createGoodsPanelTab(UIComponent parent, int x, int y, int width, String label, boolean active, Runnable action) {
        UIRoundedRectangle button = createPanel(parent, x, y, width, 22, 10f, active ? ROW_SELECTED : ROW_BG);
        button.enableEffect(new OutlineEffect(new Color(255, 255, 255, active ? 24 : 10), 1f));
        createText(button, 10, 6, shorten(label, Math.max(6, width / 8)), 0.8f, active ? TEXT_PRIMARY : TEXT_MUTED);
        button.onMouseEnterRunnable(() -> {
            if (!active) {
                button.setColor(ROW_HOVER);
            }
        });
        button.onMouseLeaveRunnable(() -> {
            if (!active) {
                button.setColor(ROW_BG);
            }
        });
        button.onMouseClickConsumer(event -> action.run());
    }

    private void createDropdownOption(UIComponent parent, int x, int y, int width, String labelKey, boolean selected, Runnable action) {
        UIRoundedRectangle option = createPanel(parent, x, y, width, 22, 8f, selected ? ROW_SELECTED : CARD_BG_SOFT);
        option.enableEffect(new OutlineEffect(new Color(255, 255, 255, selected ? 20 : 8), 1f));
        createText(option, 10, 6, Component.translatable(labelKey).getString(), 0.75f, selected ? ACCENT : TEXT_PRIMARY);
        option.onMouseEnterRunnable(() -> {
            if (!selected) {
                option.setColor(ROW_HOVER);
            }
        });
        option.onMouseLeaveRunnable(() -> {
            if (!selected) {
                option.setColor(CARD_BG_SOFT);
            }
        });
        option.onMouseClickConsumer(event -> action.run());
    }

    private void buildPriceChartPanel(UIComponent panel, int innerWidth) {
        MarketOverviewData.ListingEntry listing = selectedListing();
        MarketOverviewData.PriceChartSeries series = selectedPriceChartSeries();
        if (listing == null) {
            buildTextStack(panel, 14, 94, innerWidth,
                    List.of(Component.translatable("screen.sailboatmod.market.empty").getString()), TEXT_MUTED);
            return;
        }

        buildTextStack(panel, 14, 90, innerWidth,
                List.of(Component.translatable("screen.sailboatmod.market.buy_order.commodity", selectedChartDisplayName()).getString()),
                TEXT_MUTED);
        createMetricStrip(panel, 14, 122, innerWidth, buildPriceChartMetrics(series, listing));
        buildPriceChartFrame(panel, 14, 188, innerWidth, 214, series);
        buildTextStack(panel, 14, 414, innerWidth, buildPriceChartLines(series, listing), TEXT_MUTED);
    }

    private void buildPriceChartFrame(UIComponent parent, int x, int y, int width, int height, MarketOverviewData.PriceChartSeries series) {
        UIRoundedRectangle frame = createPanel(parent, x, y, width, height, 14f, CHROME_MUTED);
        frame.enableEffect(new OutlineEffect(BORDER, 1f));
        createText(frame, 14, 10, Component.translatable("screen.sailboatmod.market.price_chart").getString(), 0.9f, TEXT_PRIMARY);
        if (series != null && !series.points().isEmpty()) {
            createText(frame, width - 98, 10, series.points().size() + " buckets", 0.82f, TEXT_SOFT);
        }

        UIRoundedRectangle plot = createPanel(frame, 12, 34, width - 24, height - 46, 12f, new Color(16, 23, 31, 235));
        plot.enableEffect(new OutlineEffect(new Color(255, 255, 255, 10), 1f));

        if (series == null || series.points().isEmpty()) {
            buildTextStack(plot, 14, 42, width - 52,
                    List.of(Component.translatable("screen.sailboatmod.market.price_chart.empty").getString()), TEXT_MUTED);
            return;
        }

        List<MarketOverviewData.PriceChartPoint> points = series.points();
        int plotWidth = width - 48;
        int plotHeight = height - 70;
        int chartLeft = 10;
        int chartTop = 10;
        int chartBottom = chartTop + plotHeight;
        int maxPrice = 0;
        int minPrice = Integer.MAX_VALUE;
        int maxVolume = 0;
        for (MarketOverviewData.PriceChartPoint point : points) {
            maxPrice = Math.max(maxPrice, point.maxUnitPrice());
            minPrice = Math.min(minPrice, point.minUnitPrice());
            maxVolume = Math.max(maxVolume, point.volume());
        }
        if (minPrice == Integer.MAX_VALUE) {
            minPrice = 0;
        }
        if (maxPrice <= minPrice) {
            maxPrice = minPrice + 1;
        }

        int volumeMaxHeight = Math.max(18, plotHeight / 3);
        int lineTop = chartTop + 4;
        int lineBottom = chartBottom - volumeMaxHeight - 10;
        int lineHeight = Math.max(24, lineBottom - lineTop);
        int range = Math.max(1, maxPrice - minPrice);
        int barWidth = Math.max(4, Math.min(14, (plotWidth - 20) / Math.max(1, points.size())));

        for (int i = 0; i < 4; i++) {
            int lineY = lineTop + Math.round((lineHeight * i) / 3.0f);
            createAccentBar(plot, chartLeft, lineY, plotWidth - 8, 1, new Color(255, 255, 255, 10));
        }

        createText(plot, chartLeft, 4, formatCompactLong(maxPrice), 0.74f, TEXT_SOFT);
        createText(plot, chartLeft, lineTop + (lineHeight / 2) - 4, formatCompactLong((maxPrice + minPrice) / 2L), 0.72f, TEXT_SOFT);
        createText(plot, chartLeft, chartBottom - 10, formatCompactLong(minPrice), 0.74f, TEXT_SOFT);

        int previousCenterX = -1;
        int previousY = -1;
        for (int i = 0; i < points.size(); i++) {
            MarketOverviewData.PriceChartPoint point = points.get(i);
            int xPos = points.size() == 1
                    ? chartLeft + (plotWidth / 2) - (barWidth / 2)
                    : chartLeft + Math.round((plotWidth - barWidth - 8) * (i / (float) Math.max(1, points.size() - 1)));
            int centerX = xPos + (barWidth / 2);
            int yPos = lineTop + Math.round((maxPrice - point.averageUnitPrice()) * (lineHeight / (float) range));
            int volumeHeight = maxVolume <= 0 ? 0 : Math.max(3, Math.round(point.volume() * (volumeMaxHeight / (float) maxVolume)));

            createAccentBar(plot, xPos, chartBottom - volumeHeight, Math.max(3, barWidth - 1), volumeHeight, new Color(96, 146, 214, 118));
            createAccentBar(plot, centerX - 1, yPos, 2, Math.max(1, lineBottom - yPos), new Color(240, 186, 96, 34));
            createPanel(plot, centerX - 3, yPos - 3, 6, 6, 3f, i == points.size() - 1 ? ACCENT : ACCENT_DIM);

            if (previousCenterX >= 0) {
                drawChartLine(plot, previousCenterX, previousY, centerX, yPos, i == points.size() - 1 ? ACCENT : ACCENT_DIM, 2);
            }
            previousCenterX = centerX;
            previousY = yPos;
        }

        int middleIndex = points.size() / 2;
        createText(plot, chartLeft, chartBottom + 4, formatChartTime(points.get(0).bucketAt(), false), 0.72f, TEXT_SOFT);
        createText(plot, Math.max(chartLeft, (plotWidth / 2) - 24), chartBottom + 4,
                formatChartTime(points.get(middleIndex).bucketAt(), true), 0.72f, TEXT_SOFT);
        createText(plot, Math.max(chartLeft, plotWidth - 86), chartBottom + 4,
                formatChartTime(points.get(points.size() - 1).bucketAt(), false), 0.72f, TEXT_SOFT);
    }

    private void drawChartLine(UIComponent parent, int x1, int y1, int x2, int y2, Color color, int thickness) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            createAccentBar(parent, x1, y1, thickness, thickness, color);
            return;
        }
        for (int step = 0; step <= steps; step++) {
            float progress = step / (float) steps;
            int x = Math.round(x1 + dx * progress);
            int y = Math.round(y1 + dy * progress);
            createAccentBar(parent, x, y, thickness, thickness, color);
        }
    }

    private void buildSellActionPanel(UIComponent panel, int width) {
        int innerWidth = width - 28;
        buildTextStack(panel, 14, 48, innerWidth, buildStorageDetailLines(), TEXT_MUTED);
        createMetricStrip(panel, 14, 110, innerWidth, buildActionMetrics());

        UITextInput qtyInput = createInput(panel, 14, 172, innerWidth, 26,
                Component.translatable("screen.sailboatmod.market.qty").getString(), listingQtyValue, value -> listingQtyValue = value);
        qtyInput.onActivate(value -> {
            listingQtyValue = value;
            createListing();
            return Unit.INSTANCE;
        });

        createInput(panel, 14, 204, innerWidth, 26,
                Component.translatable("screen.sailboatmod.market.price_adjust").getString(), listingPriceAdjustValue,
                value -> listingPriceAdjustValue = value);

        createButton(panel, 14, 238, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.list_hand").getString(),
                selectedStorage() != null && data.dockStorageAccessible() && data.linkedDock(), false, this::createListing);

        buildTextStack(panel, 14, 276, innerWidth, buildSellActionLines(), TEXT_MUTED);
    }

    private void buildDispatchActionPanel(UIComponent panel, int width) {
        int innerWidth = width - 28;
        createMetricStrip(panel, 14, 48, innerWidth, buildDispatchMetrics());
        buildTextStack(panel, 14, 100, innerWidth, buildDispatchActionLines(), TEXT_MUTED);
        createButton(panel, 14, 260, innerWidth, 30,
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
        createButton(panel, 14, 100, innerWidth, 30,
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
        createMetricStrip(panel, 14, 48, innerWidth, buildBuyOrdersMetrics());
        buildTextStack(panel, 14, 100, innerWidth, buildBuyOrderDetailLines(), TEXT_MUTED);
        createInput(panel, 14, 160, innerWidth, 26,
                Component.translatable("screen.sailboatmod.market.buy_order.commodity").getString(),
                buyOrderCommodityValue, value -> buyOrderCommodityValue = value);
        createButton(panel, 14, 192, innerWidth, 22,
                Component.translatable("screen.sailboatmod.market.buy_order.use_selected").getString(),
                selectedListing() != null, true, this::useSelectedListingForBuyOrder);
        createInput(panel, 14, 220, innerWidth, 26,
                Component.translatable("screen.sailboatmod.market.buy_order.qty").getString(),
                buyOrderQtyValue, value -> buyOrderQtyValue = value);
        createInput(panel, 14, 252, innerWidth, 26,
                Component.translatable("screen.sailboatmod.market.buy_order.min_price").getString(),
                buyOrderMinPriceValue, value -> buyOrderMinPriceValue = value);
        createInput(panel, 14, 284, innerWidth, 26,
                Component.translatable("screen.sailboatmod.market.buy_order.max_price").getString(),
                buyOrderMaxPriceValue, value -> buyOrderMaxPriceValue = value);

        createButton(panel, 14, 318, innerWidth, 30,
                Component.translatable("screen.sailboatmod.market.buy_order.create").getString(),
                !resolvedBuyOrderCommodityValue().isBlank(), false, this::createBuyOrder);
        createButton(panel, 14, 354, innerWidth, 26,
                Component.translatable("screen.sailboatmod.market.buy_order.cancel").getString(),
                selectedBuyOrder() != null, true, this::cancelBuyOrder);

        buildTextStack(panel, 14, 388, innerWidth, buildBuyOrderActionLines(), TEXT_MUTED);
    }

    private void buildDetailSection(UIComponent panel, String headline, List<MetricCard> metrics, List<String> lines, int width, int height) {
        createText(panel, 14, 48, headline.isBlank() ? Component.translatable("screen.sailboatmod.market.empty").getString() : headline, 1.18f, TEXT_PRIMARY);
        createMetricStrip(panel, 14, 80, width, metrics);
        buildTextStack(panel, 14, 148, width, lines, TEXT_MUTED);
    }

    private void buildListPanel(UIComponent panel, List<RowSpec> rows, int width, int height) {
        buildListPanel(panel, rows, width, height, 48);
    }

    private void buildBuyOrdersListPanel(UIComponent panel, List<RowSpec> rows, int width, int height) {
        ScrollComponent scroll = new ScrollComponent();
        scroll.setX(new PixelConstraint(14));
        scroll.setY(new PixelConstraint(48));
        scroll.setWidth(new PixelConstraint(width));
        scroll.setHeight(new PixelConstraint(height));
        scroll.setChildOf(panel);
        scroll.setColor(new Color(0, 0, 0, 0));
        buyOrdersListScrollRef = scroll;

        for (RowSpec row : rows) {
            createRow(scroll, width - 8, row);
        }
        restoreScroll(scroll, buyOrdersListScrollOffset);
    }

    private void buildListPanel(UIComponent panel, List<RowSpec> rows, int width, int height, int startY) {
        ScrollComponent scroll = new ScrollComponent();
        scroll.setX(new PixelConstraint(14));
        scroll.setY(new PixelConstraint(startY));
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
            subtitle.setY(new PixelConstraint(22));
            subtitle.setWidth(new PixelConstraint(width - 36));
            subtitle.setHeight(new ChildBasedSizeConstraint(0f));
            subtitle.setTextScale(new PixelConstraint(0.7f));
            subtitle.setColor(TEXT_MUTED);
            subtitle.setChildOf(row);
        }
    }

    private List<RowSpec> buildListingRows() {
        List<Integer> visible = filteredListingIndices();
        if (visible.isEmpty()) {
            return List.of(rowSpec(Component.translatable("screen.sailboatmod.market.empty").getString(),
                    Component.translatable("screen.sailboatmod.market.catalog.empty_hint").getString(), "", false, null));
        }
        List<RowSpec> rows = new ArrayList<>();
        for (int index : visible) {
            MarketOverviewData.ListingEntry entry = data.listingEntries().get(index);
            rows.add(rowSpec(
                    entry.itemName(),
                    buildListingRowSubtitle(entry),
                    formatCompactLong(entry.unitPrice()),
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
                    displayCommodityName(entry.commodityKey()),
                    Component.translatable("screen.sailboatmod.market.buy_order.quantity", entry.quantity()).getString()
                            + "  |  "
                            + Component.translatable("screen.sailboatmod.market.buy_order.price_range", entry.minPriceBp(), entry.maxPriceBp()).getString(),
                    formatChartTime(entry.createdAt(), true),
                    index == selectedBuyOrderIndex,
                    () -> {
                        selectedBuyOrderIndex = index;
                        rebuildUi();
                    }
            ));
        }
        return rows;
    }

    private List<RowSpec> buildBuyingRows(MarketOverviewData.ListingEntry listing, MarketOverviewData.CommodityBuyBook book) {
        if (book == null || book.entries().isEmpty()) {
            return List.of(rowSpec(Component.translatable("screen.sailboatmod.market.buying_empty").getString(), "", "", false, null));
        }
        List<RowSpec> rows = new ArrayList<>();
        for (MarketOverviewData.CommodityBuyEntry entry : book.entries()) {
            int bestBid = estimateBuyOrderPrice(listing.unitPrice(), entry.maxPriceBp());
            rows.add(rowSpec(
                    entry.buyerName().isBlank() ? "-" : entry.buyerName(),
                    Component.translatable("screen.sailboatmod.market.buy_order.quantity", entry.quantity()).getString()
                            + "  |  "
                            + Component.translatable("screen.sailboatmod.market.buy_order.price_range",
                            formatCompactLong(estimateBuyOrderPrice(listing.unitPrice(), entry.minPriceBp())),
                            formatCompactLong(bestBid)).getString(),
                    formatChartTime(entry.createdAt(), true),
                    false,
                    null
            ));
        }
        return rows;
    }

    private List<Integer> filteredListingIndices() {
        List<Integer> indices = new ArrayList<>();
        String query = goodsSearchValue == null ? "" : goodsSearchValue.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < data.listingEntries().size(); i++) {
            MarketOverviewData.ListingEntry entry = data.listingEntries().get(i);
            if (!query.isBlank()) {
                String haystack = (entry.itemName() + " " + entry.sellerName() + " " + entry.sourceDockName() + " " + entry.commodityKey())
                        .toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) {
                    continue;
                }
            }
            if (!goodsCategoryFilter.matches(entry)) {
                continue;
            }
            if (!matchesPriceFilter(entry.unitPrice())) {
                continue;
            }
            indices.add(i);
        }
        indices.sort((left, right) -> compareListings(data.listingEntries().get(left), data.listingEntries().get(right)));
        return indices;
    }

    private List<HotCommodity> buildHotCommodities() {
        List<HotCommodity> hot = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < data.listingEntries().size(); i++) {
            MarketOverviewData.ListingEntry entry = data.listingEntries().get(i);
            if (seen.contains(entry.commodityKey())) {
                continue;
            }
            seen.add(entry.commodityKey());
            int buyDepth = buyDepthFor(entry.commodityKey());
            int chartActivity = chartActivityFor(entry.commodityKey());
            int heat = entry.availableCount() * 2 + buyDepth + chartActivity;
            String summary = Component.translatable("screen.sailboatmod.market.hot_row",
                    formatCompactLong(entry.unitPrice()), buyDepth, chartActivity).getString();
            hot.add(new HotCommodity(entry.commodityKey(), entry.itemName(), i, heat, summary));
        }
        hot.sort((left, right) -> Integer.compare(right.heatScore(), left.heatScore()));
        return hot.size() > 6 ? new ArrayList<>(hot.subList(0, 6)) : hot;
    }

    private int buyDepthFor(String commodityKey) {
        MarketOverviewData.CommodityBuyBook book = data.buyBookFor(commodityKey);
        if (book == null || book.entries().isEmpty()) {
            return 0;
        }
        int total = 0;
        for (MarketOverviewData.CommodityBuyEntry entry : book.entries()) {
            total += entry.quantity();
        }
        return total;
    }

    private int chartActivityFor(String commodityKey) {
        MarketOverviewData.PriceChartSeries series = data.priceChartFor(commodityKey);
        if (series == null || series.points().isEmpty()) {
            return 0;
        }
        int total = 0;
        for (MarketOverviewData.PriceChartPoint point : series.points()) {
            total += point.tradeCount();
        }
        return total;
    }

    private List<Integer> pagedListingIndices(List<Integer> filtered) {
        if (filtered == null || filtered.isEmpty()) {
            return List.of();
        }
        int start = goodsCatalogPage * GOODS_CATALOG_PAGE_SIZE;
        if (start >= filtered.size()) {
            start = Math.max(0, (goodsCatalogPageCount(filtered.size()) - 1) * GOODS_CATALOG_PAGE_SIZE);
        }
        int end = Math.min(filtered.size(), start + GOODS_CATALOG_PAGE_SIZE);
        return new ArrayList<>(filtered.subList(start, end));
    }

    private int goodsCatalogPageCount(int filteredCount) {
        return Math.max(1, (filteredCount + GOODS_CATALOG_PAGE_SIZE - 1) / GOODS_CATALOG_PAGE_SIZE);
    }

    private int clampGoodsCatalogPage(int filteredCount) {
        return Math.max(0, Math.min(goodsCatalogPage, goodsCatalogPageCount(filteredCount) - 1));
    }

    private String buildGoodsCatalogSummaryText(int filteredCount, int totalCount) {
        if (filteredCount <= 0) {
            return Component.translatable("screen.sailboatmod.market.catalog.showing_none", totalCount).getString();
        }
        int start = goodsCatalogPage * GOODS_CATALOG_PAGE_SIZE + 1;
        int end = Math.min(filteredCount, (goodsCatalogPage + 1) * GOODS_CATALOG_PAGE_SIZE);
        return Component.translatable("screen.sailboatmod.market.catalog.showing", start, end, filteredCount, totalCount).getString();
    }

    private String buildGoodsCatalogFilterSummary() {
        List<String> parts = new ArrayList<>();
        if (!goodsSearchValue.isBlank()) {
            parts.add(Component.translatable("screen.sailboatmod.market.catalog.filter.search", goodsSearchValue.trim()).getString());
        }
        if (goodsCategoryFilter != GoodsCategoryFilter.ALL) {
            parts.add(Component.translatable(goodsCategoryFilter.labelKey).getString());
        }
        if (goodsPriceBandFilter != GoodsPriceBandFilter.ALL) {
            parts.add(Component.translatable(goodsPriceBandFilter.labelKey).getString());
        }
        parts.add(Component.translatable(goodsCatalogSort.labelKey).getString());
        return parts.isEmpty()
                ? Component.translatable("screen.sailboatmod.market.catalog.filter.none").getString()
                : Component.translatable("screen.sailboatmod.market.catalog.filter_summary", String.join(" / ", parts)).getString();
    }

    private int compareListings(MarketOverviewData.ListingEntry left, MarketOverviewData.ListingEntry right) {
        return switch (goodsCatalogSort) {
            case PRICE_DESC -> Integer.compare(right.unitPrice(), left.unitPrice());
            case STOCK_DESC -> Integer.compare(right.availableCount(), left.availableCount());
            case NAME_ASC -> left.itemName().compareToIgnoreCase(right.itemName());
        };
    }

    private String buildListingRowSubtitle(MarketOverviewData.ListingEntry entry) {
        return Component.translatable("screen.sailboatmod.market.catalog.row_format",
                entry.sellerName(),
                entry.availableCount(),
                entry.sourceDockName().isBlank() ? "-" : entry.sourceDockName()).getString();
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

    private List<MetricCard> buildPriceChartMetrics(MarketOverviewData.PriceChartSeries series, MarketOverviewData.ListingEntry listing) {
        List<MetricCard> metrics = new ArrayList<>();
        int latest = listing == null ? 0 : listing.unitPrice();
        int high = latest;
        int low = latest;
        int totalVolume = 0;
        int totalTrades = 0;
        if (series != null && !series.points().isEmpty()) {
            latest = series.points().get(series.points().size() - 1).averageUnitPrice();
            low = Integer.MAX_VALUE;
            high = 0;
            for (MarketOverviewData.PriceChartPoint point : series.points()) {
                high = Math.max(high, point.maxUnitPrice());
                low = Math.min(low, point.minUnitPrice());
                totalVolume += point.volume();
                totalTrades += point.tradeCount();
            }
            if (low == Integer.MAX_VALUE) {
                low = latest;
            }
        }
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.price_chart.latest").getString(),
                formatCompactLong(latest), ACCENT));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.price_chart.high").getString(),
                formatCompactLong(high), POSITIVE));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.price_chart.low").getString(),
                formatCompactLong(low), NEGATIVE));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.price_chart.volume").getString(),
                totalVolume + " / " + totalTrades, new Color(95, 162, 198)));
        return metrics;
    }

    private List<MetricCard> buildGoodsOverviewMetrics() {
        List<MetricCard> metrics = new ArrayList<>();
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.available").getString(),
                Integer.toString(data.listingEntries().size()), POSITIVE));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.catalog").getString(),
                Integer.toString(countUniqueListingCommodities()), ACCENT));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.tab.buy_orders").getString(),
                Integer.toString(countCommodityBuyOrders()), new Color(95, 162, 198)));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.price_chart").getString(),
                Integer.toString(data.priceChartSeries().size()), ACCENT_DIM));
        return metrics;
    }

    private List<MetricCard> buildBuyingMetrics(MarketOverviewData.ListingEntry listing, MarketOverviewData.CommodityBuyBook book) {
        List<MetricCard> metrics = new ArrayList<>();
        int orderCount = book == null ? 0 : book.entries().size();
        int totalQty = 0;
        int bestBid = 0;
        int worstBid = 0;
        if (book != null && !book.entries().isEmpty()) {
            worstBid = Integer.MAX_VALUE;
            for (MarketOverviewData.CommodityBuyEntry entry : book.entries()) {
                totalQty += entry.quantity();
                bestBid = Math.max(bestBid, estimateBuyOrderPrice(listing.unitPrice(), entry.maxPriceBp()));
                worstBid = Math.min(worstBid, estimateBuyOrderPrice(listing.unitPrice(), entry.minPriceBp()));
            }
            if (worstBid == Integer.MAX_VALUE) {
                worstBid = 0;
            }
        }
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.price_chart.volume").getString(),
                Integer.toString(totalQty), POSITIVE));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.tab.buy_orders").getString(),
                Integer.toString(orderCount), new Color(95, 162, 198)));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.buy_order.max_price").getString(),
                formatCompactLong(bestBid), ACCENT));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.buy_order.min_price").getString(),
                formatCompactLong(worstBid), NEGATIVE));
        return metrics;
    }

    private List<MetricCard> buildBuyOrdersOverviewMetrics() {
        List<MetricCard> metrics = new ArrayList<>();
        int count = data.buyOrderEntries().size();
        int quantity = 0;
        int active = 0;
        for (MarketOverviewData.BuyOrderEntry entry : data.buyOrderEntries()) {
            quantity += entry.quantity();
            if ("ACTIVE".equalsIgnoreCase(entry.status())) {
                active++;
            }
        }
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.tab.buy_orders").getString(),
                Integer.toString(count), ACCENT));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.buy_order.qty").getString(),
                Integer.toString(quantity), POSITIVE));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.status").getString(),
                Integer.toString(active), new Color(95, 162, 198)));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.metric.catalog").getString(),
                Integer.toString(countUniqueBuyOrderCommodities()), ACCENT_DIM));
        return metrics;
    }

    private List<MetricCard> buildBuyOrdersMetrics() {
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
            return metrics;
        }
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.tab.buy_orders").getString(),
                Integer.toString(data.buyOrderEntries().size()), ACCENT));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.buy_order.qty").getString(),
                Integer.toString(totalBuyOrderQuantity()), POSITIVE));
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

    private List<String> buildPriceChartLines(MarketOverviewData.PriceChartSeries series, MarketOverviewData.ListingEntry listing) {
        List<String> lines = new ArrayList<>();
        if (listing == null) {
            lines.add(Component.translatable("screen.sailboatmod.market.empty").getString());
            return lines;
        }
        if (series == null || series.points().isEmpty()) {
            lines.add(Component.translatable("screen.sailboatmod.market.price_chart.empty").getString());
            lines.add(Component.translatable("screen.sailboatmod.market.auto_price_help").getString());
            return lines;
        }

        MarketOverviewData.PriceChartPoint first = series.points().get(0);
        MarketOverviewData.PriceChartPoint last = series.points().get(series.points().size() - 1);
        int high = 0;
        int low = Integer.MAX_VALUE;
        int totalVolume = 0;
        int totalTrades = 0;
        for (MarketOverviewData.PriceChartPoint point : series.points()) {
            high = Math.max(high, point.maxUnitPrice());
            low = Math.min(low, point.minUnitPrice());
            totalVolume += point.volume();
            totalTrades += point.tradeCount();
        }
        lines.add(Component.translatable("screen.sailboatmod.market.price_chart.window").getString()
                + ": " + formatChartTime(first.bucketAt(), false) + " - " + formatChartTime(last.bucketAt(), false));
        lines.add(Component.translatable("screen.sailboatmod.market.price_chart.latest").getString()
                + ": " + formatCompactLong(last.averageUnitPrice()));
        lines.add(Component.translatable("screen.sailboatmod.market.price_chart.high").getString()
                + " / " + Component.translatable("screen.sailboatmod.market.price_chart.low").getString()
                + ": " + formatCompactLong(high) + " / " + formatCompactLong(low == Integer.MAX_VALUE ? last.averageUnitPrice() : low));
        lines.add(Component.translatable("screen.sailboatmod.market.price_chart.volume").getString()
                + ": " + totalVolume + "    "
                + Component.translatable("screen.sailboatmod.market.price_chart.trades").getString()
                + ": " + totalTrades);
        return lines;
    }

    private List<String> buildGoodsOverviewLines() {
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.market.market_overview_hint").getString());
        lines.add(Component.translatable("screen.sailboatmod.market.hot_title").getString());
        lines.add(Component.translatable("screen.sailboatmod.market.price_chart.window").getString()
                + ": 24h");
        lines.add(currentDockLine());
        return lines;
    }

    private List<String> buildBuyingLines(MarketOverviewData.ListingEntry listing, MarketOverviewData.CommodityBuyBook book) {
        List<String> lines = new ArrayList<>();
        if (book == null || book.entries().isEmpty()) {
            lines.add(Component.translatable("screen.sailboatmod.market.buying_empty").getString());
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.create_hint").getString());
            return lines;
        }
        MarketOverviewData.CommodityBuyEntry best = book.entries().get(0);
        int bestBid = estimateBuyOrderPrice(listing.unitPrice(), best.maxPriceBp());
        int depth = 0;
        for (MarketOverviewData.CommodityBuyEntry entry : book.entries()) {
            depth += entry.quantity();
        }
        lines.add(Component.translatable("screen.sailboatmod.market.buying_best_bid").getString()
                + ": " + formatCompactLong(bestBid));
        lines.add(Component.translatable("screen.sailboatmod.market.buying_depth").getString()
                + ": " + depth);
        lines.add(Component.translatable("screen.sailboatmod.market.buy_order.price_range_hint").getString());
        return lines;
    }

    private List<String> buildBuyOrdersOverviewLines() {
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.market.buy_orders_manage_hint").getString());
        if (selectedListing() != null) {
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.selected_goods", displayCommodityName(selectedListing().commodityKey())).getString());
        } else {
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.selected_goods", "-").getString());
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
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.commodity", displayCommodityName(entry.commodityKey())).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.quantity", entry.quantity()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.price_range", entry.minPriceBp(), entry.maxPriceBp()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.buyer", entry.buyerName()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.status", entry.status()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.created_at", formatChartTime(entry.createdAt(), false)).getString());
            return lines;
        }
        lines.add(Component.translatable("screen.sailboatmod.market.buy_order.selected_goods", resolvedBuyOrderCommodityValue().isBlank()
                ? "-"
                : displayCommodityName(resolvedBuyOrderCommodityValue())).getString());
        lines.add(Component.translatable("screen.sailboatmod.market.buy_order.price_range_hint").getString());
        return lines;
    }

    private List<String> buildBuyOrderActionLines() {
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.market.buy_order.create_hint").getString());
        lines.add(Component.translatable("screen.sailboatmod.market.buy_order.price_range_hint").getString());
        if (selectedListing() != null) {
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.selected_goods", displayCommodityName(selectedListing().commodityKey())).getString());
        }
        return lines;
    }

    private void refreshMarket() {
        send(MarketGuiActionPacket.Action.REFRESH);
    }

    private void createListing() {
        rememberScrollState();
        ModNetwork.CHANNEL.sendToServer(new CreateMarketListingPacket(
                data.marketPos(),
                selectedStorageIndex,
                parsePositive(listingQtyValue, 1),
                0,
                parsePriceAdjustment(listingPriceAdjustValue)
        ));
    }

    private void sendBuy() {
        rememberScrollState();
        ModNetwork.CHANNEL.sendToServer(new PurchaseMarketListingPacket(
                data.marketPos(),
                selectedListingIndex,
                parsePositive(buyQtyValue, 1)
        ));
    }

    private void cancelListing() {
        rememberScrollState();
        ModNetwork.CHANNEL.sendToServer(new CancelMarketListingPacket(data.marketPos(), selectedListingIndex));
    }

    private void dispatchSelectedOrder() {
        rememberScrollState();
        ModNetwork.CHANNEL.sendToServer(new DispatchMarketOrderPacket(data.marketPos(), selectedOrderIndex, selectedBoatIndex));
    }

    private void claimCredits() {
        rememberScrollState();
        ModNetwork.CHANNEL.sendToServer(new ClaimMarketCreditsPacket(data.marketPos()));
    }

    private void createBuyOrder() {
        rememberScrollState();
        ModNetwork.CHANNEL.sendToServer(new CreateBuyOrderPacket(
                data.marketPos(),
                resolvedBuyOrderCommodityValue(),
                parsePositive(buyOrderQtyValue, 1),
                parsePriceAdjustment(buyOrderMinPriceValue),
                parsePriceAdjustment(buyOrderMaxPriceValue)
        ));
    }

    private void cancelBuyOrder() {
        rememberScrollState();
        MarketOverviewData.BuyOrderEntry entry = selectedBuyOrder();
        if (entry != null) {
            ModNetwork.CHANNEL.sendToServer(new CancelBuyOrderPacket(data.marketPos(), entry.orderId()));
        }
    }

    private void useSelectedListingForBuyOrder() {
        rememberScrollState();
        MarketOverviewData.ListingEntry listing = selectedListing();
        if (listing == null) {
            return;
        }
        buyOrderCommodityValue = listing.commodityKey();
        rebuildUi();
    }

    private void send(MarketGuiActionPacket.Action action) {
        ModNetwork.CHANNEL.sendToServer(new MarketGuiActionPacket(data.marketPos(), action));
    }

    private void rememberScrollState() {
        if (goodsViewportScrollRef != null) {
            goodsViewportScrollOffset = goodsViewportScrollRef.getVerticalOffset();
        }
        if (buyOrdersViewportScrollRef != null) {
            buyOrdersViewportScrollOffset = buyOrdersViewportScrollRef.getVerticalOffset();
        }
        if (goodsCatalogScrollRef != null) {
            goodsCatalogScrollOffset = goodsCatalogScrollRef.getVerticalOffset();
        }
        if (buyOrdersListScrollRef != null) {
            buyOrdersListScrollOffset = buyOrdersListScrollRef.getVerticalOffset();
        }
        goodsViewportScrollRef = null;
        buyOrdersViewportScrollRef = null;
        goodsCatalogScrollRef = null;
        buyOrdersListScrollRef = null;
    }

    private void restoreScroll(ScrollComponent scroll, float verticalOffset) {
        if (scroll != null && verticalOffset > 0.0f) {
            scroll.scrollTo(0.0f, verticalOffset, false);
        }
    }

    private ScrollComponent createViewportScroll(UIComponent parent, int x, int y, int width, int height) {
        ScrollComponent scroll = new ScrollComponent();
        scroll.setX(new PixelConstraint(x));
        scroll.setY(new PixelConstraint(y));
        scroll.setWidth(new PixelConstraint(width));
        scroll.setHeight(new PixelConstraint(height));
        scroll.setColor(new Color(0, 0, 0, 0));
        scroll.setChildOf(parent);
        return scroll;
    }

    private int responsiveSidebarWidth(int width) {
        if (width < 600) return 200;
        if (width < 800) return 220;
        if (width < 1000) return 260;
        return Math.min(300, width / 3);
    }

    private UIRoundedRectangle createSection(UIComponent parent, int x, int y, int width, int height, String title, String subtitle) {
        UIRoundedRectangle section = createPanel(parent, x, y, width, height, 16f, CARD_BG);
        section.enableEffect(new OutlineEffect(BORDER, 1f));
        createText(section, 14, 10, title, 0.88f, TEXT_SOFT);
        createText(section, 14, 24, subtitle, 0.8f, TEXT_PRIMARY);
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
        input.setY(new PixelConstraint(Math.max(5, (height - 16) / 2)));
        input.setWidth(new PixelConstraint(width - 20));
        input.setHeight(new PixelConstraint(Math.max(12, height - 10)));
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
        createText(button, 10, Math.max(5, height / 2 - 6), shorten(label, Math.max(5, width / 8)), 0.82f, enabled ? TEXT_PRIMARY : TEXT_SOFT);

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
        int width = Math.max(64, label.length() * 7 + 16);
        boolean active = page == activePage;
        UIRoundedRectangle button = createPanel(parent, x, 2, width, 20, 10f, active ? ROW_SELECTED : ROW_BG);
        button.enableEffect(new OutlineEffect(new Color(255, 255, 255, active ? 24 : 10), 1f));
        createText(button, 9, 5, label, 0.76f, active ? TEXT_PRIMARY : TEXT_MUTED);
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
        return width + 4;
    }

    private void createMetricStrip(UIComponent parent, int x, int y, int width, List<MetricCard> metrics) {
        if (metrics.isEmpty()) {
            return;
        }
        int cardCount = Math.min(4, metrics.size());
        int cardWidth = (width - (cardCount - 1) * 8) / cardCount;
        for (int i = 0; i < cardCount; i++) {
            buildMetricTile(parent, x + i * (cardWidth + 8), y, cardWidth, 46,
                    metrics.get(i).label, metrics.get(i).value, metrics.get(i).accent);
        }
    }

    private void buildMetricTile(UIComponent parent, int x, int y, int width, int height, String label, String value, Color accent) {
        UIRoundedRectangle tile = createPanel(parent, x, y, width, height, 12f, CARD_BG_SOFT);
        tile.enableEffect(new OutlineEffect(new Color(255, 255, 255, 14), 1f));
        createAccentBar(tile, 0, 0, 4, height, accent);
        createText(tile, 14, 9, shorten(label, Math.max(10, width / 8)), 0.76f, TEXT_SOFT);
        createText(tile, 14, 23, shorten(value, Math.max(10, width / 8)), 0.92f, TEXT_PRIMARY);
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
            text.setTextScale(new PixelConstraint(0.7f));
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

    private boolean matchesPriceFilter(int price) {
        int minPrice = parsePrice(priceFilterMinValue, 0);
        int maxPrice = parsePrice(priceFilterMaxValue, Integer.MAX_VALUE);
        return price >= minPrice && price <= maxPrice;
    }

    private int parsePrice(String value, int fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (Exception ignored) {
            return fallback;
        }
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

    private String goodsPanelSubtitle() {
        MarketOverviewData.CommodityBuyBook buyBook = selectedBuyBook();
        if (activeGoodsPanelTab == GoodsPanelTab.PRICE_CHART) {
            MarketOverviewData.PriceChartSeries series = selectedPriceChartSeries();
            if (series != null && !series.points().isEmpty()) {
                return Component.translatable("screen.sailboatmod.market.price_chart.window").getString()
                        + ": " + formatChartTime(series.points().get(0).bucketAt(), true)
                        + " - " + formatChartTime(series.points().get(series.points().size() - 1).bucketAt(), true);
            }
            return Component.translatable("screen.sailboatmod.market.price_chart").getString();
        }
        if (activeGoodsPanelTab == GoodsPanelTab.BUYING) {
            return bookSubtitle(buyBook);
        }
        return actionCaption();
    }

    private String buildBuyOrdersSubtitle() {
        MarketOverviewData.BuyOrderEntry entry = selectedBuyOrder();
        if (entry != null) {
            return Component.translatable("screen.sailboatmod.market.buy_order.created_at", formatChartTime(entry.createdAt(), false)).getString();
        }
        return Component.translatable("screen.sailboatmod.market.buy_orders_manage_hint").getString();
    }

    private String bookSubtitle(MarketOverviewData.CommodityBuyBook book) {
        if (book == null || book.entries().isEmpty()) {
            return Component.translatable("screen.sailboatmod.market.buying_empty").getString();
        }
        return Component.translatable("screen.sailboatmod.market.buying_depth").getString() + ": " + book.entries().size();
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

    private MarketOverviewData.PriceChartSeries selectedPriceChartSeries() {
        MarketOverviewData.ListingEntry listing = selectedListing();
        return listing == null ? null : data.priceChartFor(listing.commodityKey());
    }

    private MarketOverviewData.CommodityBuyBook selectedBuyBook() {
        MarketOverviewData.ListingEntry listing = selectedListing();
        return listing == null ? null : data.buyBookFor(listing.commodityKey());
    }

    private String selectedChartDisplayName() {
        MarketOverviewData.PriceChartSeries series = selectedPriceChartSeries();
        if (series != null && !series.displayName().isBlank()) {
            return series.displayName();
        }
        MarketOverviewData.ListingEntry listing = selectedListing();
        if (listing != null && !listing.itemName().isBlank()) {
            return listing.itemName();
        }
        return Component.translatable("screen.sailboatmod.market.empty").getString();
    }

    private String displayCommodityName(String commodityKey) {
        if (commodityKey == null || commodityKey.isBlank()) {
            return Component.translatable("screen.sailboatmod.market.empty").getString();
        }
        MarketOverviewData.CommodityBuyBook buyBook = data.buyBookFor(commodityKey);
        if (buyBook != null && !buyBook.displayName().isBlank()) {
            return buyBook.displayName();
        }
        MarketOverviewData.PriceChartSeries series = data.priceChartFor(commodityKey);
        if (series != null && !series.displayName().isBlank()) {
            return series.displayName();
        }
        for (MarketOverviewData.ListingEntry entry : data.listingEntries()) {
            if (commodityKey.equals(entry.commodityKey()) && !entry.itemName().isBlank()) {
                return entry.itemName();
            }
        }
        return commodityKey;
    }

    private String resolvedBuyOrderCommodityValue() {
        String manual = buyOrderCommodityValue == null ? "" : buyOrderCommodityValue.trim();
        if (!manual.isBlank()) {
            return manual;
        }
        MarketOverviewData.ListingEntry listing = selectedListing();
        return listing == null ? "" : listing.commodityKey();
    }

    private String formatChartTime(long epochMillis, boolean shortForm) {
        if (epochMillis <= 0L) {
            return "-";
        }
        return (shortForm ? CHART_SHORT_TIME_FORMAT : CHART_TIME_FORMAT)
                .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime());
    }

    private int estimateBuyOrderPrice(int marketUnitPrice, int adjustmentBp) {
        return Math.max(1, (int) Math.round(marketUnitPrice * (1 + adjustmentBp / 10000.0D)));
    }

    private int countUniqueListingCommodities() {
        List<String> keys = new ArrayList<>();
        for (MarketOverviewData.ListingEntry entry : data.listingEntries()) {
            if (!keys.contains(entry.commodityKey())) {
                keys.add(entry.commodityKey());
            }
        }
        return keys.size();
    }

    private int countCommodityBuyOrders() {
        int total = 0;
        for (MarketOverviewData.CommodityBuyBook book : data.commodityBuyBooks()) {
            total += book.entries().size();
        }
        return total;
    }

    private int countUniqueBuyOrderCommodities() {
        List<String> keys = new ArrayList<>();
        for (MarketOverviewData.BuyOrderEntry entry : data.buyOrderEntries()) {
            if (!keys.contains(entry.commodityKey())) {
                keys.add(entry.commodityKey());
            }
        }
        return keys.size();
    }

    private int totalBuyOrderQuantity() {
        int total = 0;
        for (MarketOverviewData.BuyOrderEntry entry : data.buyOrderEntries()) {
            total += entry.quantity();
        }
        return total;
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
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
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

    private enum GoodsPanelTab {
        SELLING,
        BUYING,
        PRICE_CHART
    }

    private enum GoodsCatalogSort {
        PRICE_DESC("screen.sailboatmod.market.catalog.sort.price"),
        STOCK_DESC("screen.sailboatmod.market.catalog.sort.stock"),
        NAME_ASC("screen.sailboatmod.market.catalog.sort.name");

        private final String labelKey;

        GoodsCatalogSort(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    private enum GoodsCategoryFilter {
        ALL("screen.sailboatmod.market.catalog.filter.all"),
        COMMON("screen.sailboatmod.market.catalog.filter.common"),
        UNCOMMON("screen.sailboatmod.market.catalog.filter.uncommon"),
        RARE("screen.sailboatmod.market.catalog.filter.rare"),
        EPIC("screen.sailboatmod.market.catalog.filter.epic");

        private final String labelKey;

        GoodsCategoryFilter(String labelKey) {
            this.labelKey = labelKey;
        }

        private boolean matches(MarketOverviewData.ListingEntry entry) {
            int rarity = entry == null ? 0 : entry.rarity();
            return switch (this) {
                case ALL -> true;
                case COMMON -> rarity == 0;
                case UNCOMMON -> rarity == 1;
                case RARE -> rarity == 2;
                case EPIC -> rarity == 3;
            };
        }
    }

    private enum GoodsPriceBandFilter {
        ALL("screen.sailboatmod.market.catalog.price.all"),
        LOW("screen.sailboatmod.market.catalog.price.low"),
        MID("screen.sailboatmod.market.catalog.price.mid"),
        HIGH("screen.sailboatmod.market.catalog.price.high");

        private final String labelKey;

        GoodsPriceBandFilter(String labelKey) {
            this.labelKey = labelKey;
        }

        private boolean matches(int price) {
            return switch (this) {
                case ALL -> true;
                case LOW -> price < 16;
                case MID -> price >= 16 && price < 64;
                case HIGH -> price >= 64;
            };
        }
    }

    private record MetricCard(String label, String value, Color accent) {
    }

    private record RowSpec(String title, String subtitle, String trailing, boolean selected, Runnable action) {
    }

    private record HotCommodity(String commodityKey, String itemName, int listingIndex, int heatScore, String summary) {
    }
}
