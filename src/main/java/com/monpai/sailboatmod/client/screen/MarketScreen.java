package com.monpai.sailboatmod.client.screen;

import com.mojang.logging.LogUtils;
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
import gg.essential.universal.UMatrixStack;
import gg.essential.universal.UKeyboard;
import kotlin.Unit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class MarketScreen extends WindowScreen implements MenuAccess<MarketMenu>, MarketOverviewConsumer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean SCROLL_TRACE_ENABLED = true;
    private static final int MAX_PANEL_WIDTH = 2400;
    private static final int MAX_PANEL_HEIGHT = 1600;
    private static final int NAV_WIDTH = 80;
    private static final int HEADER_HEIGHT = 36;
    private static final int PANEL_PADDING = 8;
    private static final int SECTION_GAP = 5;
    private static final int ROW_HEIGHT = 24;
    private static final int GOODS_CATALOG_PAGE_SIZE = 9;
    private static final DateTimeFormatter CHART_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter CHART_SHORT_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private static final Color OVERLAY = new Color(4, 8, 16, 214);
    private static final Color WINDOW_BG = new Color(13, 18, 28, 246);
    private static final Color CHROME_BG = new Color(19, 26, 40, 244);
    private static final Color CHROME_MUTED = new Color(36, 47, 70, 232);
    private static final Color CARD_BG = new Color(20, 27, 42, 236);
    private static final Color CARD_BG_SOFT = new Color(31, 40, 58, 226);
    private static final Color ROW_BG = new Color(22, 30, 46, 232);
    private static final Color ROW_HOVER = new Color(38, 54, 80, 238);
    private static final Color ROW_SELECTED = new Color(60, 92, 134, 244);
    private static final Color ACCENT = new Color(242, 184, 74, 255);
    private static final Color ACCENT_DIM = new Color(205, 153, 83, 255);
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
    private GoodsPrimaryView activeGoodsView = GoodsPrimaryView.BROWSE_MARKET;
    private GoodsPanelTab activeGoodsPanelTab = GoodsPanelTab.SELLING;
    private String selectedCommodityKey = "";
    private boolean initialRefreshSent;
    private boolean closingContainer;
    private boolean closeRequested;

    private int selectedListingIndex;
    private int selectedOrderIndex;
    private int selectedBoatIndex;
    private int selectedStorageIndex;
    private int selectedBuyOrderIndex;

    private String listingQtyValue = "1";
    private String listingPriceAdjustValue = "";
    private String buyQtyValue = "1";
    private String goodsSearchValue = "";
    private String storageSearchValue = "";
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
    private int scrollTraceSequence;
    private boolean showCategoryFilter = false;
    private boolean showPriceFilter = false;
    private float goodsViewportScrollOffset;
    private float buyOrdersViewportScrollOffset;
    private float goodsCatalogScrollOffset;
    private float buyOrdersListScrollOffset;
    private float goodsViewportScrollProgress = Float.NaN;
    private float buyOrdersViewportScrollProgress = Float.NaN;
    private float goodsCatalogScrollProgress = Float.NaN;
    private float buyOrdersListScrollProgress = Float.NaN;
    private ScrollComponent goodsViewportScrollRef;
    private ScrollComponent buyOrdersViewportScrollRef;
    private ScrollComponent goodsCatalogScrollRef;
    private ScrollComponent buyOrdersListScrollRef;
    private String noticeMessage = "";
    private boolean noticePositive = true;
    private long noticeExpiresAtMs;
    private float pendingGoodsViewportScrollOffset = Float.NaN;
    private float pendingBuyOrdersViewportScrollOffset = Float.NaN;
    private float pendingGoodsCatalogScrollOffset = Float.NaN;
    private float pendingBuyOrdersListScrollOffset = Float.NaN;
    private float pendingGoodsViewportScrollProgress = Float.NaN;
    private float pendingBuyOrdersViewportScrollProgress = Float.NaN;
    private float pendingGoodsCatalogScrollProgress = Float.NaN;
    private float pendingBuyOrdersListScrollProgress = Float.NaN;
    private int pendingGoodsViewportScrollTicks;
    private int pendingBuyOrdersViewportScrollTicks;
    private int pendingGoodsCatalogScrollTicks;
    private int pendingBuyOrdersListScrollTicks;

    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        super(ElementaVersion.V11, false, false, false);
        this.menu = menu;
        this.title = title;
        MarketOverviewData initial = MarketClientHooks.consumeFor(menu.getMarketPos());
        this.data = initial != null ? initial : empty(menu.getMarketPos());
        MarketClientHooks.MarketNotice initialNotice = MarketClientHooks.consumeNoticeFor(menu.getMarketPos());
        if (initialNotice != null) {
            applyNotice(initialNotice.message(), initialNotice.positive());
        }
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
        MarketClientHooks.MarketNotice pendingNotice = MarketClientHooks.consumeNoticeFor(menu.getMarketPos());
        if (pendingNotice != null) {
            applyNotice(pendingNotice.message(), pendingNotice.positive());
        }
        clampSelections();
        rebuildUi();
    }

    @Override
    public void afterInitialization() {
        super.afterInitialization();
        MarketClientHooks.MarketNotice pendingNotice = MarketClientHooks.consumeNoticeFor(menu.getMarketPos());
        if (pendingNotice != null) {
            applyNotice(pendingNotice.message(), pendingNotice.positive());
        }
        rebuildUi(false);
        if (!initialRefreshSent) {
            send(MarketGuiActionPacket.Action.REFRESH);
            initialRefreshSent = true;
        }
    }

    @Override
    public void initScreen(int width, int height) {
        super.initScreen(width, height);
        rebuildUi(false);
    }

    @Override
    public void onTick() {
        if (minecraft == null || minecraft.player == null) {
            return;
        }
        if (closeRequested) {
            closeRequested = false;
            closeMarketScreen();
            return;
        }
        if (!menu.stillValid(minecraft.player)) {
            closeMarketScreen();
            return;
        }
        syncLiveScrollOffsets();
        applyPendingScrollRestores();
        if (!noticeMessage.isBlank() && System.currentTimeMillis() >= noticeExpiresAtMs) {
            noticeMessage = "";
            rebuildUi();
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

    @Override
    public void onKeyPressed(int keyCode, char typedChar, UKeyboard.Modifiers modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (handleEscape()) {
                return;
            }
            super.onKeyPressed(keyCode, typedChar, modifiers);
            return;
        }
        super.onKeyPressed(keyCode, typedChar, modifiers);
    }

    @Override
    public void onMouseScrolled(double mouseX, double mouseY, double scrollDelta, double acceleratedDelta) {
        LOGGER.info("[MarketScroll] onMouseScrolled delta={} goodsViewportRef={} catalogRef={}", scrollDelta, goodsViewportScrollRef != null, goodsCatalogScrollRef != null);
        traceScrollMessage("onMouseScrolled mouseX=" + mouseX
                + " mouseY=" + mouseY
                + " scrollDelta=" + scrollDelta
                + " acceleratedDelta=" + acceleratedDelta);
        super.onMouseScrolled(mouseX, mouseY, scrollDelta, acceleratedDelta);
        syncLiveScrollOffsets();
        traceScrollState("onMouseScrolled:after");
    }

    private boolean handleEscape() {
        if (showCategoryFilter || showPriceFilter) {
            showCategoryFilter = false;
            showPriceFilter = false;
            rebuildUi();
            return true;
        }

        if (activePage == MarketPage.GOODS && activeGoodsView == GoodsPrimaryView.PRODUCT_PURCHASE) {
            activeGoodsView = GoodsPrimaryView.PRODUCT_LISTINGS;
            rebuildUi();
            return true;
        }

        if (activePage == MarketPage.GOODS && activeGoodsView == GoodsPrimaryView.PRODUCT_LISTINGS) {
            activeGoodsView = GoodsPrimaryView.BROWSE_MARKET;
            rebuildUi();
            return true;
        }

        if (activePage != MarketPage.GOODS) {
            activePage = MarketPage.GOODS;
            activeGoodsView = GoodsPrimaryView.BROWSE_MARKET;
            activeGoodsPanelTab = GoodsPanelTab.SELLING;
            showCategoryFilter = false;
            showPriceFilter = false;
            rebuildUi();
            return true;
        }

        closeRequested = true;
        return true;
    }

    private void rebuildUi() {
        rebuildUi(true);
    }

    private void rebuildUi(boolean preserveScroll) {
        if (width <= 0 || height <= 0) {
            return;
        }
        traceScrollState("rebuildUi:start preserve=" + preserveScroll);
        clampSelections();
        if (preserveScroll) {
            syncLiveScrollOffsets();
            rememberScrollState();
        } else {
            clearScrollRefs();
            clearPendingScrollRestores();
        }

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
        buildNotice(mainPanel, panelWidth);
        traceScrollState("rebuildUi:end preserve=" + preserveScroll);
    }

    private void buildHeader(UIComponent parent, int panelWidth) {
        boolean compact = panelWidth < 800;
        UIRoundedRectangle header = createPanel(parent, PANEL_PADDING, PANEL_PADDING, panelWidth - PANEL_PADDING * 2, HEADER_HEIGHT, 12f, CHROME_BG);
        createAccentBar(header, 0, 0, 80, 3, ACCENT);

        UIRoundedRectangle iconCard = createPanel(header, 8, 6, 64, 24, 10f, CARD_BG_SOFT);
        iconCard.enableEffect(new OutlineEffect(BORDER, 1f));
        createText(iconCard, 8, 8, "MONPAI", 0.68f, ACCENT);

        createText(header, 80, 6, marketTitleLine(), compact ? 0.75f : 0.85f, TEXT_PRIMARY);
        createText(header, 80, 20, marketSubtitleLine(), 0.65f, ACCENT_DIM);

        int headerWidth = panelWidth - PANEL_PADDING * 2;
        if (compact) {
            buildHeaderActionButtons(header, headerWidth, new int[]{50, 65, 45}, 20, 5, 8);
        } else {
            buildHeaderActionButtons(header, headerWidth, new int[]{55, 75, 50}, 20, 5, 8);
        }
    }

    private void buildHeaderActionButtons(UIComponent header, int headerWidth, int[] buttonWidths, int buttonHeight, int gap, int inset) {
        int totalWidth = inset * 2 + buttonWidths[0] + buttonWidths[1] + buttonWidths[2] + gap * 2;
        int groupX = headerWidth - totalWidth - 8;
        UIRoundedRectangle actionGroup = createPanel(header, groupX, 10, totalWidth, 36, 10f, CARD_BG_SOFT);
        actionGroup.enableEffect(new OutlineEffect(new Color(255, 255, 255, 12), 1f));

        int cancelX = totalWidth - inset - buttonWidths[2];
        int bindX = cancelX - gap - buttonWidths[1];
        int refreshX = bindX - gap - buttonWidths[0];

        createButton(actionGroup, refreshX, 8, buttonWidths[0], buttonHeight,
                Component.translatable("screen.sailboatmod.market.refresh").getString(), true, false, this::refreshMarket);
        createButton(actionGroup, bindX, 8, buttonWidths[1], buttonHeight,
                Component.translatable("screen.sailboatmod.market.bind_dock").getString(), true, false,
                () -> send(MarketGuiActionPacket.Action.BIND_NEAREST_DOCK));
        createButton(actionGroup, cancelX, 8, buttonWidths[2], buttonHeight,
                Component.translatable("screen.sailboatmod.route_name.cancel").getString(), true, true, this::closeMarketScreen);
    }

    private void buildNotice(UIComponent parent, int panelWidth) {
        if (noticeMessage == null || noticeMessage.isBlank() || System.currentTimeMillis() >= noticeExpiresAtMs) {
            return;
        }
        int width = Math.min(320, Math.max(180, panelWidth / 3));
        int x = panelWidth - PANEL_PADDING - width;
        int y = HEADER_HEIGHT + 30;
        Color bg = noticePositive ? new Color(32, 72, 56, 230) : new Color(92, 40, 38, 232);
        Color accent = noticePositive ? POSITIVE : NEGATIVE;
        UIRoundedRectangle notice = createPanel(parent, x, y, width, 26, 11f, bg);
        notice.enableEffect(new OutlineEffect(new Color(255, 255, 255, 22), 1f));
        createAccentBar(notice, 0, 0, 5, 26, accent);
        createText(notice, 12, 8, shortenToWidth(noticeMessage, width - 24, 0.76f), 0.76f, TEXT_PRIMARY);
    }

    private void buildTabs(UIComponent parent, int panelWidth) {
        UIRoundedRectangle tabs = createPanel(parent, PANEL_PADDING, HEADER_HEIGHT + 6, panelWidth - PANEL_PADDING * 2, 20, 10f, CHROME_BG);
        int x = 4;
        for (MarketPage page : MarketPage.values()) {
            x += createTabButton(tabs, x, page);
        }
    }

    private void buildGoodsPage(UIComponent parent, int x, int y, int width, int height) {
        buildGoodsViewTabs(parent, x, y, width);
        int goodsTop = y + 28;
        int goodsHeight = height - 28;

        if (activeGoodsView == GoodsPrimaryView.BROWSE_MARKET) {
            if (height < 350 || width < 760) {
                float restoreOffset = goodsViewportScrollOffset;
                float restoreProgress = goodsViewportScrollProgress;
                int contentH = compactContentHeight(width);
                LOGGER.info("[MarketScroll] browsePage compact goodsH={} contentH={}", goodsHeight, contentH);
                ScrollComponent scroll = createViewportScroll(parent, x, goodsTop, width, goodsHeight);
                goodsViewportScrollRef = scroll;
                trackViewportScroll(scroll, true);
                buildBrowseGoodsPage(scroll, 0, 0, width, contentH);
                restoreScroll(scroll, restoreOffset, restoreProgress);
            } else {
                buildBrowseGoodsPage(parent, x, goodsTop, width, goodsHeight);
            }
            return;
        }

        if (height < 350 || width < 760) {
            float restoreOffset = goodsViewportScrollOffset;
            float restoreProgress = goodsViewportScrollProgress;
            ScrollComponent scroll = createViewportScroll(parent, x, goodsTop, width, goodsHeight);
            goodsViewportScrollRef = scroll;
            trackViewportScroll(scroll, true);
            buildCompactGoodsPage(scroll, width, Math.max(goodsHeight, compactContentHeight(width)));
            restoreScroll(scroll, restoreOffset, restoreProgress);
            return;
        }

        int heroHeight = Math.min(170, Math.max(146, goodsHeight / 4));
        int detailHeight = goodsHeight - heroHeight - SECTION_GAP;

        UIRoundedRectangle overviewPanel = createSection(parent, x, goodsTop, width, heroHeight,
                selectedGoodsHeadline(),
                selectedGoodsSubtitle());
        buildGoodsOverviewPanel(overviewPanel, width);

        UIRoundedRectangle actionPanel = createSection(parent, x, goodsTop + heroHeight + SECTION_GAP, width, detailHeight,
                selectedGoodsHeadline(),
                goodsPanelSubtitle());
        buildGoodsActionPanel(actionPanel, width);
    }

    private int compactContentHeight(int width) {
        if (activeGoodsView == GoodsPrimaryView.PRODUCT_LISTINGS) {
            int sellers = selectedCommodityListingIndices().size();
            return 146 + SECTION_GAP + 118 + 20 + estimateCommoditySellerCardsHeight(sellers) + 40;
        }
        if (activeGoodsView == GoodsPrimaryView.BROWSE_MARKET) {
            List<Integer> filtered = filteredListingIndices();
            List<Integer> paged = pagedListingIndices(filtered);
            int innerWidth = Math.max(120, width - 28);
            GoodsCatalogGridLayout layout = goodsCatalogGridLayout(innerWidth, paged.size());
            return goodsCatalogScrollY(goodsCatalogDropdownHeight()) + layout.contentHeight() + 40;
        }
        return 450;
    }

    private void buildCompactGoodsPage(UIComponent parent, int width, int height) {
        if (activeGoodsView == GoodsPrimaryView.BROWSE_MARKET) {
            int listHeight = Math.max(320, height - SECTION_GAP);
            UIRoundedRectangle listPanel = createSection(parent, 0, 0, width, listHeight,
                    Component.translatable("screen.sailboatmod.market.goods_primary.browse").getString(),
                    buildGoodsCatalogFilterSummary());
            buildGoodsCatalogPanel(listPanel, width, listHeight);
            return;
        }

        int overviewHeight = Math.min(168, Math.max(146, height / 4));
        int actionHeight = Math.max(350, height - overviewHeight - SECTION_GAP);

        UIRoundedRectangle overviewPanel = createSection(parent, 0, 0, width, overviewHeight,
                selectedGoodsHeadline(),
                selectedGoodsSubtitle());
        buildGoodsOverviewPanel(overviewPanel, width);

        UIRoundedRectangle actionPanel = createSection(parent, 0, overviewHeight + SECTION_GAP, width, actionHeight,
                selectedGoodsHeadline(),
                goodsPanelSubtitle());
        buildGoodsActionPanel(actionPanel, width);
    }

    private void buildBrowseGoodsPage(UIComponent parent, int x, int y, int width, int height) {
        UIRoundedRectangle marketPanel = createSection(parent, x, y, width, height,
                Component.translatable("screen.sailboatmod.market.goods_primary.browse").getString(),
                Component.translatable("screen.sailboatmod.market.goods_primary.browse_hint").getString());
        buildGoodsCatalogPanel(marketPanel, width, height);
    }

    private void buildGoodsViewTabs(UIComponent parent, int x, int y, int width) {
        UIRoundedRectangle tabs = createPanel(parent, x, y, width, 22, 10f, CHROME_BG);
        int firstWidth = Math.max(110, Math.min(150, width / 6));
        int secondWidth = Math.max(110, Math.min(150, width / 6));
        createGoodsPanelTab(tabs, 4, 0, firstWidth, Component.translatable("screen.sailboatmod.market.goods_primary.browse").getString(),
                activeGoodsView == GoodsPrimaryView.BROWSE_MARKET,
                () -> {
                    activeGoodsView = GoodsPrimaryView.BROWSE_MARKET;
                    rebuildUi();
                });
        createGoodsPanelTab(tabs, 8 + firstWidth, 0, secondWidth, Component.translatable("screen.sailboatmod.market.goods_primary.purchase").getString(),
                activeGoodsView != GoodsPrimaryView.BROWSE_MARKET,
                () -> {
                    if (selectedCommodityKey().isBlank() && !data.listingEntries().isEmpty()) {
                        selectedCommodityKey = data.listingEntries().get(0).commodityKey();
                    }
                    activeGoodsView = GoodsPrimaryView.PRODUCT_LISTINGS;
                    activeGoodsPanelTab = GoodsPanelTab.SELLING;
                    rebuildUi();
                });
        createText(tabs, width - 180, 6,
                activeGoodsView == GoodsPrimaryView.BROWSE_MARKET
                        ? Component.translatable("screen.sailboatmod.market.goods_primary.browse_state").getString()
                        : activeGoodsView == GoodsPrimaryView.PRODUCT_LISTINGS
                        ? Component.translatable("screen.sailboatmod.market.goods_primary.seller_state").getString()
                        : Component.translatable("screen.sailboatmod.market.goods_primary.detail_state").getString(),
                0.68f, TEXT_SOFT);
    }

    private void buildSellPage(UIComponent parent, int x, int y, int width, int height) {
        int listWidth = Math.round(width * 0.30f);
        int sidebarWidth = width - listWidth - SECTION_GAP;

        UIRoundedRectangle listPanel = createSection(parent, x, y, listWidth, height,
                Component.translatable("screen.sailboatmod.market.storage_title").getString(),
                Component.translatable("screen.sailboatmod.market.sell.storage_subtitle").getString());
        buildSellStoragePanel(listPanel, listWidth, height);

        UIRoundedRectangle actionPanel = createSection(parent, x + listWidth + SECTION_GAP, y, sidebarWidth, height,
                Component.translatable("screen.sailboatmod.market.page.sell").getString(),
                Component.translatable("screen.sailboatmod.market.sell.terminal_subtitle").getString());
        buildSellActionPanel(actionPanel, sidebarWidth);
    }

    private void buildDispatchPage(UIComponent parent, int x, int y, int width, int height) {
        int orderWidth = Math.min(240, Math.max(210, width / 4));
        int boatWidth = Math.min(220, Math.max(190, width / 4));
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
                Component.translatable("screen.sailboatmod.market.finance.flow").getString(),
                data.hasTownEconomy()
                        ? Component.translatable("screen.sailboatmod.market.finance.town", data.townName().isBlank() ? "-" : data.townName()).getString()
                        : Component.translatable("screen.sailboatmod.market.finance.no_town").getString());
        buildFinanceEconomy(economy, centerWidth);

        UIRoundedRectangle action = createSection(parent, x + leftWidth + centerWidth + SECTION_GAP * 2, y, rightWidth, topHeight,
                Component.translatable("screen.sailboatmod.market.claim").getString(),
                data.pendingCredits() > 0
                        ? Component.translatable("screen.sailboatmod.market.finance.claim_hint").getString()
                        : Component.translatable("screen.sailboatmod.market.finance.no_credits_hint").getString());
        buildFinanceAction(action, rightWidth);

        UIRoundedRectangle stockpile = createSection(parent, x, y + topHeight + SECTION_GAP, leftWidth, bottomHeight,
                Component.translatable("screen.sailboatmod.market.finance.stockpile_title").getString(),
                Component.translatable("screen.sailboatmod.market.finance.metric.stockpile").getString());
        buildPreviewBlock(stockpile, data.stockpilePreviewLines(), leftWidth);

        UIRoundedRectangle procurement = createSection(parent, x + leftWidth + SECTION_GAP, y + topHeight + SECTION_GAP, centerWidth, bottomHeight,
                Component.translatable("screen.sailboatmod.market.finance.procurement_title").getString(),
                Component.translatable("screen.sailboatmod.market.finance.refresh_hint").getString());
        buildPreviewBlock(procurement, joinLines(data.demandPreviewLines(), data.procurementPreviewLines()), centerWidth);

        UIRoundedRectangle finance = createSection(parent, x + leftWidth + centerWidth + SECTION_GAP * 2, y + topHeight + SECTION_GAP, rightWidth, bottomHeight,
                Component.translatable("screen.sailboatmod.market.finance.ledger_title").getString(),
                Component.translatable(data.canManage() ? "screen.sailboatmod.market.finance.owner_controls" : "screen.sailboatmod.market.finance.read_only").getString());
        buildPreviewBlock(finance, joinLines(data.financePreviewLines(), buildFinanceDetailLines()), rightWidth);
    }

    private void buildBuyOrdersPage(UIComponent parent, int x, int y, int width, int height) {
        if (height < 350) {
            float restoreOffset = buyOrdersViewportScrollOffset;
            float restoreProgress = buyOrdersViewportScrollProgress;
            ScrollComponent scroll = createViewportScroll(parent, x, y, width, height);
            buyOrdersViewportScrollRef = scroll;
            trackViewportScroll(scroll, false);
            buildCompactBuyOrdersPage(scroll, width, 520);
            restoreScroll(scroll, restoreOffset, restoreProgress);
            return;
        }
        if (width < 780) {
            float restoreOffset = buyOrdersViewportScrollOffset;
            float restoreProgress = buyOrdersViewportScrollProgress;
            ScrollComponent scroll = createViewportScroll(parent, x, y, width, height);
            buyOrdersViewportScrollRef = scroll;
            trackViewportScroll(scroll, false);
            buildCompactBuyOrdersPage(scroll, width, height);
            restoreScroll(scroll, restoreOffset, restoreProgress);
            return;
        }

        int sidebarWidth = responsiveSidebarWidth(width);
        int listWidth = width - sidebarWidth - SECTION_GAP;
        int overviewHeight = buyOrdersOverviewSectionHeight(listWidth);
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

    private void buildCompactBuyOrdersPage(UIComponent parent, int width, int height) {
        int overviewHeight = buyOrdersOverviewSectionHeight(width);
        int listHeight = compactBuyOrdersListHeight(height);
        int actionHeight = buyOrdersActionSectionHeight(width);

        UIRoundedRectangle overviewPanel = createSection(parent, 0, 0, width, overviewHeight,
                Component.translatable("screen.sailboatmod.market.buy_orders_overview").getString(),
                Component.translatable("screen.sailboatmod.market.buy_order.create_hint").getString());
        buildBuyOrdersOverviewPanel(overviewPanel, width);

        UIRoundedRectangle listPanel = createSection(parent, 0, overviewHeight + SECTION_GAP, width, listHeight,
                Component.translatable("screen.sailboatmod.market.tab.buy_orders").getString(),
                Component.translatable("screen.sailboatmod.market.buy_orders_manage").getString());
        buildBuyOrdersListPanel(listPanel, data.buyOrderEntries().isEmpty()
                        ? List.of(rowSpec(Component.translatable("screen.sailboatmod.market.empty").getString(), "", "", false, null))
                        : buildBuyOrderRows(),
                width - 28, listHeight - 68);

        UIRoundedRectangle actionPanel = createSection(parent, 0, overviewHeight + listHeight + SECTION_GAP * 2, width, actionHeight,
                selectedBuyOrder() == null ? Component.translatable("screen.sailboatmod.market.page.buy_orders").getString() : displayCommodityName(selectedBuyOrder().commodityKey()),
                buildBuyOrdersSubtitle());
        buildBuyOrderActionPanel(actionPanel, width);
    }

    private void buildGoodsActionPanel(UIComponent panel, int width) {
        MarketOverviewData.ListingEntry listing = selectedListing();
        int innerWidth = width - 28;
        int topButtonGap = 8;
        int topButtonWidth = Math.max(104, Math.min(156, (innerWidth - topButtonGap) / 2));
        createButton(panel, 14, 48, topButtonWidth, 24, Component.translatable("screen.sailboatmod.market.goods.back_market").getString(), true, true, () -> {
            activeGoodsView = GoodsPrimaryView.BROWSE_MARKET;
            rebuildUi();
        });
        createButton(panel, 14 + topButtonWidth + topButtonGap, 48, topButtonWidth, 24,
                Component.translatable("screen.sailboatmod.market.page.sell").getString(),
                true, false, () -> {
                    activePage = MarketPage.SELL;
                    rebuildUi();
                });

        int tabY = 82;
        int tabGap = 8;
        int tabWidth = Math.max(72, (innerWidth - tabGap * 2) / 3);
        createGoodsPanelTab(panel, 14, tabY, tabWidth,
                Component.translatable("screen.sailboatmod.market.goods_subtab.selling").getString(),
                activeGoodsPanelTab == GoodsPanelTab.SELLING,
                () -> {
                    activeGoodsPanelTab = GoodsPanelTab.SELLING;
                    rebuildUi();
                });
        createGoodsPanelTab(panel, 14 + tabWidth + tabGap, tabY, tabWidth,
                Component.translatable("screen.sailboatmod.market.goods_subtab.buying").getString(),
                activeGoodsPanelTab == GoodsPanelTab.BUYING,
                () -> {
                    activeGoodsPanelTab = GoodsPanelTab.BUYING;
                    rebuildUi();
                });
        createGoodsPanelTab(panel, 14 + (tabWidth + tabGap) * 2, tabY,
                Math.max(72, innerWidth - tabWidth * 2 - tabGap * 2),
                Component.translatable("screen.sailboatmod.market.goods_subtab.history").getString(),
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

        if (activeGoodsView == GoodsPrimaryView.PRODUCT_LISTINGS) {
            buildGoodsSellerSelectionPanel(panel, innerWidth);
            return;
        }

        int footerHeight = 126;
        ScrollComponent body = createPanelBodyScroll(panel, 118, footerHeight);
        List<Integer> actionSellerIndices = selectedCommodityListingIndices();
        int detailTop = listing == null ? 0 : 104;
        int bodyBottom = detailTop + 18 + estimateCommoditySellerCardsHeight(actionSellerIndices.size());
        UIBlock bodyContent = new UIBlock(new Color(0, 0, 0, 0));
        bodyContent.setX(new PixelConstraint(0));
        bodyContent.setY(new PixelConstraint(0));
        bodyContent.setWidth(new PixelConstraint(innerWidth));
        bodyContent.setHeight(new PixelConstraint(bodyBottom + 60));
        bodyContent.setChildOf(body);

        if (listing != null) {
            UIRoundedRectangle hero = createPanel(bodyContent, 0, 0, innerWidth, 96, 14f, CHROME_MUTED);
            hero.enableEffect(new OutlineEffect(BORDER, 1f));
            ItemStack heroStack = resolveCommodityStack(listing.commodityKey());
            createItemIcon(hero, 12, 12, 72, 72, heroStack, true);
            createText(hero, 94, 12, shortenToWidth(listing.itemName(), innerWidth - 106, 1.08f), 1.08f, TEXT_PRIMARY);
            createText(hero, 94, 30, shortenToWidth(buildSelectedListingHeroSubtitle(), innerWidth - 106, 0.68f), 0.68f, TEXT_SOFT);
            createBadge(hero, 94, 48, 72, 18, rarityLabel(listing.rarity()), rarityColor(listing.rarity()), TEXT_PRIMARY);
            createBadge(hero, 172, 48, Math.max(88, innerWidth / 3), 18,
                    listing.category().isBlank() ? listing.commodityKey() : listing.category(),
                    new Color(70, 88, 120, 220), TEXT_MUTED);
            createText(hero, 94, 72, Component.translatable("screen.sailboatmod.market.finance.owner", listing.sellerName()).getString(), 0.7f, TEXT_MUTED);
            createText(hero, innerWidth - 84, 66, formatCompactLong(listing.unitPrice()), 0.96f, ACCENT);
        }

        createText(bodyContent, 0, detailTop, Component.translatable("screen.sailboatmod.market.goods.current_listing_count", actionSellerIndices.size()).getString(), 0.84f, TEXT_PRIMARY);
        createText(bodyContent, Math.max(120, innerWidth - 140), detailTop + 2, Component.translatable("screen.sailboatmod.market.goods.switch_seller_hint").getString(), 0.64f, TEXT_SOFT);
        buildCommoditySellerCards(bodyContent, 0, detailTop + 18, innerWidth, actionSellerIndices);
        buildTextStack(bodyContent, 0, bodyBottom + 8, innerWidth, buildGoodsActionLines(), TEXT_MUTED);

        int footerY = Math.max(118, Math.round(panel.getHeight()) - footerHeight - 10);
        UIRoundedRectangle footer = createPanel(panel, 14, footerY, innerWidth, footerHeight, 14f, CHROME_MUTED);
        footer.enableEffect(new OutlineEffect(BORDER, 1f));
        createMetricStrip(footer, 10, 10, innerWidth - 20, buildActionMetrics());
        int footerInputWidth = Math.max(124, Math.min(innerWidth - 20, innerWidth / 3 + 24));
        createText(footer, 10, 48, Component.translatable("screen.sailboatmod.market.buy_qty").getString(), 0.7f, TEXT_SOFT);
        UITextInput qtyInput = createInput(footer, 10, 62, footerInputWidth, 24,
                Component.translatable("screen.sailboatmod.market.buy_qty").getString(), buyQtyValue, value -> buyQtyValue = value);
        qtyInput.onActivate(value -> {
            buyQtyValue = value;
            sendBuy();
            return Unit.INSTANCE;
        });

        int footerButtonsY = 92;
        int footerButtonGap = 8;
        boolean ownListing = isSelectedListingOwnedByViewer();
        int footerButtonCount = 3;
        int footerButtonWidth = Math.max(84, (innerWidth - 20 - footerButtonGap * 2) / 3);
        createButton(footer, 10, footerButtonsY, footerButtonWidth, 24,
                Component.translatable(ownListing ? "screen.sailboatmod.market.unlist" : "screen.sailboatmod.market.buy").getString(),
                listing != null && (ownListing || data.linkedDock()), ownListing, ownListing ? this::cancelListing : this::sendBuy);
        createButton(footer, 10 + footerButtonWidth + footerButtonGap, footerButtonsY, footerButtonWidth, 24,
                Component.translatable("screen.sailboatmod.market.goods.go_sell").getString(),
                true, false, () -> {
                    activePage = MarketPage.SELL;
                    rebuildUi();
                });
        createButton(footer, 10 + (footerButtonWidth + footerButtonGap) * 2, footerButtonsY, footerButtonWidth, 24,
                Component.translatable("screen.sailboatmod.market.goods.back_market").getString(),
                true, true, this::backToGoodsListings);
    }

    private void buildGoodsOverviewPanel(UIComponent panel, int width) {
        int innerWidth = width - 28;
        MarketOverviewData.ListingEntry listing = activeGoodsView == GoodsPrimaryView.PRODUCT_PURCHASE
                ? selectedListing()
                : selectedCommodityRepresentativeListing();
        int iconBox = Math.min(112, Math.max(84, panel.getHeight() > 0 ? (int) panel.getHeight() - 42 : 104));

        UIRoundedRectangle visual = createPanel(panel, 14, 44, iconBox, iconBox, 16f, CHROME_MUTED);
        visual.enableEffect(new OutlineEffect(new Color(255, 255, 255, 20), 1f));
        if (listing != null) {
            createItemIcon(visual, 10, 10, iconBox - 20, iconBox - 20, resolveCommodityStack(listing.commodityKey()), true);
            createBadge(visual, 8, 8, 68, 18, rarityLabel(listing.rarity()), rarityColor(listing.rarity()), TEXT_PRIMARY);
            createText(visual, Math.max(8, iconBox - 54), Math.max(12, iconBox - 20), Component.translatable("screen.sailboatmod.market.count_short", listing.availableCount()).getString(), 0.72f, TEXT_PRIMARY);
        } else {
            createText(visual, 16, iconBox / 2 - 8, Component.translatable("screen.sailboatmod.market.header.brand_bottom").getString(), 0.92f, ACCENT);
        }

        int textX = 14 + iconBox + 14;
        int textWidth = innerWidth - iconBox - 14;
        createText(panel, textX, 46,
                shortenToWidth(listing == null ? marketTitleLine() : listing.itemName(), textWidth, 1.28f),
                1.28f, TEXT_PRIMARY);
        createText(panel, textX, 68,
                shortenToWidth(listing == null ? marketSubtitleLine() : buildSelectedListingHeroSubtitle(), textWidth, 0.72f),
                0.72f, TEXT_SOFT);
        createMetricStrip(panel, textX, 88, textWidth, listing == null ? buildGoodsOverviewMetrics() : buildListingMetrics());
        if (listing == null) {
            buildHotCommodityCards(panel, textWidth, textX, 140);
        } else {
            buildTextStack(panel, textX, 138, textWidth, buildListingDetailLines(), TEXT_MUTED);
        }
    }

    private void buildGoodsSellerSelectionPanel(UIComponent panel, int innerWidth) {
        MarketOverviewData.ListingEntry listing = selectedCommodityRepresentativeListing();
        if (listing == null) {
            buildTextStack(panel, 14, 92, innerWidth,
                    List.of(Component.translatable("screen.sailboatmod.market.empty").getString()), TEXT_MUTED);
            return;
        }
        ScrollComponent body = createPanelBodyScroll(panel, 118, 12);
        List<Integer> sellerIndices = selectedCommodityListingIndices();
        int expectedContentH = 20 + estimateCommoditySellerCardsHeight(sellerIndices.size());
        LOGGER.info("[MarketScroll] sellerPanel sellers={} expectedContentH={} bodyViewportH={}", sellerIndices.size(), expectedContentH, Math.round(body.getHeight()));
        body.addScrollAdjustEvent(false, (pct, pctOfParent) -> {
            LOGGER.info("[MarketScroll] sellerScroll pct={} overhang={} offset={}", pct, body.getVerticalOverhang(), body.getVerticalOffset());
            return Unit.INSTANCE;
        });
        createText(body, 0, 0, Component.translatable("screen.sailboatmod.market.goods.select_seller").getString(), 0.86f, TEXT_PRIMARY);
        createText(body, Math.max(100, innerWidth - 132), 2, Component.translatable("screen.sailboatmod.market.goods.select_seller_hint").getString(), 0.64f, TEXT_SOFT);
        buildCommoditySellerCards(body, 0, 20, innerWidth, sellerIndices);
    }

    private void buildHotCommodityCards(UIComponent panel, int innerWidth, int startX, int startY) {
        List<HotCommodity> hot = buildHotCommodities();
        if (hot.isEmpty()) {
            return;
        }
        int count = Math.min(3, hot.size());
        int cardWidth = (innerWidth - (count - 1) * 10) / count;
        for (int i = 0; i < count; i++) {
            HotCommodity commodity = hot.get(i);
            int x = startX + i * (cardWidth + 10);
            UIRoundedRectangle card = createPanel(panel, x, startY, cardWidth, 40, 12f, CARD_BG_SOFT);
            card.enableEffect(new OutlineEffect(new Color(255, 255, 255, 14), 1f));
            createAccentBar(card, 0, 0, 4, 40, i == 0 ? ACCENT : i == 1 ? new Color(95, 162, 198) : POSITIVE);
            createText(card, 12, 7, shorten(commodity.itemName(), Math.max(10, cardWidth / 9)), 0.8f, TEXT_PRIMARY);
            createText(card, 12, 22, commodity.summary(), 0.68f, TEXT_SOFT);
            card.onMouseEnterRunnable(() -> card.setColor(ROW_HOVER));
            card.onMouseLeaveRunnable(() -> card.setColor(CARD_BG_SOFT));
            card.onMouseClickConsumer(event -> runPreservingScroll(() -> {
                selectedListingIndex = commodity.listingIndex();
                rebuildUi();
            }));
        }
    }

    private void buildGoodsCatalogPanel(UIComponent panel, int width, int height) {
        int innerWidth = width - 28;
        int searchWidth = Math.min(240, Math.max(156, innerWidth / 3));
        UITextInput searchInput = createInput(panel, 14, 44, searchWidth, 24,
                Component.translatable("screen.sailboatmod.market.catalog.search").getString(),
                goodsSearchValue, value -> goodsSearchValue = value);
        searchInput.onActivate(value -> {
            runPreservingScroll(() -> {
                goodsSearchValue = value;
                goodsCatalogPage = 0;
                rebuildUi();
            });
            return Unit.INSTANCE;
        });

        int sortWidth = Math.max(88, Math.min(120, (innerWidth - searchWidth - 32) / 3));
        int sortX = 14 + searchWidth + 8;
        for (GoodsCatalogSort sort : GoodsCatalogSort.values()) {
            createGoodsPanelTab(panel, sortX, 44, sortWidth, Component.translatable(sort.labelKey).getString(),
                    goodsCatalogSort == sort, () -> {
                        goodsCatalogSort = sort;
                        goodsCatalogPage = 0;
                        rebuildUi();
                    });
            sortX += sortWidth + 6;
        }

        int filterX = 14;
        String categoryLabel = Component.translatable("screen.sailboatmod.market.catalog.filter.category").getString()
                + ": " + Component.translatable(goodsCategoryFilter.labelKey).getString();
        int categoryWidth = Math.max(116, Math.min(188, categoryLabel.length() * 7 + 16));
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
        int priceWidth = Math.max(100, Math.min(132, priceLabel.length() * 7 + 16));
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
                runPreservingScroll(() -> {
                    priceFilterMinValue = value;
                    goodsCatalogPage = 0;
                    rebuildUi();
                });
                return Unit.INSTANCE;
            });

            createText(pricePanel, (priceWidth + 120) / 2 + 4, 6, Component.translatable("screen.sailboatmod.market.catalog.price.max").getString(), 0.7f, TEXT_SOFT);
            UITextInput maxInput = createInput(pricePanel, (priceWidth + 120) / 2 + 4, 20, (priceWidth + 120) / 2 - 12, 22,
                    "999999", priceFilterMaxValue, value -> priceFilterMaxValue = value);
            maxInput.onActivate(value -> {
                runPreservingScroll(() -> {
                    priceFilterMaxValue = value;
                    goodsCatalogPage = 0;
                    rebuildUi();
                });
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

        int dropdownHeight = goodsCatalogDropdownHeight();

        buildGoodsCatalogSummary(panel, innerWidth, filtered.size(), data.listingEntries().size(), dropdownHeight);
        int catalogScrollHeight = Math.max(190, height - 172 - dropdownHeight);
        LOGGER.info("[MarketScroll] goodsCatalog panelH={} height={} dropdown={} scrollH={} filtered={}", Math.round(panel.getHeight()), height, dropdownHeight, catalogScrollHeight, filtered.size());
        buildGoodsCatalogRows(panel, innerWidth, catalogScrollHeight, filtered, dropdownHeight);
    }

    private void buildGoodsCatalogSummary(UIComponent panel, int innerWidth, int filteredCount, int totalCount, int dropdownHeight) {
        int baseY = 100 + dropdownHeight;
        createText(panel, 14, baseY, buildGoodsCatalogSummaryText(filteredCount, totalCount), 0.76f, TEXT_MUTED);
        createText(panel, 14, baseY + 14, buildGoodsCatalogFilterSummary(), 0.7f, TEXT_SOFT);

        int pageCount = goodsCatalogPageCount(filteredCount);
        if (pageCount <= 1) return;

        // Build page number list: always show first 2, last 2, current±1, with "..." gaps
        java.util.LinkedHashSet<Integer> pageSet = new java.util.LinkedHashSet<>();
        pageSet.add(0);
        if (pageCount > 1) pageSet.add(1);
        for (int p = goodsCatalogPage - 1; p <= goodsCatalogPage + 1; p++) {
            if (p >= 0 && p < pageCount) pageSet.add(p);
        }
        if (pageCount > 2) pageSet.add(pageCount - 2);
        pageSet.add(pageCount - 1);
        List<Integer> pages = new ArrayList<>(pageSet);
        java.util.Collections.sort(pages);

        int btnW = 26;
        int btnH = 22;
        int gap = 4;
        int prevW = 44;
        int nextW = 44;
        int buttonY = baseY + 28; // below summary text
        int curX = 14;

        // Prev button
        createButton(panel, curX, buttonY, prevW, btnH,
                Component.translatable("screen.sailboatmod.market.catalog.prev").getString(),
                goodsCatalogPage > 0, true, () -> {
                    goodsCatalogPage = Math.max(0, goodsCatalogPage - 1);
                    goodsCatalogScrollOffset = 0f;
                    rebuildUi();
                });
        curX += prevW + gap;

        // Page number buttons with ellipsis
        int prev = -1;
        for (int p : pages) {
            if (prev >= 0 && p > prev + 1) {
                // ellipsis
                createText(panel, curX + 4, buttonY + 7, "...", 0.74f, TEXT_MUTED);
                curX += 20 + gap;
            }
            final int fp = p;
            boolean isCurrent = (p == goodsCatalogPage);
            createButton(panel, curX, buttonY, btnW, btnH,
                    String.valueOf(p + 1), !isCurrent, isCurrent, () -> {
                        goodsCatalogPage = fp;
                        goodsCatalogScrollOffset = 0f;
                        rebuildUi();
                    });
            curX += btnW + gap;
            prev = p;
        }

        // Next button
        curX += 2;
        createButton(panel, curX, buttonY, nextW, btnH,
                Component.translatable("screen.sailboatmod.market.catalog.next").getString(),
                goodsCatalogPage + 1 < pageCount, true, () -> {
                    goodsCatalogPage = Math.min(pageCount - 1, goodsCatalogPage + 1);
                    goodsCatalogScrollOffset = 0f;
                    rebuildUi();
                });
    }

    private void buildGoodsCatalogRows(UIComponent panel, int width, int height, List<Integer> filtered, int dropdownHeight) {
        List<Integer> visible = pagedListingIndices(filtered);
        if (!visible.isEmpty() && !visible.contains(selectedListingIndex)) {
            selectedListingIndex = visible.get(0);
        }

        int scrollY = goodsCatalogScrollY(dropdownHeight);
        GoodsCatalogGridLayout layout = goodsCatalogGridLayout(width, visible.size());

        // When outer viewport scroll exists, use plain UIBlock (no nested scroll conflict)
        if (goodsViewportScrollRef != null) {
            UIBlock grid = new UIBlock(new Color(0, 0, 0, 0));
            grid.setX(new PixelConstraint(14 + layout.startX()));
            grid.setY(new PixelConstraint(scrollY));
            grid.setWidth(new PixelConstraint(layout.gridWidth()));
            grid.setHeight(new PixelConstraint(layout.contentHeight()));
            grid.setChildOf(panel);
            if (visible.isEmpty()) {
                createRow(grid, width - 8, rowSpec(
                        Component.translatable("screen.sailboatmod.market.empty").getString(),
                        Component.translatable("screen.sailboatmod.market.catalog.empty_hint").getString(),
                        "", false, null));
                return;
            }
            for (int slot = 0; slot < visible.size(); slot++) {
                int index = visible.get(slot);
                int col = slot % layout.columns();
                int row = slot / layout.columns();
                createGoodsCatalogCard(grid,
                        col * (layout.cardWidth() + layout.gap()),
                        row * (layout.cardHeight() + layout.gap()),
                        layout.cardWidth(),
                        layout.cardHeight(),
                        data.listingEntries().get(index),
                        index);
            }
            return;
        }

        float restoreOffset = goodsCatalogScrollOffset;
        float restoreProgress = goodsCatalogScrollProgress;
        ScrollComponent scroll = new ScrollComponent();
        scroll.setX(new PixelConstraint(14));
        scroll.setY(new PixelConstraint(scrollY));
        scroll.setWidth(new PixelConstraint(width));
        scroll.setHeight(new PixelConstraint(height));
        scroll.setChildOf(panel);
        scroll.setColor(new Color(0, 0, 0, 0));
        goodsCatalogScrollRef = scroll;
        trackNestedScroll(scroll, true);

        if (visible.isEmpty()) {
            createRow(scroll, width - 8,
                    rowSpec(Component.translatable("screen.sailboatmod.market.empty").getString(),
                            Component.translatable("screen.sailboatmod.market.catalog.empty_hint").getString(),
                            "", false, null));
            return;
        }

        UIBlock grid = new UIBlock(new Color(0, 0, 0, 0));
        grid.setX(new PixelConstraint(layout.startX()));
        grid.setY(new PixelConstraint(0));
        grid.setWidth(new PixelConstraint(layout.gridWidth()));
        grid.setHeight(new PixelConstraint(layout.contentHeight()));
        grid.setChildOf(scroll);

        for (int slot = 0; slot < visible.size(); slot++) {
            int index = visible.get(slot);
            int col = slot % layout.columns();
            int row = slot / layout.columns();
            createGoodsCatalogCard(grid,
                    col * (layout.cardWidth() + layout.gap()),
                    row * (layout.cardHeight() + layout.gap()),
                    layout.cardWidth(),
                    layout.cardHeight(),
                    data.listingEntries().get(index),
                    index);
        }

        restoreScroll(scroll, restoreOffset, restoreProgress);
    }

    private int goodsCatalogDropdownHeight() {
        if (showPriceFilter) {
            return 80;
        }
        if (showCategoryFilter) {
            return GoodsCategoryFilter.values().length * 24;
        }
        return 0;
    }

    private int goodsCatalogScrollY(int dropdownHeight) {
        return 158 + dropdownHeight;
    }

    private GoodsCatalogGridLayout goodsCatalogGridLayout(int width, int visibleCount) {
        int columns = 3;
        int gap = 12;
        int rawCardWidth = Math.max(96, (width - (columns - 1) * gap) / columns);
        int cardWidth = Math.min(160, rawCardWidth);
        int cardHeight = cardWidth + 60;
        int rows = Math.max(1, (Math.max(visibleCount, 1) + columns - 1) / columns);
        int gridWidth = columns * cardWidth + (columns - 1) * gap;
        int startX = Math.max(0, (width - gridWidth) / 2);
        int contentHeight = Math.max(cardHeight, rows * (cardHeight + gap) + 40);
        return new GoodsCatalogGridLayout(columns, gap, cardWidth, cardHeight, gridWidth, startX, contentHeight);
    }

    private void createGoodsCatalogCard(UIComponent parent, int x, int y, int width, int height,
                                        MarketOverviewData.ListingEntry entry, int index) {
        boolean selected = index == selectedListingIndex;
        UIRoundedRectangle card = createPanel(parent, x, y, width, height, 14f, selected ? ROW_SELECTED : CARD_BG_SOFT);
        card.enableEffect(new OutlineEffect(new Color(255, 255, 255, selected ? 28 : 14), 1f));

        if (!selected) {
            card.onMouseEnterRunnable(() -> card.setColor(ROW_HOVER));
            card.onMouseLeaveRunnable(() -> card.setColor(CARD_BG_SOFT));
        }
        card.onMouseClickConsumer(event -> runPreservingScroll(() -> {
            selectedCommodityKey = entry.commodityKey();
            selectedListingIndex = index;
            activeGoodsView = GoodsPrimaryView.PRODUCT_LISTINGS;
            rebuildUi();
        }));

        int previewSize = Math.max(64, width - 16);
        UIRoundedRectangle preview = createPanel(card, 8, 8, width - 16, previewSize, 12f, new Color(68, 74, 84, 255));
        preview.enableEffect(new OutlineEffect(new Color(255, 255, 255, 12), 1f));
        createItemIcon(preview, 10, 8, width - 36, previewSize - 18, resolveCommodityStack(entry.commodityKey()), true);
        createBadge(preview, 6, 6, 64, 16, rarityLabel(entry.rarity()), rarityColor(entry.rarity()), TEXT_PRIMARY);

        createText(card, Math.max(12, width - 34), 12, "x" + entry.availableCount(), 0.68f, TEXT_MUTED);
        createText(card, 12, previewSize + 16,
                shortenToWidth(entry.itemName(), width - 24, 0.78f), 0.78f, TEXT_PRIMARY);
        createText(card, 12, previewSize + 30,
                shortenToWidth(entry.sellerName(), width - 24, 0.64f), 0.64f, TEXT_SOFT);
        createText(card, 12, height - 18, formatCompactLong(entry.unitPrice()), 0.82f, ACCENT);
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
        row.onMouseClickConsumer(event -> runPreservingScroll(() -> {
            selectedListingIndex = index;
            rebuildUi();
        }));

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
        createMetricStrip(panel, 14, 38, innerWidth, buildBuyOrdersOverviewMetrics());
        buildTextStack(panel, 14, 90, innerWidth, buildBuyOrdersOverviewLines(), TEXT_MUTED);
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
        button.onMouseClickConsumer(event -> runPreservingScroll(action));
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
        option.onMouseClickConsumer(event -> runPreservingScroll(action));
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
            createText(frame, width - 98, 10, Component.translatable("screen.sailboatmod.market.price_chart.buckets", series.points().size()).getString(), 0.82f, TEXT_SOFT);
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
        int panelH = Math.round(panel.getHeight());

        // Outer scroll wrapping the entire right panel content
        ScrollComponent outerScroll = new ScrollComponent();
        outerScroll.setX(new PixelConstraint(0));
        outerScroll.setY(new PixelConstraint(36)); // below section title
        outerScroll.setWidth(new PixelConstraint(width));
        outerScroll.setHeight(new PixelConstraint(Math.max(100, panelH - 36)));
        outerScroll.setColor(new Color(0, 0, 0, 0));
        outerScroll.setChildOf(panel);

        UIBlock outerContent = new UIBlock(new Color(0, 0, 0, 0));
        outerContent.setX(new PixelConstraint(0));
        outerContent.setY(new PixelConstraint(0));
        outerContent.setWidth(new PixelConstraint(width));
        outerContent.setHeight(new PixelConstraint(8 + 286 + 240 + 16));
        outerContent.setChildOf(outerScroll);

        MarketOverviewData.StorageEntry entry = selectedStorage();
        int headerTop = 8;
        UIRoundedRectangle terminalHeader = createPanel(outerContent, 14, headerTop, innerWidth, 64, 16f, CHROME_MUTED);
        terminalHeader.enableEffect(new OutlineEffect(BORDER, 1f));
        UIRoundedRectangle iconPlate = createPanel(terminalHeader, 10, 10, 46, 46, 12f, CARD_BG_SOFT);
        iconPlate.enableEffect(new OutlineEffect(new Color(255, 255, 255, 14), 1f));
        createItemIcon(iconPlate, 7, 7, 32, 32, resolveStorageStack(entry), true);
        int badgeW = 82;
        int badgeX = Math.max(66, innerWidth - badgeW - 8);
        int textMaxW = Math.max(60, badgeX - 66 - 6);
        createText(terminalHeader, 66, 10,
                shortenToWidth(entry == null ? Component.translatable("screen.sailboatmod.market.storage_empty").getString() : entry.itemName(),
                        textMaxW, 1.02f),
                1.02f, TEXT_PRIMARY);
        createText(terminalHeader, 66, 26,
                shortenToWidth(entry == null ? Component.translatable("screen.sailboatmod.market.sell.wait_select").getString() : entry.detail(),
                        textMaxW, 0.68f),
                0.68f, TEXT_SOFT);
        createText(terminalHeader, 66, 42,
                shortenToWidth(entry == null ? currentDockLine() : Component.translatable("screen.sailboatmod.market.sell.header_stock_price", entry.quantity(), formatCompactLong(entry.suggestedUnitPrice())).getString(),
                        textMaxW, 0.7f),
                0.7f, TEXT_MUTED);
        createBadge(terminalHeader, badgeX, 12, badgeW, 18,
                data.linkedDock() ? Component.translatable("screen.sailboatmod.market.sell.badge.dock_online").getString() : Component.translatable("screen.sailboatmod.market.sell.badge.no_dock").getString(),
                data.linkedDock() ? new Color(50, 100, 92, 220) : new Color(102, 54, 54, 220),
                TEXT_PRIMARY);
        createBadge(terminalHeader, badgeX, 34, badgeW, 18,
                data.dockStorageAccessible() ? Component.translatable("screen.sailboatmod.market.sell.badge.post_allowed").getString() : Component.translatable("screen.sailboatmod.market.sell.badge.read_only").getString(),
                data.dockStorageAccessible() ? new Color(70, 88, 120, 220) : new Color(96, 68, 48, 220),
                TEXT_MUTED);

        createMetricStrip(outerContent, 14, headerTop + 74, innerWidth, buildStorageMetrics());

        UIRoundedRectangle detailPanel = createPanel(outerContent, 14, headerTop + 128, innerWidth, 148, 14f, CARD_BG_SOFT);
        detailPanel.enableEffect(new OutlineEffect(new Color(255, 255, 255, 12), 1f));
        createText(detailPanel, 10, 10, Component.translatable("screen.sailboatmod.market.sell.detail_title").getString(), 0.78f, TEXT_PRIMARY);
        buildDetailInfoRows(detailPanel, 10, 28, innerWidth - 20, buildSellDetailRows(entry));

        int contentTop = headerTop + 286;
        int contentHeight = 240;
        int splitGap = 10;
        int leftWidth = Math.max(144, Math.min(228, Math.round(innerWidth * 0.4f)));
        int rightWidth = innerWidth - leftWidth - splitGap;

        UIRoundedRectangle infoPanel = createPanel(outerContent, 14, contentTop, leftWidth, contentHeight, 14f, CHROME_MUTED);
        infoPanel.enableEffect(new OutlineEffect(BORDER, 1f));
        createText(infoPanel, 10, 10, Component.translatable("screen.sailboatmod.market.sell.info_title").getString(), 0.78f, TEXT_PRIMARY);
        buildTextStack(infoPanel, 10, 28, leftWidth - 20, buildSellInfoLines(), TEXT_MUTED);
        buildTextStack(infoPanel, 10, 86, leftWidth - 20, buildSellActionLines(), TEXT_SOFT);

        UIRoundedRectangle actionFrame = createPanel(outerContent, 14 + leftWidth + splitGap, contentTop, rightWidth, contentHeight, 14f, CHROME_MUTED);
        actionFrame.enableEffect(new OutlineEffect(BORDER, 1f));
        createAccentBar(actionFrame, 0, 0, 5, contentHeight, ACCENT_DIM);

        UIBlock actionContent = new UIBlock(new Color(0, 0, 0, 0));
        actionContent.setX(new PixelConstraint(12));
        actionContent.setY(new PixelConstraint(6));
        actionContent.setWidth(new PixelConstraint(rightWidth - 24));
        actionContent.setHeight(new PixelConstraint(contentHeight - 12));
        actionContent.setChildOf(actionFrame);

        createText(actionContent, 0, 0, Component.translatable("screen.sailboatmod.market.sell.action_title").getString(), 0.78f, TEXT_PRIMARY);
        createText(actionContent, 0, 18, Component.translatable("screen.sailboatmod.market.qty").getString(), 0.72f, TEXT_SOFT);

        UITextInput qtyInput = createInput(actionContent, 0, 32, Math.min(Math.max(80, rightWidth - 32), 120), 26,
                Component.translatable("screen.sailboatmod.market.qty").getString(), listingQtyValue, value -> listingQtyValue = value);
        createText(actionContent, 0, 68, Component.translatable("screen.sailboatmod.market.price_adjust").getString(), 0.72f, TEXT_SOFT);
        UITextInput bpInput = createInput(actionContent, 0, 82, Math.min(Math.max(80, rightWidth - 32), 120), 26,
                "+5% / -10%", listingPriceAdjustValue,
                value -> listingPriceAdjustValue = value);
        bpInput.onActivate(value -> {
            listingPriceAdjustValue = value;
            rebuildUi(false);
            return Unit.INSTANCE;
        });

        UIRoundedRectangle confirmPanel = createPanel(actionContent, 0, 118, rightWidth - 32, 56, 12f, new Color(31, 40, 58, 238));
        confirmPanel.enableEffect(new OutlineEffect(new Color(255, 255, 255, 14), 1f));
        createText(confirmPanel, 10, 10, Component.translatable("screen.sailboatmod.market.sell.confirm_title").getString(), 0.72f, TEXT_SOFT);
        createText(confirmPanel, 10, 26,
                shortenToWidth(buildSellConfirmSummary(), Math.max(80, rightWidth - 40), 0.86f),
                0.86f, TEXT_PRIMARY);

        createButton(actionContent, 0, 184, rightWidth - 32, 30,
                Component.translatable("screen.sailboatmod.market.list_hand").getString(),
                selectedStorage() != null && data.dockStorageAccessible() && data.linkedDock(), false, this::createListing);
        buildTextStack(actionContent, 0, 224, rightWidth - 32, buildSellActionLines(), TEXT_SOFT);
    }

    private void buildSellStoragePanel(UIComponent panel, int width, int height) {
        int innerWidth = width - 28;
        List<Integer> filtered = filteredStorageIndices();

        // Search input (fixed, not scrolled)
        UITextInput searchInput = createInput(panel, 14, 54, innerWidth, 24,
                Component.translatable("screen.sailboatmod.market.sell.search_storage").getString(), storageSearchValue, value -> storageSearchValue = value);
        searchInput.onActivate(value -> {
            runPreservingScroll(() -> {
                storageSearchValue = value;
                rebuildUi();
            });
            return Unit.INSTANCE;
        });

        // Scrollable content below search
        int scrollTop = 86;
        int scrollHeight = Math.max(100, height - scrollTop - 8);
        UIRoundedRectangle scrollFrame = createPanel(panel, 0, scrollTop, width, scrollHeight, 0f, new Color(0, 0, 0, 0));

        ScrollComponent scroll = new ScrollComponent();
        scroll.setX(new PixelConstraint(0));
        scroll.setY(new PixelConstraint(0));
        scroll.setWidth(new PixelConstraint(width));
        scroll.setHeight(new PixelConstraint(scrollHeight));
        scroll.setColor(new Color(0, 0, 0, 0));
        scroll.setChildOf(scrollFrame);

        UIBlock scrollContent = new UIBlock(new Color(0, 0, 0, 0));
        scrollContent.setX(new PixelConstraint(0));
        scrollContent.setY(new PixelConstraint(0));
        scrollContent.setWidth(new PixelConstraint(width));
        // height set below after listHeight is computed
        scrollContent.setChildOf(scroll);

        // Summary text
        createText(scrollContent, 14, 0, buildSellStorageSummaryText(filtered.size()), 0.66f, TEXT_SOFT);

        // Overview card (可见货物/仓储总量) - aligned to scrollContent
        UIRoundedRectangle overviewCard = createPanel(scrollContent, 14, 16, innerWidth, 44, 12f, CARD_BG_SOFT);
        overviewCard.enableEffect(new OutlineEffect(new Color(255, 255, 255, 12), 1f));
        buildDetailInfoRows(overviewCard, 10, 8, innerWidth - 20, buildSellStorageOverviewRows(filtered.size()));

        // Selected item card - name centered above card
        MarketOverviewData.StorageEntry selected = selectedStorage();
        int cardTop = 68;
        // Item name label above the card, centered
        String cardLabel = selected == null
                ? Component.translatable("screen.sailboatmod.market.sell.no_selection").getString()
                : selected.itemName();
        createText(scrollContent, 14, cardTop, shortenToWidth(cardLabel, innerWidth, 0.82f), 0.82f, TEXT_PRIMARY);

        UIRoundedRectangle selectedCard = createPanel(scrollContent, 14, cardTop + 14, innerWidth, 62, 14f, CHROME_MUTED);
        selectedCard.enableEffect(new OutlineEffect(BORDER, 1f));
        UIRoundedRectangle selectedIcon = createPanel(selectedCard, 12, 11, 40, 40, 12f, CARD_BG_SOFT);
        selectedIcon.enableEffect(new OutlineEffect(new Color(255, 255, 255, 10), 1f));
        createItemIcon(selectedIcon, 8, 8, 24, 24, resolveStorageStack(selected), true);
        createText(selectedCard, 62, 8,
                shortenToWidth(selected == null ? Component.translatable("screen.sailboatmod.market.sell.select_hint").getString()
                        : selected.detail(), innerWidth - 74, 0.68f),
                0.68f, TEXT_SOFT);
        createText(selectedCard, 62, 26,
                shortenToWidth(selected == null ? currentDockLine()
                        : Component.translatable("screen.sailboatmod.market.sell.selected_stock_price", selected.quantity(), formatCompactLong(selected.suggestedUnitPrice())).getString(),
                        innerWidth - 74, 0.68f),
                0.68f, TEXT_MUTED);

        // Storage rows list — height based on actual row count to avoid nested scroll conflict
        int listTop = cardTop + 14 + 62 + 8;
        int rowCount = Math.max(1, filtered.size());
        int listHeight = rowCount * 62 + (rowCount - 1) * 8 + 20; // rows + gaps + padding
        int contentHeight = listTop + listHeight + 8;
        scrollContent.setHeight(new PixelConstraint(contentHeight));
        buildStorageRowsPanel(scrollContent, 14, listTop, innerWidth, listHeight, filtered);
    }

    private void buildStorageRowsPanel(UIComponent panel, int x, int y, int width, int height, List<Integer> filteredIndices) {
        UIRoundedRectangle frame = createPanel(panel, x, y, width, height, 14f, new Color(17, 24, 37, 210));
        frame.enableEffect(new OutlineEffect(new Color(255, 255, 255, 10), 1f));

        UIBlock rows = new UIBlock(new Color(0, 0, 0, 0));
        rows.setX(new PixelConstraint(6));
        rows.setY(new PixelConstraint(6));
        rows.setWidth(new PixelConstraint(width - 12));
        rows.setHeight(new PixelConstraint(height - 12));
        rows.setChildOf(frame);

        if (filteredIndices.isEmpty()) {
            createStorageRow(rows, width - 20, null, -1, false);
            return;
        }
        for (int index : filteredIndices) {
            MarketOverviewData.StorageEntry entry = data.dockStorageEntries().get(index);
            createStorageRow(rows, width - 20, entry, index, index == selectedStorageIndex);
        }
    }

    private void createStorageRow(UIComponent parent, int width, MarketOverviewData.StorageEntry entry, int index, boolean selected) {
        UIRoundedRectangle row = new UIRoundedRectangle(12f);
        row.setX(new PixelConstraint(0));
        row.setY(new CramSiblingConstraint(8f));
        row.setWidth(new PixelConstraint(width));
        row.setHeight(new PixelConstraint(62));
        row.setColor(selected ? ROW_SELECTED : ROW_BG);
        row.setChildOf(parent);
        row.enableEffect(new OutlineEffect(new Color(255, 255, 255, selected ? 22 : 10), 1f));
        createAccentBar(row, 0, 0, selected ? 4 : 3, 62, selected ? ACCENT : new Color(255, 255, 255, 14));

        if (entry == null) {
            createText(row, 14, 16, Component.translatable("screen.sailboatmod.market.storage_empty").getString(), 0.82f, TEXT_MUTED);
            return;
        }

        if (index >= 0) {
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
            row.onMouseClickConsumer(event -> runPreservingScroll(() -> {
                selectedStorageIndex = index;
                rebuildUi();
            }));
        }

        UIRoundedRectangle token = createPanel(row, 12, 15, 32, 32, 10f, new Color(34, 43, 61, 220));
        token.enableEffect(new OutlineEffect(new Color(255, 255, 255, 10), 1f));
        createItemIcon(token, 8, 8, 16, 16, resolveStorageStack(entry), true);

        int textX = 56;
        int textWidth = Math.max(90, width - 144);
        createText(row, textX, 8, shortenToWidth(entry.itemName(), textWidth, 0.84f), 0.84f, TEXT_PRIMARY);
        createText(row, textX, 22, shortenToWidth(entry.detail(), textWidth, 0.66f), 0.66f, TEXT_SOFT);
        createText(row, textX, 40, shortenToWidth(Component.translatable("screen.sailboatmod.market.sell.detail.stock").getString() + " x" + entry.quantity(), textWidth - 54, 0.72f), 0.72f, POSITIVE);
        createText(row, width - 88, 40, shortenToWidth(formatCompactLong(entry.suggestedUnitPrice()), 78, 0.72f), 0.72f, ACCENT_DIM);
    }

    private void buildDispatchActionPanel(UIComponent panel, int width) {
        int innerWidth = width - 28;
        createMetricStrip(panel, 14, 48, innerWidth, buildDispatchMetrics());
        buildTextStack(panel, 14, 96, innerWidth, buildDispatchActionLines(), TEXT_MUTED);
        createButton(panel, 14, 226, innerWidth, 28,
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
                Component.translatable("screen.sailboatmod.market.finance.metric.stockpile").getString(),
                data.stockpileCommodityTypes() + " / " + data.stockpileTotalUnits(), ACCENT_DIM);
        buildMetricTile(panel, 14, 98, innerWidth, 44,
                Component.translatable("screen.sailboatmod.market.finance.metric.demand").getString(),
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
        createMetricStrip(panel, 14, 42, innerWidth, buildBuyOrdersMetrics());
        int detailY = 90;
        List<String> detailLines = buildBuyOrderDetailLines();
        buildTextStack(panel, 14, detailY, innerWidth, detailLines, TEXT_MUTED);
        int fieldsTop = detailY + estimateTextStackHeight(innerWidth, detailLines) + 14;

        int columnGap = 10;
        int fieldWidth = Math.max(90, (innerWidth - columnGap) / 2);
        int rightColumnX = 14 + fieldWidth + columnGap;

        createText(panel, 14, fieldsTop, Component.translatable("screen.sailboatmod.market.buy_order.commodity").getString().replace("%s", ""), 0.7f, TEXT_SOFT);
        createText(panel, rightColumnX, fieldsTop, Component.translatable("screen.sailboatmod.market.buy_order.qty").getString(), 0.7f, TEXT_SOFT);
        createInput(panel, 14, fieldsTop + 12, fieldWidth, 24,
                Component.translatable("screen.sailboatmod.market.buy_order.commodity").getString().replace("%s", ""),
                buyOrderCommodityValue, value -> buyOrderCommodityValue = value);
        createInput(panel, rightColumnX, fieldsTop + 12, fieldWidth, 24,
                Component.translatable("screen.sailboatmod.market.buy_order.qty").getString(),
                buyOrderQtyValue, value -> buyOrderQtyValue = value);

        int priceTop = fieldsTop + 44;
        createText(panel, 14, priceTop, Component.translatable("screen.sailboatmod.market.buy_order.min_price").getString(), 0.7f, TEXT_SOFT);
        createText(panel, rightColumnX, priceTop, Component.translatable("screen.sailboatmod.market.buy_order.max_price").getString(), 0.7f, TEXT_SOFT);
        createInput(panel, 14, priceTop + 12, fieldWidth, 24,
                Component.translatable("screen.sailboatmod.market.buy_order.min_price").getString(),
                buyOrderMinPriceValue, value -> buyOrderMinPriceValue = value);
        createInput(panel, rightColumnX, priceTop + 12, fieldWidth, 24,
                Component.translatable("screen.sailboatmod.market.buy_order.max_price").getString(),
                buyOrderMaxPriceValue, value -> buyOrderMaxPriceValue = value);

        int useSelectedY = priceTop + 44;
        createButton(panel, 14, useSelectedY, innerWidth, 20,
                Component.translatable("screen.sailboatmod.market.buy_order.use_selected").getString(),
                selectedListing() != null, true, this::useSelectedListingForBuyOrder);

        int createY = useSelectedY + 28;
        createButton(panel, 14, createY, innerWidth, 28,
                Component.translatable("screen.sailboatmod.market.buy_order.create").getString(),
                !resolvedBuyOrderCommodityValue().isBlank(), false, this::createBuyOrder);
        int cancelY = createY + 34;
        createButton(panel, 14, cancelY, innerWidth, 22,
                Component.translatable("screen.sailboatmod.market.buy_order.cancel").getString(),
                selectedBuyOrder() != null, true, this::cancelBuyOrder);

        buildTextStack(panel, 14, cancelY + 30, innerWidth, buildBuyOrderActionLines(), TEXT_MUTED);
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
        float restoreOffset = buyOrdersListScrollOffset;
        float restoreProgress = buyOrdersListScrollProgress;
        ScrollComponent scroll = new ScrollComponent();
        scroll.setX(new PixelConstraint(14));
        scroll.setY(new PixelConstraint(48));
        scroll.setWidth(new PixelConstraint(width));
        scroll.setHeight(new PixelConstraint(height));
        scroll.setChildOf(panel);
        scroll.setColor(new Color(0, 0, 0, 0));
        buyOrdersListScrollRef = scroll;
        trackNestedScroll(scroll, false);

        for (RowSpec row : rows) {
            createRow(scroll, width - 8, row);
        }
        restoreScroll(scroll, restoreOffset, restoreProgress);
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
            row.onMouseClickConsumer(event -> runPreservingScroll(spec.action));
        }

        createAccentBar(row, 0, 0, spec.selected ? 6 : 4, ROW_HEIGHT, spec.selected ? ACCENT : new Color(255, 255, 255, 18));
        int trailingReserve = spec.trailing.isBlank() ? 12 : 68;
        createText(row, 18, 12, shortenToWidth(spec.title, width - trailingReserve - 18, 1.0f), 1.0f, TEXT_PRIMARY);
        if (!spec.trailing.isBlank()) {
            createText(row, width - 60, 12, shortenToWidth(spec.trailing, 48, 0.9f), 0.9f, spec.selected ? ACCENT : TEXT_MUTED);
        }
        if (!spec.subtitle.isBlank()) {
            createText(row, 18, 22,
                    shortenToWidth(spec.subtitle, width - trailingReserve - 18, 0.7f),
                    0.7f, TEXT_MUTED);
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

    private List<RowSpec> buildFilteredStorageRows() {
        String query = storageSearchValue == null ? "" : storageSearchValue.trim().toLowerCase(Locale.ROOT);
        List<RowSpec> rows = new ArrayList<>();
        for (int i = 0; i < data.dockStorageEntries().size(); i++) {
            MarketOverviewData.StorageEntry entry = data.dockStorageEntries().get(i);
            if (!query.isBlank()) {
                String haystack = (entry.itemName() + " " + entry.detail()).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) {
                    continue;
                }
            }
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
                    entry.targetDockName().isBlank() ? entry.sourceDockName() : entry.targetDockName(),
                    Component.translatable("screen.sailboatmod.market.dispatch.route", entry.sourceDockName()).getString(),
                    "x" + entry.quantity(),
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
                    entry.mode().isBlank() ? "" : shorten(entry.mode(), 10),
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
                            + " | " + entry.minPriceBp() + " - " + entry.maxPriceBp() + " bp",
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
        // Deduplicate by commodityKey — catalog shows one card per commodity
        List<Integer> deduped = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int idx : indices) {
            String key = data.listingEntries().get(idx).commodityKey();
            if (seen.add(key)) {
                deduped.add(idx);
            }
        }
        return deduped;
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
            int bp = parsePriceAdjustment(listingPriceAdjustValue);
            String bpDisplay = bp == 0 ? "0%" : (bp > 0 ? "+" : "") + bp + "%";
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.price_adjust").getString(), bpDisplay, ACCENT));
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
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.tab.shipping").getString(),
                    ship.boatName(), POSITIVE));
            metrics.add(metric(Component.translatable("screen.sailboatmod.market.dispatch.mode").getString(),
                    ship.mode().isBlank() ? "-" : ship.mode(), new Color(111, 149, 198)));
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

    private List<DetailRow> buildListingDetailRows(MarketOverviewData.ListingEntry entry) {
        List<DetailRow> rows = new ArrayList<>();
        if (entry == null) {
            rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.catalog.column.item").getString(),
                    Component.translatable("screen.sailboatmod.market.empty").getString(), TEXT_MUTED));
            return rows;
        }
        rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.catalog.column.item").getString(), entry.itemName(), TEXT_PRIMARY));
        rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.catalog.column.seller").getString(), entry.sellerName(), TEXT_PRIMARY));
        rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.catalog.column.stock").getString(), "x" + entry.availableCount(), POSITIVE));
        rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.metric.price").getString(), formatCompactLong(entry.unitPrice()), ACCENT));
        rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.detail.dock_label").getString(), entry.sourceDockName().isBlank() ? Component.translatable("screen.sailboatmod.market.value.none").getString() : entry.sourceDockName(), TEXT_MUTED));
        rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.detail.note_label").getString(), entry.sellerNote().isBlank() ? Component.translatable("screen.sailboatmod.market.value.none").getString() : entry.sellerNote(), TEXT_SOFT));
        return rows;
    }

    private List<Integer> selectedCommodityListingIndices() {
        List<Integer> indices = new ArrayList<>();
        String commodityKey = selectedCommodityKey();
        if (commodityKey.isBlank()) {
            return indices;
        }
        for (int i = 0; i < data.listingEntries().size(); i++) {
            MarketOverviewData.ListingEntry entry = data.listingEntries().get(i);
            if (commodityKey.equals(entry.commodityKey())) {
                indices.add(i);
            }
        }
        indices.sort((left, right) -> compareListings(data.listingEntries().get(left), data.listingEntries().get(right)));
        return indices;
    }

    private int estimateCommoditySellerCardsHeight(int count) {
        if (count <= 0) {
            return 36;
        }
        return count * 64 + Math.max(0, count - 1) * 8;
    }

    private void buildCommoditySellerCards(UIComponent parent, int x, int y, int width, List<Integer> indices) {
        if (indices.isEmpty()) {
            buildTextStack(parent, x, y, width, List.of(Component.translatable("screen.sailboatmod.market.empty").getString()), TEXT_MUTED);
            return;
        }
        int rowY = y;
        for (int index : indices) {
            MarketOverviewData.ListingEntry entry = data.listingEntries().get(index);
            boolean selected = index == selectedListingIndex;
            UIRoundedRectangle card = createPanel(parent, x, rowY, width, 64, 12f, selected ? ROW_SELECTED : CARD_BG_SOFT);
            card.enableEffect(new OutlineEffect(new Color(255, 255, 255, selected ? 28 : 12), 1f));
            if (!selected) {
                card.onMouseEnterRunnable(() -> card.setColor(ROW_HOVER));
                card.onMouseLeaveRunnable(() -> card.setColor(CARD_BG_SOFT));
            }
            card.onMouseClickConsumer(event -> runPreservingScroll(() -> {
                selectedListingIndex = index;
                selectedCommodityKey = entry.commodityKey();
                activeGoodsView = GoodsPrimaryView.PRODUCT_PURCHASE;
                rebuildUi();
            }));
            createAccentBar(card, 0, 0, selected ? 6 : 4, 64, selected ? ACCENT : new Color(255, 255, 255, 18));
            createText(card, 14, 10, shortenToWidth(entry.sellerName().isBlank() ? Component.translatable("screen.sailboatmod.market.value.none").getString() : entry.sellerName(), width - 130, 0.82f), 0.82f, TEXT_PRIMARY);
            createText(card, 14, 28,
                    shortenToWidth((entry.sourceDockName().isBlank() ? Component.translatable("screen.sailboatmod.market.value.none").getString() : entry.sourceDockName())
                            + (entry.sellerNote().isBlank() ? "" : " | " + entry.sellerNote()), width - 130, 0.64f),
                    0.64f, TEXT_SOFT);
            createText(card, 14, 44, Component.translatable("screen.sailboatmod.market.goods.quantity_line", entry.availableCount()).getString(), 0.68f, POSITIVE);
            createText(card, Math.max(14, width - 94), 16, formatCompactLong(entry.unitPrice()), 0.9f, ACCENT);
            createText(card, Math.max(14, width - 94), 36, Component.translatable(selected ? "screen.sailboatmod.market.goods.current_selected" : "screen.sailboatmod.market.goods.click_view").getString(), 0.64f, selected ? TEXT_PRIMARY : TEXT_MUTED);
            rowY += 72;
        }
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

    private List<String> buildSellInfoLines() {
        List<String> lines = new ArrayList<>();
        MarketOverviewData.StorageEntry entry = selectedStorage();
        if (entry != null) {
            lines.add(Component.translatable("screen.sailboatmod.market.sell.info.source", entry.detail()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.sell.info.stock", entry.quantity()).getString());
            lines.add(Component.translatable("screen.sailboatmod.market.sell.info.suggested_price", formatCompactLong(entry.suggestedUnitPrice())).getString());
        } else {
            lines.add(Component.translatable("screen.sailboatmod.market.storage_empty").getString());
        }
        return lines;
    }

    private List<String> buildGoodsActionLines() {
        List<String> lines = new ArrayList<>();
        MarketOverviewData.ListingEntry entry = selectedListing();
        if (entry != null && isSelectedListingOwnedByViewer()) {
            lines.add(Component.translatable("screen.sailboatmod.market.self_buy_denied").getString());
            lines.add(Component.translatable("screen.sailboatmod.market.action.cancel_hint").getString());
            return lines;
        }
        if (!data.linkedDock()) {
            lines.add(Component.translatable("screen.sailboatmod.market.linked_dock_missing").getString());
            lines.add(Component.translatable("screen.sailboatmod.market.action.bind_dock_hint").getString());
            return lines;
        }
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

    private List<MetricCard> buildSellStorageOverviewMetrics(int filteredCount) {
        List<MetricCard> metrics = new ArrayList<>();
        int totalUnits = 0;
        for (MarketOverviewData.StorageEntry entry : data.dockStorageEntries()) {
            totalUnits += entry.quantity();
        }
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.sell.metric.visible_goods").getString(), Integer.toString(filteredCount), POSITIVE));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.sell.metric.total_stock").getString(), Integer.toString(totalUnits), ACCENT));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.sell.metric.dock_status").getString(), Component.translatable(data.linkedDock() ? "screen.sailboatmod.market.sell.metric.online" : "screen.sailboatmod.market.sell.metric.unbound").getString(), data.linkedDock() ? ACCENT_DIM : NEGATIVE));
        metrics.add(metric(Component.translatable("screen.sailboatmod.market.sell.metric.permission").getString(), Component.translatable(data.dockStorageAccessible() ? "screen.sailboatmod.market.sell.metric.postable" : "screen.sailboatmod.market.sell.metric.read_only").getString(), data.dockStorageAccessible() ? new Color(95, 162, 198) : NEGATIVE));
        return metrics;
    }

    private List<DetailRow> buildSellStorageOverviewRows(int filteredCount) {
        List<DetailRow> rows = new ArrayList<>();
        int totalUnits = 0;
        for (MarketOverviewData.StorageEntry entry : data.dockStorageEntries()) {
            totalUnits += entry.quantity();
        }
        rows.add(new DetailRow(
                Component.translatable("screen.sailboatmod.market.sell.metric.visible_goods").getString(),
                Integer.toString(filteredCount),
                POSITIVE));
        rows.add(new DetailRow(
                Component.translatable("screen.sailboatmod.market.sell.metric.total_stock").getString(),
                Integer.toString(totalUnits),
                ACCENT));
        return rows;
    }

    private List<Integer> filteredStorageIndices() {
        String query = storageSearchValue == null ? "" : storageSearchValue.trim().toLowerCase(Locale.ROOT);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < data.dockStorageEntries().size(); i++) {
            MarketOverviewData.StorageEntry entry = data.dockStorageEntries().get(i);
            if (!query.isBlank()) {
                String haystack = (entry.itemName() + " " + entry.detail()).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) {
                    continue;
                }
            }
            indices.add(i);
        }
        return indices;
    }

    private String buildSellStorageSummaryText(int filteredCount) {
        if (filteredCount <= 0) {
            return Component.translatable("screen.sailboatmod.market.sell.summary.none").getString();
        }
        return Component.translatable("screen.sailboatmod.market.sell.summary.filtered", filteredCount).getString();
    }

    private List<DetailRow> buildSellDetailRows(MarketOverviewData.StorageEntry entry) {
        List<DetailRow> rows = new ArrayList<>();
        if (entry == null) {
            rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.sell.detail.status").getString(), Component.translatable("screen.sailboatmod.market.sell.wait_select").getString(), TEXT_MUTED));
            rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.sell.detail.dock").getString(), currentDockLine(), data.linkedDock() ? TEXT_MUTED : NEGATIVE));
            return rows;
        }
        rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.sell.detail.goods").getString(), entry.itemName(), TEXT_PRIMARY));
        rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.sell.detail.stock").getString(), "x" + entry.quantity(), POSITIVE));
        rows.add(new DetailRow(Component.translatable("screen.sailboatmod.market.sell.detail.unit_price").getString(), formatCompactLong(entry.suggestedUnitPrice()), ACCENT));
        return rows;
    }

    private String buildSellConfirmSummary() {
        MarketOverviewData.StorageEntry entry = selectedStorage();
        if (entry == null) {
            return Component.translatable("screen.sailboatmod.market.sell.confirm_wait").getString();
        }
        int qty = parsePositive(listingQtyValue, 1);
        int bp = parsePriceAdjustment(listingPriceAdjustValue);
        long adjustedUnitPrice = (long) Math.round(entry.suggestedUnitPrice() * (1 + bp / 100.0));
        long total = adjustedUnitPrice * qty;
        return Component.translatable("screen.sailboatmod.market.sell.confirm_value", qty, formatCompactLong(total)).getString();
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
        lines.add(Component.translatable("screen.sailboatmod.market.price_chart.window_value",
                Component.translatable("screen.sailboatmod.market.price_chart.window").getString(),
                formatChartTime(first.bucketAt(), false),
                formatChartTime(last.bucketAt(), false)).getString());
        lines.add(Component.translatable("screen.sailboatmod.market.price_chart.single_value",
                Component.translatable("screen.sailboatmod.market.price_chart.latest").getString(),
                formatCompactLong(last.averageUnitPrice())).getString());
        lines.add(Component.translatable("screen.sailboatmod.market.price_chart.high_low_value",
                Component.translatable("screen.sailboatmod.market.price_chart.high").getString(),
                Component.translatable("screen.sailboatmod.market.price_chart.low").getString(),
                formatCompactLong(high),
                formatCompactLong(low == Integer.MAX_VALUE ? last.averageUnitPrice() : low)).getString());
        lines.add(Component.translatable("screen.sailboatmod.market.price_chart.volume_trades_value",
                Component.translatable("screen.sailboatmod.market.price_chart.volume").getString(),
                totalVolume,
                Component.translatable("screen.sailboatmod.market.price_chart.trades").getString(),
                totalTrades).getString());
        return lines;
    }

    private List<String> buildGoodsOverviewLines() {
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.market.market_overview_hint").getString());
        lines.add(Component.translatable("screen.sailboatmod.market.hot_title").getString());
        lines.add(Component.translatable("screen.sailboatmod.market.price_chart.window_24h").getString());
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
        lines.add(Component.translatable("screen.sailboatmod.market.buying_best_bid_value",
                Component.translatable("screen.sailboatmod.market.buying_best_bid").getString(),
                formatCompactLong(bestBid)).getString());
        lines.add(Component.translatable("screen.sailboatmod.market.buying_depth_value",
                Component.translatable("screen.sailboatmod.market.buying_depth").getString(),
                depth).getString());
        lines.add(Component.translatable("screen.sailboatmod.market.buy_order.price_range_hint").getString());
        return lines;
    }

    private List<String> buildBuyOrdersOverviewLines() {
        List<String> lines = new ArrayList<>();
        lines.add(Component.translatable("screen.sailboatmod.market.buy_orders_manage_hint").getString());
        if (selectedListing() != null) {
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.selected_goods", displayCommodityName(selectedListing().commodityKey())).getString());
        } else {
            lines.add(Component.translatable("screen.sailboatmod.market.buy_order.selected_goods",
                    Component.translatable("screen.sailboatmod.market.value.none").getString()).getString());
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
                ? Component.translatable("screen.sailboatmod.market.value.none").getString()
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
        if (isSelectedListingOwnedByViewer()) {
            applyNotice(Component.translatable("screen.sailboatmod.market.self_buy_denied").getString(), false);
            rebuildUi();
            return;
        }
        rememberScrollState();
        ModNetwork.CHANNEL.sendToServer(new PurchaseMarketListingPacket(
                data.marketPos(),
                selectedListingIndex,
                parsePositive(buyQtyValue, 1)
        ));
    }

    private void backToGoodsListings() {
        activeGoodsView = GoodsPrimaryView.PRODUCT_LISTINGS;
        rebuildUi();
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
        rememberScrollState();
        ModNetwork.CHANNEL.sendToServer(new MarketGuiActionPacket(data.marketPos(), action));
    }

    private void syncLiveScrollOffsets() {
        if (goodsViewportScrollRef != null && pendingGoodsViewportScrollTicks <= 0) {
            goodsViewportScrollOffset = goodsViewportScrollRef.getVerticalOffset();
            goodsViewportScrollProgress = computeScrollProgress(goodsViewportScrollRef, goodsViewportScrollOffset);
        }
        if (buyOrdersViewportScrollRef != null && pendingBuyOrdersViewportScrollTicks <= 0) {
            buyOrdersViewportScrollOffset = buyOrdersViewportScrollRef.getVerticalOffset();
            buyOrdersViewportScrollProgress = computeScrollProgress(buyOrdersViewportScrollRef, buyOrdersViewportScrollOffset);
        }
        if (goodsCatalogScrollRef != null && pendingGoodsCatalogScrollTicks <= 0) {
            goodsCatalogScrollOffset = goodsCatalogScrollRef.getVerticalOffset();
            goodsCatalogScrollProgress = computeScrollProgress(goodsCatalogScrollRef, goodsCatalogScrollOffset);
        }
        if (buyOrdersListScrollRef != null && pendingBuyOrdersListScrollTicks <= 0) {
            buyOrdersListScrollOffset = buyOrdersListScrollRef.getVerticalOffset();
            buyOrdersListScrollProgress = computeScrollProgress(buyOrdersListScrollRef, buyOrdersListScrollOffset);
        }
    }

    private void rememberScrollState() {
        traceScrollState("rememberScrollState:before");
        if (goodsViewportScrollRef != null) {
            goodsViewportScrollOffset = preservedScrollOffset(goodsViewportScrollRef,
                    goodsViewportScrollOffset, pendingGoodsViewportScrollOffset, pendingGoodsViewportScrollTicks);
            goodsViewportScrollProgress = preservedScrollProgress(goodsViewportScrollRef,
                    goodsViewportScrollProgress, pendingGoodsViewportScrollProgress, pendingGoodsViewportScrollTicks, goodsViewportScrollOffset);
        }
        if (buyOrdersViewportScrollRef != null) {
            buyOrdersViewportScrollOffset = preservedScrollOffset(buyOrdersViewportScrollRef,
                    buyOrdersViewportScrollOffset, pendingBuyOrdersViewportScrollOffset, pendingBuyOrdersViewportScrollTicks);
            buyOrdersViewportScrollProgress = preservedScrollProgress(buyOrdersViewportScrollRef,
                    buyOrdersViewportScrollProgress, pendingBuyOrdersViewportScrollProgress, pendingBuyOrdersViewportScrollTicks, buyOrdersViewportScrollOffset);
        }
        if (goodsCatalogScrollRef != null) {
            goodsCatalogScrollOffset = preservedScrollOffset(goodsCatalogScrollRef,
                    goodsCatalogScrollOffset, pendingGoodsCatalogScrollOffset, pendingGoodsCatalogScrollTicks);
            goodsCatalogScrollProgress = preservedScrollProgress(goodsCatalogScrollRef,
                    goodsCatalogScrollProgress, pendingGoodsCatalogScrollProgress, pendingGoodsCatalogScrollTicks, goodsCatalogScrollOffset);
        }
        if (buyOrdersListScrollRef != null) {
            buyOrdersListScrollOffset = preservedScrollOffset(buyOrdersListScrollRef,
                    buyOrdersListScrollOffset, pendingBuyOrdersListScrollOffset, pendingBuyOrdersListScrollTicks);
            buyOrdersListScrollProgress = preservedScrollProgress(buyOrdersListScrollRef,
                    buyOrdersListScrollProgress, pendingBuyOrdersListScrollProgress, pendingBuyOrdersListScrollTicks, buyOrdersListScrollOffset);
        }
        traceScrollState("rememberScrollState:after");
        clearScrollRefs();
    }

    private float preservedScrollOffset(ScrollComponent scroll, float storedOffset, float pendingOffset, int pendingTicks) {
        if (pendingTicks > 0 && !Float.isNaN(pendingOffset)) {
            return pendingOffset;
        }
        if (scroll == null) {
            return storedOffset;
        }
        return scroll.getVerticalOffset();
    }

    private float preservedScrollProgress(ScrollComponent scroll, float storedProgress, float pendingProgress,
                                          int pendingTicks, float resolvedOffset) {
        if (pendingTicks > 0 && !Float.isNaN(pendingProgress)) {
            return pendingProgress;
        }
        if (scroll == null) {
            return storedProgress;
        }
        return computeScrollProgress(scroll, resolvedOffset);
    }

    private void clearScrollRefs() {
        goodsViewportScrollRef = null;
        buyOrdersViewportScrollRef = null;
        goodsCatalogScrollRef = null;
        buyOrdersListScrollRef = null;
    }

    private void clearPendingScrollRestores() {
        pendingGoodsViewportScrollOffset = Float.NaN;
        pendingBuyOrdersViewportScrollOffset = Float.NaN;
        pendingGoodsCatalogScrollOffset = Float.NaN;
        pendingBuyOrdersListScrollOffset = Float.NaN;
        pendingGoodsViewportScrollProgress = Float.NaN;
        pendingBuyOrdersViewportScrollProgress = Float.NaN;
        pendingGoodsCatalogScrollProgress = Float.NaN;
        pendingBuyOrdersListScrollProgress = Float.NaN;
        pendingGoodsViewportScrollTicks = 0;
        pendingBuyOrdersViewportScrollTicks = 0;
        pendingGoodsCatalogScrollTicks = 0;
        pendingBuyOrdersListScrollTicks = 0;
    }

    private void runPreservingScroll(Runnable action) {
        traceScrollState("runPreservingScroll:before");
        syncLiveScrollOffsets();
        rememberScrollState();
        action.run();
        traceScrollState("runPreservingScroll:after");
    }

    private void restoreScroll(ScrollComponent scroll, float verticalOffset, float progress) {
        if (scroll == null) {
            return;
        }
        float target = resolveScrollRestoreTarget(scroll, verticalOffset, progress);
        traceScrollMessage("restoreScroll " + scrollName(scroll)
                + " storedOffset=" + verticalOffset
                + " storedProgress=" + progress
                + " target=" + target
                + " current=" + scroll.getVerticalOffset()
                + " overhang=" + scroll.getVerticalOverhang());
        scroll.scrollTo(scroll.getHorizontalOffset(), target, false);
        if (scroll == goodsViewportScrollRef) {
            pendingGoodsViewportScrollOffset = verticalOffset;
            pendingGoodsViewportScrollProgress = progress;
            pendingGoodsViewportScrollTicks = 8;
        } else if (scroll == buyOrdersViewportScrollRef) {
            pendingBuyOrdersViewportScrollOffset = verticalOffset;
            pendingBuyOrdersViewportScrollProgress = progress;
            pendingBuyOrdersViewportScrollTicks = 8;
        } else if (scroll == goodsCatalogScrollRef) {
            pendingGoodsCatalogScrollOffset = verticalOffset;
            pendingGoodsCatalogScrollProgress = progress;
            pendingGoodsCatalogScrollTicks = 2;
        } else if (scroll == buyOrdersListScrollRef) {
            pendingBuyOrdersListScrollOffset = verticalOffset;
            pendingBuyOrdersListScrollProgress = progress;
            pendingBuyOrdersListScrollTicks = 8;
        }
    }

    private void applyPendingScrollRestores() {
        if (pendingGoodsViewportScrollTicks > 0 && goodsViewportScrollRef != null && !Float.isNaN(pendingGoodsViewportScrollOffset)) {
            float target = resolveScrollRestoreTarget(goodsViewportScrollRef, pendingGoodsViewportScrollOffset, pendingGoodsViewportScrollProgress);
            goodsViewportScrollRef.scrollTo(goodsViewportScrollRef.getHorizontalOffset(), target, false);
            if (Math.abs(goodsViewportScrollRef.getVerticalOffset() - target) <= 0.5f) {
                pendingGoodsViewportScrollTicks = 0;
                pendingGoodsViewportScrollOffset = Float.NaN;
                pendingGoodsViewportScrollProgress = Float.NaN;
            } else {
                pendingGoodsViewportScrollTicks--;
            }
        }
        if (pendingBuyOrdersViewportScrollTicks > 0 && buyOrdersViewportScrollRef != null && !Float.isNaN(pendingBuyOrdersViewportScrollOffset)) {
            float target = resolveScrollRestoreTarget(buyOrdersViewportScrollRef, pendingBuyOrdersViewportScrollOffset, pendingBuyOrdersViewportScrollProgress);
            buyOrdersViewportScrollRef.scrollTo(buyOrdersViewportScrollRef.getHorizontalOffset(), target, false);
            if (Math.abs(buyOrdersViewportScrollRef.getVerticalOffset() - target) <= 0.5f) {
                pendingBuyOrdersViewportScrollTicks = 0;
                pendingBuyOrdersViewportScrollOffset = Float.NaN;
                pendingBuyOrdersViewportScrollProgress = Float.NaN;
            } else {
                pendingBuyOrdersViewportScrollTicks--;
            }
        }
        if (pendingGoodsCatalogScrollTicks > 0 && goodsCatalogScrollRef != null && !Float.isNaN(pendingGoodsCatalogScrollOffset)) {
            float target = resolveScrollRestoreTarget(goodsCatalogScrollRef, pendingGoodsCatalogScrollOffset, pendingGoodsCatalogScrollProgress);
            goodsCatalogScrollRef.scrollTo(goodsCatalogScrollRef.getHorizontalOffset(), target, false);
            if (Math.abs(goodsCatalogScrollRef.getVerticalOffset() - target) <= 0.5f) {
                pendingGoodsCatalogScrollTicks = 0;
                pendingGoodsCatalogScrollOffset = Float.NaN;
                pendingGoodsCatalogScrollProgress = Float.NaN;
            } else {
                pendingGoodsCatalogScrollTicks--;
            }
        }
        if (pendingBuyOrdersListScrollTicks > 0 && buyOrdersListScrollRef != null && !Float.isNaN(pendingBuyOrdersListScrollOffset)) {
            float target = resolveScrollRestoreTarget(buyOrdersListScrollRef, pendingBuyOrdersListScrollOffset, pendingBuyOrdersListScrollProgress);
            buyOrdersListScrollRef.scrollTo(buyOrdersListScrollRef.getHorizontalOffset(), target, false);
            if (Math.abs(buyOrdersListScrollRef.getVerticalOffset() - target) <= 0.5f) {
                pendingBuyOrdersListScrollTicks = 0;
                pendingBuyOrdersListScrollOffset = Float.NaN;
                pendingBuyOrdersListScrollProgress = Float.NaN;
            } else {
                pendingBuyOrdersListScrollTicks--;
            }
        }
    }

    private float resolveScrollRestoreTarget(ScrollComponent scroll, float fallbackOffset, float progress) {
        if (scroll == null) {
            return fallbackOffset;
        }
        float overhang = Math.max(0f, scroll.getVerticalOverhang());
        if (overhang <= 0f || Float.isNaN(progress)) {
            return fallbackOffset;
        }
        float clampedProgress = Math.max(0f, Math.min(1f, progress));
        return -clampedProgress * overhang;
    }

    private float computeScrollProgress(ScrollComponent scroll, float offset) {
        if (scroll == null) {
            return Float.NaN;
        }
        float overhang = Math.max(0f, scroll.getVerticalOverhang());
        if (overhang <= 0f) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, -offset / overhang));
    }

    private float scrollPercentageToOffset(ScrollComponent scroll, float progress) {
        if (scroll == null) {
            return 0f;
        }
        float overhang = Math.max(0f, scroll.getVerticalOverhang());
        if (overhang <= 0f) {
            return 0f;
        }
        float clampedProgress = Math.max(0f, Math.min(1f, progress));
        return -clampedProgress * overhang;
    }

    public void showNotice(String message, boolean positive) {
        applyNotice(message, positive);
        rebuildUi();
    }

    private void applyNotice(String message, boolean positive) {
        noticeMessage = message == null ? "" : message;
        noticePositive = positive;
        noticeExpiresAtMs = System.currentTimeMillis() + 2800L;
    }

    private void closeMarketScreen() {
        onScreenClose();
        restorePreviousScreen();
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

    private ScrollComponent createPanelBodyScroll(UIComponent panel, int y, int bottomReservedHeight) {
        int bodyHeight = Math.max(100, Math.round(panel.getHeight()) - y - bottomReservedHeight - 14);
        LOGGER.info("[MarketScroll] createPanelBodyScroll panelH={} y={} reserved={} -> bodyHeight={}", Math.round(panel.getHeight()), y, bottomReservedHeight, bodyHeight);
        ScrollComponent scroll = new ScrollComponent();
        scroll.setX(new PixelConstraint(14));
        scroll.setY(new PixelConstraint(y));
        scroll.setWidth(new PixelConstraint(Math.max(80, Math.round(panel.getWidth()) - 28)));
        scroll.setHeight(new PixelConstraint(bodyHeight));
        scroll.setColor(new Color(0, 0, 0, 0));
        scroll.setChildOf(panel);
        return scroll;
    }

    private void trackViewportScroll(ScrollComponent scroll, boolean goodsPage) {
        if (scroll == null) {
            return;
        }
        float current = scroll.getVerticalOffset();
        float progress = computeScrollProgress(scroll, current);
        traceScrollMessage("trackViewportScroll attach=" + (goodsPage ? "goodsViewport" : "buyOrdersViewport")
                + " current=" + current
                + " progress=" + progress
                + " overhang=" + scroll.getVerticalOverhang());
        if (goodsPage) {
            goodsViewportScrollOffset = current;
            goodsViewportScrollProgress = progress;
        } else {
            buyOrdersViewportScrollOffset = current;
            buyOrdersViewportScrollProgress = progress;
        }
        scroll.addScrollAdjustEvent(false, (scrollPercentage, percentageOfParent) -> {
            float updatedProgress = scrollPercentage == null ? 0f : scrollPercentage;
            float value = scrollPercentageToOffset(scroll, updatedProgress);
            traceScrollMessage("trackViewportScroll adjust=" + (goodsPage ? "goodsViewport" : "buyOrdersViewport")
                    + " progress=" + updatedProgress
                    + " value=" + value
                    + " percentageOfParent=" + percentageOfParent
                    + " live=" + scroll.getVerticalOffset()
                    + " overhang=" + scroll.getVerticalOverhang());
            if (goodsPage) {
                if (pendingGoodsViewportScrollTicks > 0) {
                    return Unit.INSTANCE;
                }
                goodsViewportScrollOffset = value;
                goodsViewportScrollProgress = updatedProgress;
            } else {
                if (pendingBuyOrdersViewportScrollTicks > 0) {
                    return Unit.INSTANCE;
                }
                buyOrdersViewportScrollOffset = value;
                buyOrdersViewportScrollProgress = updatedProgress;
            }
            return Unit.INSTANCE;
        });
    }

    private void trackNestedScroll(ScrollComponent scroll, boolean goodsCatalog) {
        if (scroll == null) {
            return;
        }
        float current = scroll.getVerticalOffset();
        float progress = computeScrollProgress(scroll, current);
        traceScrollMessage("trackNestedScroll attach=" + (goodsCatalog ? "goodsCatalog" : "buyOrdersList")
                + " current=" + current
                + " progress=" + progress
                + " overhang=" + scroll.getVerticalOverhang());
        if (goodsCatalog) {
            goodsCatalogScrollOffset = current;
            goodsCatalogScrollProgress = progress;
        } else {
            buyOrdersListScrollOffset = current;
            buyOrdersListScrollProgress = progress;
        }
        scroll.addScrollAdjustEvent(false, (scrollPercentage, percentageOfParent) -> {
            float updatedProgress = scrollPercentage == null ? 0f : scrollPercentage;
            float value = scrollPercentageToOffset(scroll, updatedProgress);
            traceScrollMessage("trackNestedScroll adjust=" + (goodsCatalog ? "goodsCatalog" : "buyOrdersList")
                    + " progress=" + updatedProgress
                    + " value=" + value
                    + " percentageOfParent=" + percentageOfParent
                    + " live=" + scroll.getVerticalOffset()
                    + " overhang=" + scroll.getVerticalOverhang());
            if (goodsCatalog) {
                goodsCatalogScrollOffset = value;
                goodsCatalogScrollProgress = updatedProgress;
            } else {
                buyOrdersListScrollOffset = value;
                buyOrdersListScrollProgress = updatedProgress;
            }
            return Unit.INSTANCE;
        });
    }

    private void traceScrollState(String stage) {
        if (!SCROLL_TRACE_ENABLED) {
            return;
        }
        int seq = ++scrollTraceSequence;
        LOGGER.info("[MarketScroll:{}] {} page={} goodsTab={} goodsViewport={} buyViewport={} goodsCatalog={} buyList={} pending={}",
                seq, stage, activePage, activeGoodsPanelTab,
                describeScroll("goodsViewport", goodsViewportScrollRef, goodsViewportScrollOffset, goodsViewportScrollProgress),
                describeScroll("buyViewport", buyOrdersViewportScrollRef, buyOrdersViewportScrollOffset, buyOrdersViewportScrollProgress),
                describeScroll("goodsCatalog", goodsCatalogScrollRef, goodsCatalogScrollOffset, goodsCatalogScrollProgress),
                describeScroll("buyList", buyOrdersListScrollRef, buyOrdersListScrollOffset, buyOrdersListScrollProgress),
                pendingScrollSummary());
    }

    private void traceScrollMessage(String message) {
        if (!SCROLL_TRACE_ENABLED) {
            return;
        }
        LOGGER.info("[MarketScroll:{}] {}", ++scrollTraceSequence, message);
    }

    private String describeScroll(String name, ScrollComponent scroll, float storedOffset, float storedProgress) {
        if (scroll == null) {
            return name + "{ref=null,storedOffset=" + storedOffset + ",storedProgress=" + storedProgress + "}";
        }
        return name + "{ref=" + Integer.toHexString(System.identityHashCode(scroll))
                + ",liveOffset=" + scroll.getVerticalOffset()
                + ",storedOffset=" + storedOffset
                + ",storedProgress=" + storedProgress
                + ",overhang=" + scroll.getVerticalOverhang()
                + "}";
    }

    private String pendingScrollSummary() {
        return "goodsViewport{ticks=" + pendingGoodsViewportScrollTicks + ",offset=" + pendingGoodsViewportScrollOffset + ",progress=" + pendingGoodsViewportScrollProgress
                + "} buyViewport{ticks=" + pendingBuyOrdersViewportScrollTicks + ",offset=" + pendingBuyOrdersViewportScrollOffset + ",progress=" + pendingBuyOrdersViewportScrollProgress
                + "} goodsCatalog{ticks=" + pendingGoodsCatalogScrollTicks + ",offset=" + pendingGoodsCatalogScrollOffset + ",progress=" + pendingGoodsCatalogScrollProgress
                + "} buyList{ticks=" + pendingBuyOrdersListScrollTicks + ",offset=" + pendingBuyOrdersListScrollOffset + ",progress=" + pendingBuyOrdersListScrollProgress + "}";
    }

    private String scrollName(ScrollComponent scroll) {
        if (scroll == goodsViewportScrollRef) {
            return "goodsViewport";
        }
        if (scroll == buyOrdersViewportScrollRef) {
            return "buyOrdersViewport";
        }
        if (scroll == goodsCatalogScrollRef) {
            return "goodsCatalog";
        }
        if (scroll == buyOrdersListScrollRef) {
            return "buyOrdersList";
        }
        return "unknown@" + Integer.toHexString(System.identityHashCode(scroll));
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
        int innerWidth = Math.max(40, width - 28);
        createText(section, 14, 10, shortenToWidth(title, innerWidth, 0.88f), 0.88f, TEXT_SOFT);
        createWrappedText(section, 14, 24, innerWidth,
                shortenToWidth(subtitle, innerWidth * 2, 0.74f), 0.74f, TEXT_PRIMARY);
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

    private UIWrappedText createWrappedText(UIComponent parent, int x, int y, int width, String text, float scale, Color color) {
        UIWrappedText label = new UIWrappedText(text == null ? "" : text);
        label.setX(new PixelConstraint(x));
        label.setY(new PixelConstraint(y));
        label.setWidth(new PixelConstraint(width));
        label.setHeight(new ChildBasedSizeConstraint(0f));
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

    private void createItemIcon(UIComponent parent, int x, int y, int width, int height, ItemStack stack, boolean decorations) {
        ItemIconComponent icon = new ItemIconComponent(stack, decorations);
        icon.setX(new PixelConstraint(x));
        icon.setY(new PixelConstraint(y));
        icon.setWidth(new PixelConstraint(width));
        icon.setHeight(new PixelConstraint(height));
        icon.setChildOf(parent);
    }

    private void buildDetailInfoRows(UIComponent parent, int x, int y, int width, List<DetailRow> rows) {
        int rowY = y;
        for (DetailRow row : rows) {
            UIRoundedRectangle line = createPanel(parent, x, rowY, width, 20, 10f, new Color(25, 34, 52, 180));
            line.enableEffect(new OutlineEffect(new Color(255, 255, 255, 8), 1f));
            createText(line, 10, 6, shortenToWidth(row.label(), Math.max(40, width / 3), 0.68f), 0.68f, TEXT_SOFT);
            createText(line, Math.max(86, width / 3), 6,
                    shortenToWidth(row.value(), width - Math.max(96, width / 3) - 10, 0.7f),
                    0.7f, row.accent());
            rowY += 24;
        }
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
        frame.onMouseClickConsumer(event -> {
            input.grabWindowFocus();
            input.setActive(true);
        });
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
            button.onMouseClickConsumer(event -> runPreservingScroll(action));
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
            createText(button, 18, 25, Component.translatable(page.kickerKey).getString(), 0.84f, ACCENT);
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
        button.onMouseClickConsumer(event -> runPreservingScroll(() -> {
            activePage = page;
            rebuildUi();
        }));
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
        button.onMouseClickConsumer(event -> runPreservingScroll(() -> {
            activePage = page;
            rebuildUi();
        }));
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
        int innerWidth = Math.max(32, width - 28);
        createText(tile, 14, 9, shortenToWidth(label, innerWidth, 0.76f), 0.76f, TEXT_SOFT);
        createText(tile, 14, 23, shortenToWidth(value, innerWidth, 0.92f), 0.92f, TEXT_PRIMARY);
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
            text.setY(new CramSiblingConstraint(6f));
            text.setWidth(new PixelConstraint(width));
            text.setHeight(new ChildBasedSizeConstraint(0f));
            text.setTextScale(new PixelConstraint(0.6f));
            text.setColor(color);
            text.setChildOf(holder);
        }
    }

    private int buyOrdersOverviewSectionHeight(int width) {
        int innerWidth = Math.max(120, width - 28);
        return Math.max(120, 90 + estimateTextStackHeight(innerWidth, buildBuyOrdersOverviewLines()) + 16);
    }

    private int compactBuyOrdersListHeight(int viewportHeight) {
        return Math.max(168, Math.min(228, viewportHeight - 180));
    }

    private int buyOrdersActionSectionHeight(int width) {
        int innerWidth = Math.max(120, width - 28);
        int detailHeight = estimateTextStackHeight(innerWidth, buildBuyOrderDetailLines());
        int helperHeight = estimateTextStackHeight(innerWidth, buildBuyOrderActionLines());
        int contentBottom = 90 + detailHeight + 14
                + 44
                + 44
                + 20
                + 28
                + 22
                + 30
                + helperHeight;
        return Math.max(360, contentBottom + 18);
    }

    private int estimateTextStackHeight(int width, List<String> lines) {
        int total = 0;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            total += estimateWrappedLineCount(line, width) * 10 + 6;
        }
        return Math.max(0, total);
    }

    private int estimateWrappedLineCount(String text, int width) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int effectiveWidth = Math.max(48, width);
        int charsPerLine = Math.max(10, effectiveWidth / 4);
        return Math.max(1, (text.length() + charsPerLine - 1) / charsPerLine);
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
                return Component.translatable("screen.sailboatmod.market.price_chart.window_value",
                        Component.translatable("screen.sailboatmod.market.price_chart.window").getString(),
                        formatChartTime(series.points().get(0).bucketAt(), true),
                        formatChartTime(series.points().get(series.points().size() - 1).bucketAt(), true)).getString();
            }
            return Component.translatable("screen.sailboatmod.market.price_chart").getString();
        }
        if (activeGoodsPanelTab == GoodsPanelTab.BUYING) {
            return bookSubtitle(buyBook);
        }
        return actionCaption();
    }

    private String buildSelectedListingHeroSubtitle() {
        MarketOverviewData.ListingEntry entry = selectedListing();
        if (entry == null) {
            return currentDockLine();
        }
        return Component.translatable("screen.sailboatmod.market.goods.hero_subtitle",
                rarityLabel(entry.rarity()),
                entry.category().isBlank() ? displayCommodityName(entry.commodityKey()) : entry.category(),
                Component.translatable("screen.sailboatmod.market.catalog.column.seller").getString(),
                entry.sellerName().isBlank() ? Component.translatable("screen.sailboatmod.market.value.none").getString() : entry.sellerName()).getString();
    }

    private String selectedGoodsHeadline() {
        MarketOverviewData.ListingEntry entry = activeGoodsView == GoodsPrimaryView.PRODUCT_PURCHASE
                ? selectedListing()
                : selectedCommodityRepresentativeListing();
        return entry == null
                ? Component.translatable("screen.sailboatmod.market.page.goods").getString()
                : entry.itemName();
    }

    private String selectedGoodsSubtitle() {
        if (activeGoodsView == GoodsPrimaryView.PRODUCT_PURCHASE) {
            return selectedListing() == null
                    ? Component.translatable("screen.sailboatmod.market.market_overview").getString()
                    : buildSelectedListingHeroSubtitle();
        }
        MarketOverviewData.ListingEntry entry = selectedCommodityRepresentativeListing();
        if (entry == null) {
            return Component.translatable("screen.sailboatmod.market.market_overview").getString();
        }
        return Component.translatable("screen.sailboatmod.market.goods.seller_count_summary",
                rarityLabel(entry.rarity()),
                entry.category().isBlank() ? displayCommodityName(entry.commodityKey()) : entry.category(),
                selectedCommodityListingIndices().size()).getString();
    }

    private String selectedCommodityKey() {
        if (selectedCommodityKey != null && !selectedCommodityKey.isBlank()) {
            return selectedCommodityKey;
        }
        MarketOverviewData.ListingEntry listing = selectedListing();
        return listing == null ? "" : listing.commodityKey();
    }

    private MarketOverviewData.ListingEntry selectedCommodityRepresentativeListing() {
        List<Integer> indices = selectedCommodityListingIndices();
        if (!indices.isEmpty()) {
            return data.listingEntries().get(indices.get(0));
        }
        MarketOverviewData.ListingEntry listing = selectedListing();
        if (listing != null) {
            return listing;
        }
        return data.listingEntries().isEmpty() ? null : data.listingEntries().get(0);
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
        return Component.translatable("screen.sailboatmod.market.buying_depth_value",
                Component.translatable("screen.sailboatmod.market.buying_depth").getString(),
                book.entries().size()).getString();
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

    private String shortenToWidth(String value, int maxWidth, float scale) {
        if (value == null || value.isBlank()) {
            return value == null ? "" : value;
        }
        if (maxWidth <= 0 || minecraft == null || minecraft.font == null) {
            return shorten(value, Math.max(4, maxWidth / 8));
        }
        if (minecraft.font.width(value) * scale <= maxWidth) {
            return value;
        }
        for (int end = value.length() - 1; end > 0; end--) {
            String candidate = value.substring(0, end).trim() + "...";
            if (minecraft.font.width(candidate) * scale <= maxWidth) {
                return candidate;
            }
        }
        return "...";
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

    private boolean isSelectedListingOwnedByViewer() {
        MarketOverviewData.ListingEntry listing = selectedListing();
        if (listing == null || data.viewerUuid() == null || data.viewerUuid().isBlank()) {
            return false;
        }
        return data.viewerUuid().equals(listing.sellerUuid());
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

    private ItemStack resolveCommodityStack(String commodityKey) {
        ResourceLocation key = ResourceLocation.tryParse(commodityKey == null ? "" : commodityKey.trim());
        if (key != null) {
            Item item = ForgeRegistries.ITEMS.getValue(key);
            if (item != null) {
                return new ItemStack(item);
            }
        }
        return ItemStack.EMPTY;
    }

    private ItemStack resolveStorageStack(MarketOverviewData.StorageEntry entry) {
        return entry == null ? ItemStack.EMPTY : resolveCommodityStack(entry.commodityKey());
    }

    private String rarityLabel(int rarity) {
        return Component.translatable("screen.sailboatmod.market.rarity." + switch (clamp(rarity, 0, 4)) {
            case 4 -> "legend";
            case 3 -> "epic";
            case 2 -> "rare";
            case 1 -> "uncommon";
            default -> "common";
        }).getString();
    }

    private Color rarityColor(int rarity) {
        return switch (clamp(rarity, 0, 4)) {
            case 4 -> new Color(205, 92, 92, 235);
            case 3 -> new Color(134, 92, 190, 235);
            case 2 -> new Color(72, 128, 206, 235);
            case 1 -> new Color(86, 154, 96, 235);
            default -> new Color(164, 126, 72, 235);
        };
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
        return new MarketOverviewData(pos, "Market", "-", "", "", 0, false, "-", "-", false, false,
                "", "", 0, 0, 0, 0, 0, 0L, 0L, 0L, 0.0F,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private enum MarketPage {
        GOODS("screen.sailboatmod.market.page.goods", "screen.sailboatmod.market.page.kicker.goods"),
        SELL("screen.sailboatmod.market.page.sell", "screen.sailboatmod.market.page.kicker.sell"),
        DISPATCH("screen.sailboatmod.market.page.dispatch", "screen.sailboatmod.market.page.kicker.dispatch"),
        FINANCE("screen.sailboatmod.market.page.finance", "screen.sailboatmod.market.page.kicker.finance"),
        BUY_ORDERS("screen.sailboatmod.market.page.buy_orders", "screen.sailboatmod.market.page.kicker.buy_orders");

        private final String key;
        private final String kickerKey;

        MarketPage(String key, String kickerKey) {
            this.key = key;
            this.kickerKey = kickerKey;
        }
    }

    private enum GoodsPrimaryView {
        BROWSE_MARKET,
        PRODUCT_LISTINGS,
        PRODUCT_PURCHASE
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

    private record GoodsCatalogGridLayout(int columns, int gap, int cardWidth, int cardHeight,
                                          int gridWidth, int startX, int contentHeight) {
    }

    private record RowSpec(String title, String subtitle, String trailing, boolean selected, Runnable action) {
    }

    private record DetailRow(String label, String value, Color accent) {
    }

    private record HotCommodity(String commodityKey, String itemName, int listingIndex, int heatScore, String summary) {
    }

    private static final class ItemIconComponent extends UIBlock {
        private final ItemStack stack;
        private final boolean decorations;

        private ItemIconComponent(ItemStack stack, boolean decorations) {
            super(new Color(0, 0, 0, 0));
            this.stack = stack == null ? ItemStack.EMPTY : stack.copy();
            this.decorations = decorations;
        }

        @Override
        public void draw(UMatrixStack matrixStack) {
            super.draw(matrixStack);
            if (stack.isEmpty()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            int drawX = Math.round(getLeft() + Math.max(0f, (getWidth() - 16f) / 2f));
            int drawY = Math.round(getTop() + Math.max(0f, (getHeight() - 16f) / 2f));
            GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
            graphics.renderItem(stack, drawX, drawY);
            if (decorations) {
                graphics.renderItemDecorations(minecraft.font, stack, drawX, drawY);
            }
            graphics.flush();
        }
    }
}
