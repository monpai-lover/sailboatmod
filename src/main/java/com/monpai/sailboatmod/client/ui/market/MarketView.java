package com.monpai.sailboatmod.client.ui.market;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Selector;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.monpai.sailboatmod.client.ui.kit.ActionButton;
import com.monpai.sailboatmod.client.ui.kit.PriceChart;
import com.monpai.sailboatmod.client.ui.kit.Ui;
import com.monpai.sailboatmod.client.ui.kit.Scrollbars;
import com.monpai.sailboatmod.client.ui.theme.UiTheme;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.market.analytics.CommodityCandlePoint;
import com.monpai.sailboatmod.market.analytics.CommodityCandleSeries;
import com.monpai.sailboatmod.market.analytics.MarketAnalyticsPoint;
import com.monpai.sailboatmod.market.analytics.MarketAnalyticsSeries;
import com.monpai.sailboatmod.network.packet.MarketWalletActionPacket;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.TaffyPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The market terminal built with LDLib2.
 *
 * <p>The element tree is created once. {@link #refresh()} updates labels and button states and rebuilds only list
 * contents, so typed text, focus and scroll positions survive server updates. Every button calls the
 * {@link MarketController}; nothing here sends packets or decides permissions on its own.</p>
 *
 * <p>Layout: header (market, wallet, theme, refresh, close) above a page rail and the page body. Goods has a
 * catalog of item cards beside a detail pane with the buy box; Sell, Dispatch and Buy Orders follow the same
 * "list on the left, action pane on the right" pattern; Finance and My Orders are single columns.</p>
 */
public final class MarketView {
    /** Screen-level callbacks the view needs from its host. */
    public interface Host {
        void close();

        void toggleTheme();

        UiTheme theme();

        long tick();
    }

    private static final float PANE_WIDTH = 138;
    private static final float CARD_W = 58;
    private static final float CARD_H = 74;

    private final MarketController controller;
    private final MarketViewModel model;
    private final Host host;

    private final UIElement root;
    private final UIElement window;
    private final Map<MarketViewModel.Page, UIElement> pages = new EnumMap<>(MarketViewModel.Page.class);
    private final Map<MarketViewModel.Page, Button> navTabs = new EnumMap<>(MarketViewModel.Page.class);
    private final List<Selector<?>> selectors = new ArrayList<>();

    // header
    private Label marketName;
    private Label dockStatus;
    private Label walletBalance;

    // goods
    private UIElement catalogArea;
    private UIElement productArea;
    private Label resultCount;
    private ScrollerView catalogScroller;
    private UIElement catalogGrid;
    private TextField catalogSearchField;
    private Selector<Integer> catalogRarityFilter;
    private Selector<MarketViewModel.PriceBand> catalogPriceFilter;
    private Label productName;
    private UIElement productSlotHolder;
    private final Map<MarketViewModel.ProductTab, Button> productTabs = new EnumMap<>(MarketViewModel.ProductTab.class);
    private ScrollerView productList;
    private PriceChart productChart;
    private UIElement detailPane;
    private UIElement detailEmpty;
    private UIElement detailBody;
    private ScrollerView detailScroller;
    private UIElement detailSlotHolder;
    private String detailSlotKey = "";
    private Label detailName;
    private Label detailMeta;
    private Label detailSeller;
    private Label detailPrice;
    private Label detailBid;
    private Label detailStock;
    private UIElement detailQtyRow;
    private ActionButton buyAction;
    private ActionButton unlistAction;
    private Button sellersButton;

    // sell
    private ScrollerView storageList;
    private UIElement sellPane;
    private UIElement sellEmpty;
    private UIElement sellBody;
    private ScrollerView sellScroller;
    private UIElement sellSlotHolder;
    private Label sellName;
    private Label sellStock;
    private TextField listingQtyField;
    private TextField listingPriceField;
    private Label sellValidation;
    private ActionButton listAction;
    private String sellShownKey = "";

    // dispatch
    private ScrollerView orderList;
    private ScrollerView carrierList;
    private ActionButton dispatchAction;
    private ActionButton cancelOrderAction;

    // finance
    private UIElement financeFigures;
    private UIElement financeEconomy;
    private final Map<ActionButton, MarketWalletActionPacket.Action> walletActions = new java.util.LinkedHashMap<>();
    private ActionButton claimAction;
    private ActionButton renameAction;
    private UIElement renameRow;

    // buy orders
    private ScrollerView buyOrderList;
    private ActionButton createBuyOrderAction;
    private ActionButton cancelBuyOrderAction;
    private TextField buyOrderCommodityField;

    // my orders
    private ScrollerView myOrderList;
    private Label myOrdersHint;

    // index
    private ScrollerView seriesList;
    private PriceChart indexChart;
    private Label indexTitle;
    private String selectedSeriesId = "";

    // overlays
    private UIElement buyOverlay;
    private UIElement buyDialogBody;
    private UIElement toast;
    private Label toastText;
    private long toastUntilTick;

    public MarketView(MarketController controller, Host host) {
        this.controller = controller;
        this.model = controller.model();
        this.host = host;

        root = new UIElement().addClasses("sm-root", "sm-controls").setId("sm-market-root");
        root.layout(l -> l.widthPercent(100).heightPercent(100).paddingAll(Ui.GAP_L)
                .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));

        window = Ui.column("sm-window");
        window.layout(l -> l.widthPercent(100).heightPercent(100).maxWidth(640).maxHeight(400));
        root.addChild(window);

        window.addChild(buildHeader());
        UIElement body = Ui.row();
        body.layout(l -> l.height(0).flexGrow(1).flexShrink(1).minWidth(0).minHeight(0).widthPercent(100).alignItems(AlignItems.STRETCH));
        body.addChild(buildNavRail());
        UIElement content = Ui.column();
        content.layout(l -> l.width(0).flexGrow(1).minWidth(0).minHeight(0).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_M));
        pages.put(MarketViewModel.Page.GOODS, buildGoodsPage());
        pages.put(MarketViewModel.Page.MARKET_INDEX, buildIndexPage());
        pages.put(MarketViewModel.Page.SELL, buildSellPage());
        pages.put(MarketViewModel.Page.DISPATCH, buildDispatchPage());
        pages.put(MarketViewModel.Page.FINANCE, buildFinancePage());
        pages.put(MarketViewModel.Page.BUY_ORDERS, buildBuyOrdersPage());
        pages.put(MarketViewModel.Page.MY_ORDERS, buildMyOrdersPage());
        pages.values().forEach(page -> {
            page.layout(l -> l.height(0).flexGrow(1).flexShrink(1).minHeight(0));
            content.addChild(page);
        });
        body.addChild(content);
        window.addChild(body);

        window.addChild(buildToast());
        window.addChild(buildBuyOverlay());
        refresh();
    }

    public UIElement root() {
        return root;
    }

    // ================================================================= header & navigation

    private UIElement buildHeader() {
        UIElement header = Ui.row("sm-header");
        header.layout(l -> l.height(24).paddingHorizontal(Ui.GAP_M).gapAll(Ui.GAP_S).flexShrink(0));
        header.addChild(Ui.slot(new ItemStack(Items.CHEST), Ui.SLOT));
        marketName = Ui.text("", "sm-title");
        dockStatus = Ui.text("", "sm-muted");
        UIElement names = Ui.column();
        names.layout(l -> l.width(0).flexGrow(1).flexShrink(1).minWidth(0));
        marketName.textStyle(s -> s.adaptiveWidth(false));
        dockStatus.textStyle(s -> s.adaptiveWidth(false));
        marketName.setOverflowVisible(false);
        dockStatus.setOverflowVisible(false);
        marketName.layout(l -> l.widthPercent(100));
        dockStatus.layout(l -> l.widthPercent(100));
        names.addChildren(marketName, dockStatus);
        header.addChild(names);

        UIElement wallet = Ui.row("sm-chip");
        wallet.layout(l -> l.width(74).height(Ui.CONTROL_HEIGHT).flexShrink(0).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_XS));
        walletBalance = Ui.text("", "sm-price");
        walletBalance.textStyle(s -> s.adaptiveWidth(false));
        walletBalance.setOverflowVisible(false);
        walletBalance.layout(l -> l.width(0).flexGrow(1).minWidth(0));
        wallet.addChildren(Ui.icon(new ItemStack(Items.EMERALD), 12), walletBalance);
        wallet.style(s -> s.tooltips(MarketText.market("finance.metric.balance")));
        header.addChild(wallet);

        header.addChild(Ui.iconButton(new ItemStack(Items.MAP), MarketText.ui("theme.toggle"), host::toggleTheme));
        header.addChild(Ui.iconButton(new ItemStack(Items.COMPASS), MarketText.market("refresh"), controller::refresh));
        header.addChild(Ui.iconButton(new ItemStack(Items.BARRIER), MarketText.ui("close"), host::close));
        return header;
    }

    private UIElement buildNavRail() {
        UIElement rail = Ui.column("sm-divider-right");
        rail.layout(l -> l.width(50).paddingAll(Ui.GAP_XS).gapAll(Ui.GAP_XS).flexShrink(0));
        addNavTab(rail, MarketViewModel.Page.GOODS, Items.CHEST, "page.goods");
        addNavTab(rail, MarketViewModel.Page.MARKET_INDEX, Items.CLOCK, "page.market_index");
        addNavTab(rail, MarketViewModel.Page.SELL, Items.GOLD_INGOT, "page.sell");
        addNavTab(rail, MarketViewModel.Page.DISPATCH, Items.OAK_BOAT, "page.dispatch");
        addNavTab(rail, MarketViewModel.Page.FINANCE, Items.EMERALD, "page.finance");
        addNavTab(rail, MarketViewModel.Page.BUY_ORDERS, Items.WRITABLE_BOOK, "page.buy_orders");
        addNavTab(rail, MarketViewModel.Page.MY_ORDERS, Items.PAPER, "page.my_orders");
        return rail;
    }

    private void addNavTab(UIElement rail, MarketViewModel.Page page, net.minecraft.world.item.Item icon, String labelKey) {
        Button tab = new Button();
        tab.addClass("sm-nav-tab");
        tab.addPreIcon(new com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture(new ItemStack(icon)));
        tab.setText(MarketText.market(labelKey));
        tab.textStyle(t -> t.adaptiveWidth(false).textWrap(com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.HOVER_ROLL));
        tab.text.layout(l -> l.width(0).flexGrow(1).flexShrink(1).minWidth(0));
        tab.text.setOverflowVisible(false);
        tab.layout(l -> l.widthPercent(100).height(18).paddingHorizontal(Ui.GAP_XS));
        tab.style(s -> s.tooltips(MarketText.market(labelKey)));
        tab.setOnClick(e -> {
            model.openPage(page);
            refresh();
        });
        navTabs.put(page, tab);
        rail.addChild(tab);
    }

    // ================================================================= goods

    private UIElement buildGoodsPage() {
        UIElement page = Ui.row();
        page.layout(l -> l.widthPercent(100).minWidth(0).flexGrow(1).minHeight(0).gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH));

        UIElement left = Ui.column();
        left.layout(l -> l.width(0).flexGrow(1).minWidth(0).minHeight(0).gapAll(Ui.GAP_S));

        // catalog
        catalogArea = Ui.column();
        catalogArea.layout(l -> l.height(0).widthPercent(100).minWidth(0).flexGrow(1).flexShrink(1).minHeight(0).gapAll(Ui.GAP_S));
        UIElement toolbar = Ui.wrapRow(Ui.GAP_S);
        toolbar.layout(l -> l.gapAll(Ui.GAP_S).flexShrink(0));
        TextField search = Ui.field(model.search(), MarketText.market("catalog.search"), value -> {
            model.setSearch(value);
            refreshCatalog();
        });
        catalogSearchField = search;
        search.layout(l -> l.widthPercent(100).minWidth(40));
        toolbar.addChildren(search,
                selector(List.of(MarketViewModel.Sort.values()), model.sort(), this::sortLabel, value -> {
                    model.setSort(value);
                    refreshCatalog();
                }, 52),
                catalogRarityFilter = selector(raritiesWithAll(), model.rarity(), this::rarityFilterLabel, value -> {
                    model.setRarity(value);
                    refreshCatalog();
                }, 56),
                catalogPriceFilter = selector(List.of(MarketViewModel.PriceBand.values()), model.priceBand(), this::priceBandLabel, value -> {
                    model.setPriceBand(value);
                    refreshCatalog();
                }, 56));
        resultCount = Ui.alignRight(Ui.text("", "sm-muted"));
        resultCount.layout(l -> l.flexShrink(0).widthPercent(100));
        catalogScroller = Ui.scroller();
        catalogGrid = Ui.wrapRow(Ui.GAP_S);
        catalogGrid.layout(l -> l.widthPercent(100).minWidth(0));
        catalogScroller.addScrollViewChild(catalogGrid);
        catalogArea.addChildren(toolbar, resultCount, catalogScroller);

        // product (sellers of one commodity)
        productArea = Ui.column();
        productArea.layout(l -> l.height(0).flexGrow(1).flexShrink(1).minHeight(0).gapAll(Ui.GAP_S));
        UIElement productHead = Ui.row();
        productHead.layout(l -> l.gapAll(Ui.GAP_S).flexShrink(0));
        productSlotHolder = Ui.row();
        productName = Ui.text("", "sm-title");
        productHead.addChildren(
                Ui.button(MarketText.market("goods.back_market"), Ui.Tone.SECONDARY, () -> {
                    model.backToCatalog();
                    refresh();
                }),
                productSlotHolder, productName);
        UIElement productTabsRow = Ui.row();
        productTabsRow.layout(l -> l.gapAll(Ui.GAP_XS).flexShrink(0));
        addProductTab(productTabsRow, MarketViewModel.ProductTab.SELLING, "goods_subtab.selling");
        addProductTab(productTabsRow, MarketViewModel.ProductTab.BUYING, "goods_subtab.buying");
        addProductTab(productTabsRow, MarketViewModel.ProductTab.PRICE_CHART, "goods_subtab.history");
        productList = Ui.scroller();
        productChart = new PriceChart(true, 32);
        productChart.layout(l -> l.flexGrow(1).minHeight(40));
        productArea.addChildren(productHead, productTabsRow, productList, productChart);

        left.addChildren(catalogArea, productArea);
        detailPane = Ui.column("sm-pane");
        detailPane.layout(l -> l.width(PANE_WIDTH).flexShrink(0).minHeight(0).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        buildDetailPane();
        page.addChildren(left, detailPane);
        return page;
    }

    /** Built once; {@link #refreshDetailPane()} only changes values, so the quantity field keeps focus and text. */
    private void buildDetailPane() {
        detailEmpty = emptyState(MarketText.ui("pick_item"), null);
        detailBody = Ui.column();
        detailBody.layout(l -> l.widthPercent(100).minWidth(0).gapAll(Ui.GAP_S));

        UIElement head = Ui.row();
        head.layout(l -> l.gapAll(Ui.GAP_S));
        detailSlotHolder = Ui.row();
        UIElement names = Ui.column();
        names.layout(l -> l.flexGrow(1).minWidth(0));
        detailName = Ui.text("", "sm-title");
        detailMeta = Ui.text("", "sm-muted");
        detailSeller = Ui.text("", "sm-muted");
        names.addChildren(detailName, detailMeta, detailSeller);
        head.addChildren(detailSlotHolder, names);

        UIElement priceRow = Ui.row();
        priceRow.layout(l -> l.gapAll(Ui.GAP_S));
        detailPrice = Ui.figure(Component.empty(), "sm-price");
        priceRow.addChildren(Ui.icon(new ItemStack(Items.EMERALD), 16), detailPrice);
        detailBid = Ui.text("", "sm-up");
        detailStock = Ui.text("");

        detailQtyRow = Ui.row();
        detailQtyRow.layout(l -> l.gapAll(Ui.GAP_S));
        TextField qty = Ui.numberField(model.buyQty(), MarketText.market("qty"), 1_000_000, model::setBuyQty);
        qty.layout(l -> l.width(0).flexGrow(1).minWidth(40));
        detailQtyRow.addChildren(Ui.text(MarketText.market("qty"), "sm-muted"), qty);

        buyAction = action(MarketText.market("buy"), Ui.Tone.PRIMARY, controller::openBuyDialog);
        unlistAction = action(MarketText.market("unlist"), Ui.Tone.DANGER, controller::unlistSelected);
        sellersButton = Ui.button(Component.empty(), Ui.Tone.SECONDARY, () -> {
            MarketOverviewData.ListingEntry listing = model.selectedListing();
            if (listing != null) {
                model.openCommodity(listing.commodityKey());
                refresh();
            }
        });
        sellersButton.layout(l -> l.widthPercent(100));
        Button bid = Ui.button(MarketText.ui("place_buy_order"), Ui.Tone.SECONDARY, () -> {
            model.useSelectedListingForBuyOrder();
            model.openPage(MarketViewModel.Page.BUY_ORDERS);
            if (buyOrderCommodityField != null) {
                buyOrderCommodityField.setText(model.buyOrderCommodity(), false);
            }
            refresh();
        });
        bid.layout(l -> l.widthPercent(100));

        detailBody.addChildren(head, Ui.divider(), Ui.text(MarketText.ui("lowest_ask"), "sm-muted"), priceRow,
                keyValueRow(MarketText.market("buying_best_bid"), detailBid),
                keyValueRow(MarketText.market("catalog.column.stock"), detailStock),
                Ui.spacer(), detailQtyRow, buyAction.element(), unlistAction.element(), sellersButton, bid);
        detailScroller = Ui.scroller();
        detailScroller.addScrollViewChild(detailBody);
        detailPane.addChildren(detailEmpty, detailScroller);
    }

    private void addProductTab(UIElement row, MarketViewModel.ProductTab tab, String labelKey) {
        Button button = Ui.button(MarketText.market(labelKey), Ui.Tone.SECONDARY, () -> {
            model.openProductTab(tab);
            refresh();
        });
        button.addClass("sm-nav-tab");
        productTabs.put(tab, button);
        row.addChild(button);
    }

    private void refreshCatalog() {
        List<MarketOverviewData.ListingEntry> catalog = model.catalog();
        resultCount.setValue(MarketText.ui("results", catalog.size()));
        catalogGrid.clearAllChildren();
        if (catalog.isEmpty()) {
            catalogGrid.addChild(emptyState(model.data().listingEntries().isEmpty()
                    ? MarketText.ui("market_empty")
                    : MarketText.market("catalog.empty_hint"),
                    model.hasActiveFilters() ? Ui.button(MarketText.ui("clear_filters"), Ui.Tone.SECONDARY, () -> {
                        model.clearFilters();
                        catalogSearchField.setText(model.search(), false);
                        catalogRarityFilter.setSelected(model.rarity(), false);
                        catalogPriceFilter.setSelected(model.priceBand(), false);
                        refresh();
                    }) : null));
            return;
        }
        MarketOverviewData.ListingEntry selected = model.selectedListing();
        for (MarketOverviewData.ListingEntry entry : catalog) {
            catalogGrid.addChild(catalogCard(entry, selected != null && selected.commodityKey().equals(entry.commodityKey())));
        }
    }

    private UIElement catalogCard(MarketOverviewData.ListingEntry entry, boolean selected) {
        UIElement card = Ui.column("sm-card");
        if (selected) {
            card.addClass("selected");
        }
        card.layout(l -> l.width(CARD_W).height(CARD_H).paddingAll(Ui.GAP_XS + 1).gapAll(1).alignItems(AlignItems.CENTER));
        card.addChild(Ui.slot(MarketText.stack(entry.commodityKey()), 24));
        Label name = Ui.text(entry.itemName(), "rarity-" + MarketViewModel.clampRarity(entry.rarity()));
        name.textStyle(t -> t.adaptiveWidth(false));
        name.setOverflowVisible(false);
        name.layout(l -> l.widthPercent(100));
        card.addChild(name);
        UIElement price = Ui.row();
        price.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_XS));
        price.addChildren(Ui.icon(new ItemStack(Items.EMERALD), 8), Ui.text(MarketText.money(entry.unitPrice()), "sm-price"));
        card.addChild(price);
        Label sellers = Ui.text(MarketText.ui("sellers", model.listingCountFor(entry.commodityKey())), "sm-muted");
        sellers.textStyle(t -> t.adaptiveWidth(false));
        sellers.setOverflowVisible(false);
        sellers.layout(l -> l.widthPercent(100));
        card.addChild(sellers);
        card.style(s -> s.tooltips(Component.literal(entry.itemName()),
                MarketText.category(entry.category()), MarketText.rarity(entry.rarity())));
        // first click selects (detail pane updates), clicking the selected card opens its sellers
        return Ui.clickable(card, () -> {
            MarketOverviewData.ListingEntry current = model.selectedListing();
            if (current != null && current.commodityKey().equals(entry.commodityKey())) {
                model.openCommodity(entry.commodityKey());
            } else {
                model.selectListing(entry.listingId());
            }
            refresh();
        });
    }

    private void refreshProduct() {
        productSlotHolder.clearAllChildren();
        String key = model.selectedCommodityKey();
        productSlotHolder.addChild(Ui.slot(MarketText.stack(key), Ui.SLOT));
        productName.setValue(Component.literal(MarketText.commodityName(model.data(), key)));
        productTabs.forEach((tab, button) -> setClass(button, "active", tab == model.productTab()));

        boolean chart = model.productTab() == MarketViewModel.ProductTab.PRICE_CHART;
        productList.setDisplay(!chart);
        productChart.setDisplay(chart);
        productList.clearAllScrollViewChildren();
        switch (model.productTab()) {
            case SELLING -> {
                MarketOverviewData.ListingEntry selected = model.selectedListing();
                for (MarketOverviewData.ListingEntry entry : model.sellersOfSelectedCommodity()) {
                    productList.addScrollViewChild(sellerRow(entry, selected != null && selected.listingId().equals(entry.listingId())));
                }
            }
            case BUYING -> {
                MarketOverviewData.CommodityBuyBook book = model.data().buyBookFor(key);
                if (book == null || book.entries().isEmpty()) {
                    productList.addScrollViewChild(emptyState(MarketText.market("buying_empty"), null));
                } else {
                    for (MarketOverviewData.CommodityBuyEntry entry : book.entries()) {
                        productList.addScrollViewChild(infoRow(entry.buyerName(),
                                MarketText.market("buy_order.price_range", entry.minPriceBp(), entry.maxPriceBp()).getString(),
                                "x" + entry.quantity()));
                    }
                }
            }
            case PRICE_CHART -> productChart.setPoints(candlePoints(key)).setPalette(chartPalette());
        }
    }

    private UIElement sellerRow(MarketOverviewData.ListingEntry entry, boolean selected) {
        UIElement row = Ui.row("sm-row");
        if (selected) {
            row.addClass("selected");
        }
        row.layout(l -> l.widthPercent(100).height(22).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).marginBottom(Ui.GAP_XS));
        UIElement who = Ui.column();
        who.layout(l -> l.flexGrow(1).minWidth(0));
        who.addChildren(Ui.text(entry.sellerName(), "sm-title"),
                Ui.text(entry.sourceDockName() + (entry.sellerNote().isBlank() ? "" : "  " + entry.sellerNote()), "sm-muted"));
        row.addChildren(who,
                Ui.text(MarketText.ui("stock_short", MarketText.stock(entry.availableCount())), "sm-muted"),
                Ui.icon(new ItemStack(Items.EMERALD), 8),
                Ui.text(MarketText.money(entry.unitPrice()), "sm-price"));
        return Ui.clickable(row, () -> {
            model.selectListing(entry.listingId());
            refresh();
        });
    }

    private void refreshDetailPane() {
        MarketOverviewData.ListingEntry listing = model.selectedListing();
        detailEmpty.setDisplay(listing == null);
        detailScroller.setDisplay(listing != null);
        detailBody.setDisplay(listing != null);
        if (listing == null) {
            return;
        }
        if (!listing.commodityKey().equals(detailSlotKey)) {
            detailSlotKey = listing.commodityKey();
            detailSlotHolder.clearAllChildren();
            detailSlotHolder.addChild(Ui.slot(MarketText.stack(listing.commodityKey()), 36));
        }
        detailName.setValue(Component.literal(listing.itemName()));
        setRarityClass(detailName, listing.rarity());
        detailMeta.setValue(Component.literal(MarketText.rarity(listing.rarity()).getString() + " · "
                + MarketText.category(listing.category()).getString()));
        detailSeller.setValue(Component.literal(listing.sellerName()));
        detailPrice.setValue(Component.literal(MarketText.money(listing.unitPrice())));

        MarketOverviewData.CommodityBuyBook book = model.data().buyBookFor(listing.commodityKey());
        int bestBid = Integer.MIN_VALUE;
        if (book != null) {
            for (MarketOverviewData.CommodityBuyEntry entry : book.entries()) {
                bestBid = Math.max(bestBid, entry.maxPriceBp());
            }
        }
        detailBid.setValue(Component.literal(bestBid == Integer.MIN_VALUE ? "-" : bestBid + " bp"));
        detailStock.setValue(Component.literal(MarketText.stock(listing.availableCount())));

        boolean own = model.isOwnListing(listing);
        detailQtyRow.setDisplay(!own);
        buyAction.element().setDisplay(!own);
        unlistAction.element().setDisplay(own);
        buyAction.sync(model.data().linkedDock(), controller.isPending(MarketController.KEY_BUY), false);
        buyAction.element().style(s -> s.tooltips(model.data().linkedDock()
                ? new Component[0] : new Component[]{MarketText.market("linked_dock_missing")}));
        unlistAction.sync(true, controller.isPending(MarketController.KEY_UNLIST),
                controller.isArmed("unlist:" + listing.listingId()));
        sellersButton.setText(MarketText.ui("view_sellers", model.listingCountFor(listing.commodityKey())));
        sellersButton.setDisplay(model.goodsView() == MarketViewModel.GoodsView.BROWSE_MARKET);
    }

    private List<PriceChart.Point> candlePoints(String commodityKey) {
        List<PriceChart.Point> points = new ArrayList<>();
        CommodityCandleSeries series = model.data().candleSeriesFor(commodityKey, "1h");
        if (series != null && !series.points().isEmpty()) {
            for (CommodityCandlePoint p : series.points()) {
                points.add(new PriceChart.Point(p.openUnitPrice(), p.highUnitPrice(), p.lowUnitPrice(), p.closeUnitPrice()));
            }
            return points;
        }
        MarketOverviewData.PriceChartSeries fallback = model.data().priceChartFor(commodityKey);
        if (fallback != null) {
            for (MarketOverviewData.PriceChartPoint p : fallback.points()) {
                points.add(new PriceChart.Point(p.averageUnitPrice(), p.maxUnitPrice(), p.minUnitPrice(), p.averageUnitPrice()));
            }
        }
        return points;
    }

    // ================================================================= buy dialog

    private UIElement buildBuyOverlay() {
        buyOverlay = new UIElement().addClass("sm-overlay");
        buyOverlay.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).left(0).top(0).widthPercent(100).heightPercent(100)
                .alignItems(AlignItems.CENTER).justifyContent(AlignContent.CENTER));
        buyOverlay.style(s -> s.zIndex(50));
        buyDialogBody = Ui.column("sm-dialog");
        buyDialogBody.layout(l -> l.width(210).paddingAll(Ui.GAP_L).gapAll(Ui.GAP_S));
        buyDialogBody.stopInteractionEventsPropagation();
        buyOverlay.addChild(buyDialogBody);
        // clicking the dimmed area outside the dialog closes it, as before
        Ui.clickable(buyOverlay, () -> {
            model.closeBuyDialog();
            refresh();
        });
        return buyOverlay;
    }

    private void refreshBuyDialog() {
        buyOverlay.setDisplay(model.isBuyDialogOpen());
        buyDialogBody.clearAllChildren();
        if (!model.isBuyDialogOpen()) {
            return;
        }
        MarketOverviewData.ListingEntry listing = model.findListing(model.buyDialogListingId());
        buyDialogBody.addChild(Ui.text(MarketText.market("buy.modal.title"), "sm-title"));
        if (listing != null) {
            UIElement item = Ui.row();
            item.layout(l -> l.gapAll(Ui.GAP_S));
            item.addChildren(Ui.slot(MarketText.stack(listing.commodityKey()), Ui.SLOT),
                    Ui.text(listing.itemName() + "  x" + Math.max(1, MarketViewModel.parseQuantity(model.buyQty())), "sm-title"));
            buyDialogBody.addChild(item);
        }
        buyDialogBody.addChild(Ui.text(MarketText.ui("delivery"), "sm-muted"));
        modeRow(MarketViewModel.MODE_SELLER_SHIP, "buy.modal.mode.seller_ship");
        modeRow(MarketViewModel.MODE_AUTO_PICKUP, "buy.modal.mode.auto_pickup");
        modeRow(MarketViewModel.MODE_REAL_PICKUP, "buy.modal.mode.real_pickup");

        if (!MarketViewModel.MODE_REAL_PICKUP.equals(model.buyFulfillment())) {
            buyDialogBody.addChild(Ui.text(MarketText.market("buy.modal.receiving.label"), "sm-muted"));
            if (model.data().receivingWarehouseOptions().isEmpty()) {
                buyDialogBody.addChild(Ui.text(MarketText.market("buy.modal.receiving.none"), "sm-down"));
            } else {
                for (MarketOverviewData.WarehouseOption option : model.data().receivingWarehouseOptions()) {
                    String label = option.townName() == null || option.townName().isBlank()
                            ? option.displayName() : option.displayName() + " · " + option.townName();
                    boolean selected = option.pos().equals(model.buyReceivingPos());
                    UIElement row = choiceRow(label, selected, true, "");
                    BlockPos pos = option.pos();
                    buyDialogBody.addChild(Ui.clickable(row, () -> {
                        if (model.chooseReceiving(pos)) {
                            controller.requestProbe();
                            refresh();
                        }
                    }));
                }
            }
        }
        UIElement actions = Ui.row();
        actions.layout(l -> l.gapAll(Ui.GAP_S).marginTop(Ui.GAP_S));
        Button cancel = Ui.button(MarketText.market("buy.modal.cancel"), Ui.Tone.SECONDARY, () -> {
            model.closeBuyDialog();
            refresh();
        });
        cancel.layout(l -> l.flexGrow(1));
        ActionButton confirm = action(MarketText.market("buy.modal.confirm"), Ui.Tone.PRIMARY, controller::confirmBuy).inRow();
        confirm.sync(model.canConfirmBuy(), controller.isPending(MarketController.KEY_BUY), false);
        actions.addChildren(cancel, confirm.element());
        buyDialogBody.addChild(actions);
    }

    private void modeRow(String mode, String labelKey) {
        boolean enabled = model.isModeEnabled(mode);
        String reason = model.modeBlockedReasonKey(mode);
        UIElement row = choiceRow(MarketText.market(labelKey).getString(), mode.equals(model.buyFulfillment()), enabled,
                reason.isEmpty() ? "" : Component.translatable(reason).getString());
        buyDialogBody.addChild(Ui.clickable(row, () -> {
            if (model.chooseFulfillment(mode)) {
                refresh();
            }
        }));
    }

    private UIElement choiceRow(String label, boolean selected, boolean enabled, String reason) {
        UIElement row = Ui.row("sm-row");
        if (selected) {
            row.addClass("selected");
        }
        row.layout(l -> l.widthPercent(100).height(16).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S));
        row.addChild(Ui.text((selected ? "● " : "○ ") + label, enabled ? "sm-title" : "sm-muted"));
        row.addChild(Ui.spacer());
        if (!reason.isEmpty()) {
            row.addChild(Ui.text(reason, "sm-down"));
        }
        return row;
    }

    // ================================================================= sell

    private UIElement buildSellPage() {
        UIElement page = Ui.row();
        page.layout(l -> l.flexGrow(1).minHeight(0).gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH));
        UIElement left = Ui.column();
        left.layout(l -> l.flexGrow(1).minWidth(0).minHeight(0).gapAll(Ui.GAP_S));
        TextField search = Ui.field(model.storageSearch(), MarketText.market("sell.search_storage"), value -> {
            model.setStorageSearch(value);
            refreshStorage();
        });
        storageList = Ui.scroller();
        left.addChildren(search, storageList);

        sellPane = Ui.column("sm-pane");
        sellPane.layout(l -> l.width(PANE_WIDTH).flexShrink(0).minHeight(0).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        buildSellPane();
        page.addChildren(left, sellPane);
        return page;
    }

    /** Built once so the quantity and price fields keep focus and text across refreshes. */
    private void buildSellPane() {
        sellEmpty = emptyState(MarketText.market("sell.select_hint"), null);
        sellBody = Ui.column();
        sellBody.layout(l -> l.widthPercent(100).minWidth(0).gapAll(Ui.GAP_S));
        UIElement head = Ui.row();
        head.layout(l -> l.gapAll(Ui.GAP_S));
        sellSlotHolder = Ui.row();
        UIElement names = Ui.column();
        names.layout(l -> l.flexGrow(1).minWidth(0));
        sellName = Ui.text("", "sm-title");
        sellStock = Ui.text("", "sm-muted");
        names.addChildren(sellName, sellStock);
        head.addChildren(sellSlotHolder, names);
        listingQtyField = Ui.numberField(model.listingQty(), MarketText.market("qty"), Integer.MAX_VALUE, value -> {
            model.setListingQty(value);
            refreshSellValidation();
        });
        listingPriceField = Ui.numberField(model.listingUnitPrice(), Component.empty(), Integer.MAX_VALUE, value -> {
            model.setListingUnitPrice(value);
            refreshSellValidation();
        });
        sellValidation = Ui.paragraph(Component.empty(), "sm-muted");
        listAction = action(MarketText.market("list_hand"), Ui.Tone.PRIMARY, controller::createListing);
        sellBody.addChildren(head, Ui.divider(), Ui.text(MarketText.market("qty"), "sm-muted"), listingQtyField,
                Ui.text(MarketText.market("sell.manual_price"), "sm-muted"), listingPriceField, sellValidation,
                Ui.spacer(), listAction.element());
        sellScroller = Ui.scroller();
        sellScroller.addScrollViewChild(sellBody);
        sellPane.addChildren(sellEmpty, sellScroller);
    }

    private void refreshStorage() {
        storageList.clearAllScrollViewChildren();
        List<Integer> rows = model.filteredStorageRows();
        if (rows.isEmpty()) {
            storageList.addScrollViewChild(emptyState(model.data().linkedDock()
                    ? MarketText.market("sell.summary.none")
                    : MarketText.market("linked_dock_missing"), null));
            return;
        }
        for (int index : rows) {
            MarketOverviewData.StorageEntry entry = model.data().dockStorageEntries().get(index);
            String key = model.storageRowKey(index);
            UIElement row = Ui.row("sm-row");
            if (key.equals(model.selectedStorageKey())) {
                row.addClass("selected");
            }
            row.layout(l -> l.widthPercent(100).height(22).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).marginBottom(Ui.GAP_XS));
            UIElement names = Ui.column();
            names.layout(l -> l.flexGrow(1).minWidth(0));
            names.addChildren(Ui.text(entry.itemName(), "rarity-" + MarketViewModel.clampRarity(entry.rarity())),
                    Ui.text(entry.detail(), "sm-muted"));
            row.addChildren(Ui.slot(MarketText.stack(entry.commodityKey()), Ui.SLOT), names,
                    Ui.text("x" + entry.quantity(), "sm-muted"));
            storageList.addScrollViewChild(Ui.clickable(row, () -> {
                model.selectStorage(key);
                refresh();
            }));
        }
    }

    private void refreshSellPane() {
        MarketOverviewData.StorageEntry entry = model.selectedStorage();
        sellEmpty.setDisplay(entry == null);
        sellScroller.setDisplay(entry != null);
        sellBody.setDisplay(entry != null);
        if (entry == null) {
            sellShownKey = "";
            return;
        }
        String key = model.selectedStorageKey();
        if (!key.equals(sellShownKey)) {
            // a different row: show its icon (the model cleared the typed price for it)
            sellShownKey = key;
            sellSlotHolder.clearAllChildren();
            sellSlotHolder.addChild(Ui.slot(MarketText.stack(entry.commodityKey()), 36));
        }
        // When the model drops the typed price (another row, or the stock changed), the field must not keep
        // showing it: an empty field means the suggested price, which is what will be sent.
        if (model.listingUnitPrice().isEmpty() && !listingPriceField.getValue().isEmpty()) {
            listingPriceField.setText("", false);
            Ui.setClass(listingPriceField, "sm-invalid", false);
        }
        sellName.setValue(Component.literal(entry.itemName()));
        sellStock.setValue(MarketText.market("sell.info.stock", entry.quantity()));
        listingPriceField.textFieldStyle(st -> st.placeholder(Component.literal(MarketText.money(model.suggestedUnitPrice()))));
        listAction.element().style(st -> st.tooltips(model.data().canManage()
                ? new Component[0] : new Component[]{MarketText.market("finance.read_only")}));
        refreshSellValidation();
    }

    /** Explains the allowed price range as the player types and disables listing while the input is invalid. */
    private void refreshSellValidation() {
        MarketOverviewData.StorageEntry entry = model.selectedStorage();
        if (sellValidation == null || entry == null) {
            return;
        }
        MarketViewModel.PriceCheck check = model.checkListingPrice();
        Component message = switch (check) {
            case OK -> entry.priceConstrained()
                    ? MarketText.market("sell.info.allowed_price_range", MarketText.money(model.minAllowedUnitPrice()),
                    MarketText.money(model.maxAllowedUnitPrice()))
                    : MarketText.market("sell.info.free_price");
            case INVALID -> MarketText.market("error.listing_price_invalid");
            case OUT_OF_RANGE -> MarketText.market("error.listing_price_out_of_range",
                    MarketText.money(model.minAllowedUnitPrice()), MarketText.money(model.maxAllowedUnitPrice()),
                    MarketText.money(model.suggestedUnitPrice()));
            case NO_STORAGE -> MarketText.market("sell.no_selection");
        };
        sellValidation.setValue(message);
        setClass(sellValidation, "sm-down", check != MarketViewModel.PriceCheck.OK);
        setClass(sellValidation, "sm-muted", check == MarketViewModel.PriceCheck.OK);
        setClass(listingPriceField, "sm-invalid", check != MarketViewModel.PriceCheck.OK);
        boolean allowed = model.data().canManage() && model.data().dockStorageAccessible();
        listAction.sync(allowed && check == MarketViewModel.PriceCheck.OK && MarketViewModel.parseQuantity(model.listingQty()) > 0,
                controller.isPending(MarketController.KEY_LIST), false);
    }

    // ================================================================= dispatch

    private UIElement buildDispatchPage() {
        UIElement page = Ui.row();
        page.layout(l -> l.flexGrow(1).minHeight(0).gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH));
        UIElement left = Ui.column();
        left.layout(l -> l.flexGrow(1).minWidth(0).minHeight(0).gapAll(Ui.GAP_S));
        orderList = Ui.scroller();
        left.addChildren(Ui.text(MarketText.market("dispatch.metric.orders"), "sm-muted"), orderList);

        UIElement pane = Ui.column("sm-pane");
        pane.layout(l -> l.width(PANE_WIDTH).flexShrink(0).minHeight(0).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        carrierList = Ui.scroller();
        dispatchAction = action(MarketText.market("dispatch"), Ui.Tone.PRIMARY, controller::dispatchSelectedOrder);
        cancelOrderAction = action(MarketText.market("order.cancel"), Ui.Tone.DANGER, () -> {
            MarketOverviewData.OrderEntry order = model.selectedOrder();
            return controller.cancelPurchaseOrder(order == null ? "" : order.orderId());
        });
        pane.addChildren(Ui.text(MarketText.market("dispatch.select_boat"), "sm-muted"), carrierList,
                dispatchAction.element(), cancelOrderAction.element());
        page.addChildren(left, pane);
        return page;
    }

    private void refreshDispatch() {
        orderList.clearAllScrollViewChildren();
        if (model.data().orderEntries().isEmpty()) {
            orderList.addScrollViewChild(emptyState(MarketText.ui("no_orders_to_ship"), null));
        }
        for (MarketOverviewData.OrderEntry order : model.data().orderEntries()) {
            UIElement row = Ui.row("sm-row");
            if (order.orderId().equals(model.selectedOrderId())) {
                row.addClass("selected");
            }
            row.layout(l -> l.widthPercent(100).height(22).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).marginBottom(Ui.GAP_XS));
            UIElement names = Ui.column();
            names.layout(l -> l.flexGrow(1).minWidth(0));
            names.addChildren(Ui.text(order.label(), "sm-title"),
                    Ui.text(order.sourceDockName() + " → " + order.targetDockName(), "sm-muted"));
            row.addChildren(names, Ui.text("x" + order.quantity(), "sm-muted"), Ui.text(MarketText.orderStatus(order.status()), "sm-muted"));
            orderList.addScrollViewChild(Ui.clickable(row, () -> {
                model.selectOrder(order.orderId());
                refresh();
            }));
        }
        carrierList.clearAllScrollViewChildren();
        for (MarketOverviewData.DispatchOption option : model.currentDispatchOptions()) {
            String key = MarketViewModel.dispatchKey(option);
            UIElement row = choiceRow(MarketText.dispatchCandidateSubtitle(option.carrierName(), option.routeName(), option.terminalLabel()),
                    key.equals(model.selectedDispatchKey()), option.available(), option.available() ? "" : option.availability());
            row.style(s -> s.tooltips(Component.literal(option.terminalLabel()),
                    Component.literal(MarketText.distance(option.distanceMeters()) + " · " + MarketText.eta(option.etaSeconds())),
                    Component.literal(option.detail() == null ? "" : option.detail())));
            carrierList.addScrollViewChild(Ui.clickable(row, () -> {
                model.selectDispatchOption(key);
                refresh();
            }));
        }
        boolean hasOrder = model.selectedOrder() != null;
        dispatchAction.sync(hasOrder && model.data().canManage(), controller.isPending(MarketController.KEY_DISPATCH), false);
        cancelOrderAction.sync(hasOrder, controller.isPending(MarketController.KEY_CANCEL_ORDER),
                hasOrder && controller.isArmed("order:" + model.selectedOrderId()));
    }

    // ================================================================= finance

    private UIElement buildFinancePage() {
        ScrollerView viewport = Ui.scroller();
        UIElement page = Ui.column();
        page.layout(l -> l.widthPercent(100).minWidth(0).gapAll(Ui.GAP_M));
        viewport.addScrollViewChild(page);
        financeFigures = Ui.wrapRow(Ui.GAP_S);
        page.addChild(financeFigures);

        UIElement wallet = Ui.column("sm-pane");
        wallet.layout(l -> l.paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        UIElement amountRow = Ui.row();
        amountRow.layout(l -> l.gapAll(Ui.GAP_S));
        TextField amount = Ui.numberField(model.walletAmount(), MarketText.market("wallet.amount_placeholder"), Integer.MAX_VALUE, value -> {
            model.setWalletAmount(value);
            refreshFinanceButtons();
        });
        amount.layout(l -> l.width(90));
        amountRow.addChildren(Ui.text(MarketText.market("wallet.amount"), "sm-muted"), amount);
        UIElement buttons = Ui.wrapRow(Ui.GAP_S);
        walletActions.clear();
        for (MarketWalletActionPacket.Action walletAction : List.of(MarketWalletActionPacket.Action.CASH_TO_WALLET,
                MarketWalletActionPacket.Action.WALLET_TO_CASH, MarketWalletActionPacket.Action.WALLET_TO_TREASURY,
                MarketWalletActionPacket.Action.TREASURY_TO_WALLET)) {
            ActionButton button = action(MarketText.market("wallet." + walletAction.name().toLowerCase(java.util.Locale.ROOT)),
                    Ui.Tone.SECONDARY, () -> controller.walletAction(walletAction));
            button.element().layout(l -> l.minWidth(80).widthAuto());
            walletActions.put(button, walletAction);
            buttons.addChild(button.element());
        }
        claimAction = action(MarketText.market("wallet.claim_to_wallet"), Ui.Tone.PRIMARY,
                () -> controller.walletAction(MarketWalletActionPacket.Action.CLAIM_CREDITS_TO_WALLET));
        wallet.addChildren(amountRow, buttons, claimAction.element(), Ui.paragraph(MarketText.market("finance_help"), "sm-muted"));
        page.addChild(wallet);

        renameRow = Ui.row();
        renameRow.layout(l -> l.gapAll(Ui.GAP_S));
        TextField rename = Ui.field(model.renameFieldValue(), MarketText.market("rename.placeholder"), model::setRenameDraft);
        rename.layout(l -> l.width(0).flexGrow(1).flexShrink(1).minWidth(40));
        renameAction = action(MarketText.market("rename.confirm"), Ui.Tone.SECONDARY, controller::renameMarket).inRow();
        renameRow.addChildren(Ui.text(MarketText.market("rename.label"), "sm-muted"), rename, renameAction.element());
        page.addChild(renameRow);

        financeEconomy = Ui.column();
        financeEconomy.layout(l -> l.gapAll(Ui.GAP_XS));
        page.addChild(financeEconomy);
        return viewport;
    }

    private void refreshFinance() {
        MarketOverviewData data = model.data();
        financeFigures.clearAllChildren();
        financeFigures.addChildren(
                figureChip(MarketText.ui("wallet_available"), MarketText.money(data.walletAvailableBalance()), true),
                figureChip(MarketText.ui("wallet_reserved"), MarketText.money(data.walletReservedBalance()), false),
                figureChip(MarketText.market("finance.metric.balance"), MarketText.signedMoney(data.netBalance()), false),
                figureChip(MarketText.ui("treasury"), MarketText.money(data.treasuryBalance()), false),
                figureChip(MarketText.ui("pending_credits"), MarketText.money(data.pendingCredits()), false));
        renameRow.setDisplay(data.canManage());
        financeEconomy.clearAllChildren();
        if (data.townName() == null || data.townName().isBlank()) {
            financeEconomy.addChild(Ui.text(MarketText.market("finance.no_town"), "sm-muted"));
        } else {
            financeEconomy.addChildren(
                    keyValue(MarketText.market("finance.town", data.townName()).getString(), "", ""),
                    keyValue(MarketText.market("finance.metric.stockpile").getString(), data.stockpileCommodityTypes() + " / " + data.stockpileTotalUnits(), ""),
                    keyValue(MarketText.market("finance.metric.demand").getString(), data.openDemandCount() + " / " + data.openDemandUnits(), ""),
                    keyValue(MarketText.market("finance.flow").getString(),
                            MarketText.signedMoney(data.totalIncome()) + " / " + MarketText.signedMoney(-data.totalExpense()), ""),
                    keyValue(MarketText.market("finance.employment", MarketText.percent(data.employmentRate())).getString(), "", ""));
        }
        refreshFinanceButtons();
    }

    private void refreshFinanceButtons() {
        MarketOverviewData data = model.data();
        boolean amountOk = MarketViewModel.parseQuantity(model.walletAmount()) > 0;
        boolean pending = controller.isPending(MarketController.KEY_WALLET);
        walletActions.forEach((button, walletAction) -> {
            boolean allowed = switch (walletAction) {
                case WALLET_TO_TREASURY -> data.townId() != null && !data.townId().isBlank();
                case TREASURY_TO_WALLET -> data.canTransferTreasury();
                default -> true;
            };
            button.sync(amountOk && allowed, pending, false);
        });
        claimAction.sync(data.pendingCredits() > 0, pending, false);
        renameAction.sync(data.canManage(), controller.isPending(MarketController.KEY_RENAME), false);
    }

    // ================================================================= buy orders

    private UIElement buildBuyOrdersPage() {
        UIElement page = Ui.row();
        page.layout(l -> l.flexGrow(1).minHeight(0).gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH));
        UIElement left = Ui.column();
        left.layout(l -> l.flexGrow(1).minWidth(0).minHeight(0).gapAll(Ui.GAP_S));
        buyOrderList = Ui.scroller();
        cancelBuyOrderAction = action(MarketText.market("buy_order.cancel"), Ui.Tone.DANGER, controller::cancelSelectedBuyOrder);
        left.addChildren(Ui.text(MarketText.market("buy_orders_overview"), "sm-muted"), buyOrderList, cancelBuyOrderAction.element());

        UIElement form = Ui.column("sm-pane");
        form.layout(l -> l.widthPercent(100).paddingAll(Ui.GAP_M).gapAll(Ui.GAP_S));
        buyOrderCommodityField = Ui.field(model.buyOrderCommodity(), Component.literal("minecraft:wheat"), value -> {
            model.setBuyOrderCommodity(value);
            refreshBuyOrderForm();
        });
        Button useSelected = Ui.button(MarketText.market("buy_order.use_selected"), Ui.Tone.SECONDARY, () -> {
            model.useSelectedListingForBuyOrder();
            buyOrderCommodityField.setText(model.buyOrderCommodity(), false);
            refreshBuyOrderForm();
        });
        useSelected.layout(l -> l.widthPercent(100));
        TextField qty = Ui.numberField(model.buyOrderQty(), MarketText.market("buy_order.qty"), 1_000_000, value -> {
            model.setBuyOrderQty(value);
            refreshBuyOrderForm();
        });
        UIElement band = Ui.row();
        band.layout(l -> l.gapAll(Ui.GAP_S));
        TextField min = Ui.signedField(model.buyOrderMinBp(), MarketText.market("buy_order.min_price"),
                MarketViewModel.BP_MIN, MarketViewModel.BP_MAX, value -> {
                    model.setBuyOrderMinBp(value);
                    refreshBuyOrderForm();
                });
        TextField max = Ui.signedField(model.buyOrderMaxBp(), MarketText.market("buy_order.max_price"),
                MarketViewModel.BP_MIN, MarketViewModel.BP_MAX, value -> {
                    model.setBuyOrderMaxBp(value);
                    refreshBuyOrderForm();
                });
        min.layout(l -> l.width(0).flexGrow(1).minWidth(40));
        max.layout(l -> l.width(0).flexGrow(1).minWidth(40));
        band.addChildren(min, Ui.text("~", "sm-muted"), max);
        createBuyOrderAction = action(MarketText.market("buy_order.create"), Ui.Tone.PRIMARY, controller::createBuyOrder);
        form.addChildren(Ui.text(MarketText.ui("item_id"), "sm-muted"), buyOrderCommodityField, useSelected,
                Ui.text(MarketText.market("buy_order.qty"), "sm-muted"), qty,
                Ui.text(MarketText.ui("price_band_bp"), "sm-muted"), band,
                Ui.paragraph(MarketText.market("buy_order.price_range_hint"), "sm-muted"), Ui.spacer(), createBuyOrderAction.element());
        ScrollerView formViewport = Ui.scroller();
        formViewport.layout(l -> l.heightAuto().width(PANE_WIDTH).flexGrow(0).flexShrink(0));
        formViewport.addScrollViewChild(form);
        page.addChildren(left, formViewport);
        return page;
    }

    private void refreshBuyOrders() {
        buyOrderList.clearAllScrollViewChildren();
        if (model.data().buyOrderEntries().isEmpty()) {
            buyOrderList.addScrollViewChild(emptyState(MarketText.market("buy_order.create_hint"), null));
        }
        for (MarketOverviewData.BuyOrderEntry entry : model.data().buyOrderEntries()) {
            UIElement row = Ui.row("sm-row");
            if (entry.orderId().equals(model.selectedBuyOrderId())) {
                row.addClass("selected");
            }
            row.layout(l -> l.widthPercent(100).height(22).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).marginBottom(Ui.GAP_XS));
            UIElement names = Ui.column();
            names.layout(l -> l.flexGrow(1).minWidth(0));
            names.addChildren(Ui.text(MarketText.commodityName(model.data(), entry.commodityKey()), "sm-title"),
                    Ui.text(MarketText.market("buy_order.price_range", entry.minPriceBp(), entry.maxPriceBp()), "sm-muted"));
            row.addChildren(Ui.slot(MarketText.stack(entry.commodityKey()), Ui.SLOT), names,
                    Ui.text("x" + entry.quantity(), "sm-muted"), Ui.text(MarketText.orderStatus(entry.status()), "sm-muted"));
            buyOrderList.addScrollViewChild(Ui.clickable(row, () -> {
                model.selectBuyOrder(entry.orderId());
                refresh();
            }));
        }
        MarketOverviewData.BuyOrderEntry selected = model.selectedBuyOrder();
        cancelBuyOrderAction.sync(selected != null, controller.isPending(MarketController.KEY_CANCEL_BUY_ORDER),
                selected != null && controller.isArmed("buy_order:" + selected.orderId()));
        refreshBuyOrderForm();
    }

    private void refreshBuyOrderForm() {
        if (createBuyOrderAction != null) {
            createBuyOrderAction.sync(model.isBuyOrderValid(), controller.isPending(MarketController.KEY_BUY_ORDER), false);
        }
    }

    // ================================================================= my orders

    private UIElement buildMyOrdersPage() {
        UIElement page = Ui.column();
        page.layout(l -> l.flexGrow(1).minHeight(0).gapAll(Ui.GAP_S));
        myOrdersHint = Ui.text("", "sm-muted");
        myOrderList = Ui.scroller();
        page.addChildren(Ui.text(MarketText.market("my_orders.subtitle"), "sm-muted"), myOrdersHint, myOrderList);
        return page;
    }

    /** Renders the viewer's purchase orders; cancellable ones offer a confirm-twice cancel (CancelPurchaseOrderPacket). */
    private void buildMyOrdersPage(List<MarketOverviewData.MyOrderEntry> orders) {
        myOrdersHint.setValue(MarketText.market("my_orders.hint", orders.size()));
        myOrderList.clearAllScrollViewChildren();
        if (orders.isEmpty()) {
            myOrderList.addScrollViewChild(emptyState(MarketText.market("my_orders.empty"), Ui.button(MarketText.market("page.goods"), Ui.Tone.SECONDARY, () -> {
                model.openPage(MarketViewModel.Page.GOODS);
                refresh();
            })));
            return;
        }
        for (MarketOverviewData.MyOrderEntry order : orders) {
            UIElement card = Ui.column("sm-row");
            card.layout(l -> l.widthPercent(100).paddingAll(Ui.GAP_S).gapAll(Ui.GAP_XS).marginBottom(Ui.GAP_XS));
            UIElement top = Ui.row();
            top.layout(l -> l.gapAll(Ui.GAP_S));
            top.addChildren(Ui.text(order.itemName() + "  x" + order.quantity(), "sm-title"), Ui.spacer(),
                    Ui.text(MarketText.orderStatus(order.status()), "sm-muted"));
            card.addChild(top);
            String eta = order.queuePosition() > 0
                    ? "  " + MarketText.market("my_orders.eta", order.queuePosition(), MarketText.eta(order.queueEtaSeconds())).getString()
                    : "";
            card.addChild(Ui.text(order.sourceDockName() + " → " + order.targetDockName() + eta, "sm-muted"));
            UIElement bottom = Ui.row();
            bottom.layout(l -> l.gapAll(Ui.GAP_S));
            bottom.addChildren(Ui.icon(new ItemStack(Items.EMERALD), 8), Ui.text(MarketText.money(order.totalPrice()), "sm-price"));
            if (order.shippingBoatName() != null && !order.shippingBoatName().isBlank()) {
                bottom.addChild(Ui.text(MarketText.market("my_orders.shipping", order.shippingBoatName()), "sm-muted"));
            }
            bottom.addChild(Ui.spacer());
            if (order.cancellable()) {
                ActionButton cancel = action(MarketText.market("my_orders.cancel"), Ui.Tone.DANGER,
                        () -> controller.cancelPurchaseOrder(order.orderId()));
                cancel.element().layout(l -> l.widthAuto());
                cancel.sync(true, controller.isPending(MarketController.KEY_CANCEL_ORDER), controller.isArmed("order:" + order.orderId()));
                bottom.addChild(cancel.element());
            }
            card.addChild(bottom);
            myOrderList.addScrollViewChild(card);
        }
    }

    // ================================================================= market index

    private UIElement buildIndexPage() {
        UIElement page = Ui.row();
        page.layout(l -> l.flexGrow(1).minHeight(0).gapAll(Ui.GAP_M).alignItems(AlignItems.STRETCH));
        seriesList = Ui.scroller();
        seriesList.layout(l -> l.heightAuto().width(PANE_WIDTH).flexGrow(0));
        UIElement right = Ui.column();
        right.layout(l -> l.flexGrow(1).minWidth(0).minHeight(0).gapAll(Ui.GAP_S));
        indexTitle = Ui.text("", "sm-title");
        indexChart = new PriceChart(false, 24);
        indexChart.layout(l -> l.flexGrow(1).minHeight(60));
        right.addChildren(indexTitle, indexChart, Ui.text(MarketText.ui("chart_zoom_hint"), "sm-muted"));
        page.addChildren(seriesList, right);
        return page;
    }

    private void refreshIndex() {
        List<MarketAnalyticsSeries> all = model.data().analyticsSeries();
        if (all.stream().noneMatch(s -> seriesId(s).equals(selectedSeriesId))) {
            selectedSeriesId = all.isEmpty() ? "" : seriesId(all.get(0));
        }
        seriesList.clearAllScrollViewChildren();
        if (all.isEmpty()) {
            seriesList.addScrollViewChild(emptyState(MarketText.market("price_chart.empty"), null));
        }
        MarketAnalyticsSeries selected = null;
        for (MarketAnalyticsSeries series : all) {
            String id = seriesId(series);
            if (id.equals(selectedSeriesId)) {
                selected = series;
            }
            int last = series.points().isEmpty() ? 0 : series.points().get(series.points().size() - 1).value();
            UIElement row = Ui.row("sm-row");
            if (id.equals(selectedSeriesId)) {
                row.addClass("selected");
            }
            row.layout(l -> l.widthPercent(100).height(18).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).marginBottom(Ui.GAP_XS));
            row.addChildren(Ui.text(series.displayName(), "sm-title"), Ui.spacer(), Ui.text(String.valueOf(last), "sm-muted"));
            seriesList.addScrollViewChild(Ui.clickable(row, () -> {
                selectedSeriesId = id;
                refresh();
            }));
        }
        indexTitle.setValue(Component.literal(selected == null ? "" : selected.displayName()));
        List<PriceChart.Point> points = new ArrayList<>();
        if (selected != null) {
            for (MarketAnalyticsPoint p : selected.points()) {
                points.add(PriceChart.Point.line(p.value()));
            }
        }
        indexChart.setPoints(points).setPalette(chartPalette());
    }

    private static String seriesId(MarketAnalyticsSeries series) {
        return series.scopeType() + ":" + series.scopeKey();
    }

    // ================================================================= toast

    private UIElement buildToast() {
        toast = Ui.row("sm-toast");
        toast.layout(l -> l.positionType(TaffyPosition.ABSOLUTE).bottom(Ui.GAP_L).left(60).height(14)
                .paddingHorizontal(Ui.GAP_M).maxWidth(320));
        toast.style(s -> s.zIndex(60));
        toastText = Ui.text("", "sm-on-toast");
        toast.addChild(toastText);
        toast.setDisplay(false);
        return toast;
    }

    /** Shows a short message: positive results and server notices in the theme's toast, failures outlined in red. */
    public void showToast(Component message, boolean positive) {
        toastText.setValue(message);
        setClass(toast, "negative", !positive);
        toast.setDisplay(true);
        toastUntilTick = host.tick() + 60;
    }

    // ================================================================= refresh & ticking

    /** Re-reads the view-model; called after data updates and after every player action. */
    public void refresh() {
        MarketOverviewData data = model.data();
        if (model.consumeStorageSelectionLost()) {
            showToast(MarketText.ui("storage_selection_lost"), false);
        }
        marketName.setValue(Component.literal(data.marketName()));
        dockStatus.setValue(data.linkedDock()
                ? MarketText.market("linked_dock_value", data.linkedDockName(), data.linkedDockPosText())
                : MarketText.market("linked_dock_missing"));
        setClass(dockStatus, "sm-up", data.linkedDock());
        setClass(dockStatus, "sm-down", !data.linkedDock());
        walletBalance.setValue(Component.literal(MarketText.money(data.walletAvailableBalance())));

        MarketViewModel.Page page = model.page();
        pages.forEach((p, element) -> element.setDisplay(p == page));
        navTabs.forEach((p, tab) -> setClass(tab, "active", p == page));

        switch (page) {
            case GOODS -> {
                boolean product = model.goodsView() == MarketViewModel.GoodsView.PRODUCT_LISTINGS;
                catalogArea.setDisplay(!product);
                productArea.setDisplay(product);
                if (product) {
                    refreshProduct();
                } else {
                    refreshCatalog();
                }
                refreshDetailPane();
            }
            case MARKET_INDEX -> refreshIndex();
            case SELL -> {
                refreshStorage();
                refreshSellPane();
            }
            case DISPATCH -> refreshDispatch();
            case FINANCE -> refreshFinance();
            case BUY_ORDERS -> refreshBuyOrders();
            case MY_ORDERS -> buildMyOrdersPage(data.myOrders());
        }
        refreshBuyDialog();
    }

    /** Called every client tick for time-based UI: toast expiry and confirm buttons returning to normal. */
    public void tick() {
        if (toast.isDisplayed() && host.tick() >= toastUntilTick) {
            toast.setDisplay(false);
        }
        if (armedSince > 0 && host.tick() - armedSince > com.monpai.sailboatmod.client.ui.state.ConfirmGate.DEFAULT_WINDOW_TICKS) {
            armedSince = 0;
            refresh();
        }
    }

    /** Closes any open dropdown; returns whether one was open (Escape closes dropdowns first). */
    public boolean closeOpenSelector() {
        for (Selector<?> selector : selectors) {
            if (selector.isOpen()) {
                selector.hide();
                return true;
            }
        }
        return false;
    }

    // ================================================================= results

    /** Shows the outcome of an action that did not send anything, and refreshes button states either way. */
    public void report(MarketController.Result result) {
        switch (result) {
            case SENT -> {
            }
            case CONFIRM_REQUIRED -> armedSince = host.tick();
            case ALREADY_PENDING -> showToast(MarketText.ui("still_sending"), false);
            case NOTHING_SELECTED -> showToast(MarketText.ui("pick_item"), false);
            case OWN_LISTING -> showToast(MarketText.market("self_buy_denied"), false);
            case INVALID_QUANTITY -> showToast(MarketText.ui("invalid_quantity"), false);
            case INVALID_PRICE -> showToast(MarketText.market("error.listing_price_invalid"), false);
            case PRICE_OUT_OF_RANGE -> showToast(MarketText.market("error.listing_price_out_of_range",
                    MarketText.money(model.minAllowedUnitPrice()), MarketText.money(model.maxAllowedUnitPrice()),
                    MarketText.money(model.suggestedUnitPrice())), false);
            case MODE_UNAVAILABLE -> showToast(MarketText.ui("pick_delivery"), false);
            case INVALID_BUY_ORDER -> showToast(MarketText.ui("invalid_buy_order"), false);
            case INVALID_AMOUNT -> showToast(MarketText.ui("invalid_amount"), false);
        }
        refresh();
    }

    private long armedSince;

    // ================================================================= helpers

    /** A controller-backed button; its result (validation message, confirm state) is reported back to the player. */
    private ActionButton action(Component label, Ui.Tone tone, java.util.function.Supplier<MarketController.Result> run) {
        return new ActionButton(label, tone, () -> report(run.get()));
    }

    private UIElement keyValueRow(Component key, Label value) {
        UIElement row = Ui.row();
        row.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_S));
        row.addChildren(Ui.text(key, "sm-muted"), Ui.spacer(), value);
        return row;
    }

    private static void setRarityClass(UIElement element, int rarity) {
        for (int i = 0; i <= 5; i++) {
            setClass(element, "rarity-" + i, i == MarketViewModel.clampRarity(rarity));
        }
    }

    private <T> Selector<T> selector(List<T> values, T current, java.util.function.Function<T, Component> label,
                                     Consumer<T> onChange, float width) {
        Selector<T> selector = new Selector<>();
        Scrollbars.install(selector.scrollerView);
        // Order matters with LDLib2 2.2.42: installing the provider redraws the preview immediately with the current
        // value, so the value is set first and the provider also tolerates null (it ran with null and crashed the
        // market on open).
        selector.setCandidates(values);
        selector.setSelected(current, false);
        selector.setCandidateUIProvider(value -> Ui.text(value == null ? Component.literal("-") : label.apply(value)));
        selector.setOnValueChanged(value -> {
            if (value != null) {
                onChange.accept(value);
            }
        });
        selector.layout(l -> l.width(width).height(Ui.CONTROL_HEIGHT).flexShrink(0));
        selectors.add(selector);
        return selector;
    }

    private static List<Integer> raritiesWithAll() {
        return List.of(MarketViewModel.RARITY_ALL, 0, 1, 2, 3, 4, 5);
    }

    private Component sortLabel(MarketViewModel.Sort sort) {
        return MarketText.market(switch (sort) {
            case PRICE_ASC -> "catalog.sort.price";
            case STOCK_DESC -> "catalog.sort.stock";
            case NAME_ASC -> "catalog.sort.name";
        });
    }

    private Component rarityFilterLabel(Integer rarity) {
        return rarity == null || rarity < 0 ? MarketText.market("catalog.filter.rarity") : MarketText.rarity(rarity);
    }

    private Component priceBandLabel(MarketViewModel.PriceBand band) {
        return MarketText.market(switch (band) {
            case ALL -> "catalog.price.all";
            case LOW -> "catalog.price.low";
            case MID -> "catalog.price.mid";
            case HIGH -> "catalog.price.high";
        });
    }

    private UIElement figureChip(Component label, String value, boolean money) {
        UIElement chip = Ui.column("sm-chip");
        chip.layout(l -> l.minWidth(70).paddingAll(Ui.GAP_S).gapAll(Ui.GAP_XS));
        chip.addChild(Ui.text(label, "sm-muted"));
        UIElement row = Ui.row();
        row.layout(l -> l.gapAll(Ui.GAP_XS));
        if (money) {
            row.addChild(Ui.icon(new ItemStack(Items.EMERALD), 12));
        }
        row.addChild(Ui.text(value, "sm-price"));
        chip.addChild(row);
        return chip;
    }

    private UIElement keyValue(String key, String value, String valueClass) {
        UIElement row = Ui.row();
        row.layout(l -> l.widthPercent(100).gapAll(Ui.GAP_S));
        row.addChildren(Ui.text(key, "sm-muted"), Ui.spacer());
        if (!value.isEmpty()) {
            row.addChild(valueClass.isEmpty() ? Ui.text(value) : Ui.text(value, valueClass));
        }
        return row;
    }

    private UIElement infoRow(String title, String subtitle, String trailing) {
        UIElement row = Ui.row("sm-row");
        row.layout(l -> l.widthPercent(100).height(22).paddingHorizontal(Ui.GAP_S).gapAll(Ui.GAP_S).marginBottom(Ui.GAP_XS));
        UIElement names = Ui.column();
        names.layout(l -> l.flexGrow(1).minWidth(0));
        names.addChildren(Ui.text(title, "sm-title"), Ui.text(subtitle, "sm-muted"));
        row.addChildren(names, Ui.text(trailing, "sm-muted"));
        return row;
    }

    /** Empty lists say what to do next, with an optional action. */
    private UIElement emptyState(Component message, Button action) {
        UIElement box = Ui.column();
        box.layout(l -> l.widthPercent(100).paddingAll(Ui.GAP_L).gapAll(Ui.GAP_S).alignItems(AlignItems.CENTER));
        box.addChild(Ui.paragraph(message, "sm-muted"));
        if (action != null) {
            box.addChild(action);
        }
        return box;
    }

    private PriceChart.Palette chartPalette() {
        return host.theme() == UiTheme.LEDGER
                ? new PriceChart.Palette(0x228B7146, 0xFF1D6F7A, 0xFF4D6B2A, 0xFF9B2C24, 0x408B7146)
                : new PriceChart.Palette(0x22A6B1C6, 0xFF6EA8FF, 0xFF3CCF7E, 0xFFFF6B6B, 0x40A6B1C6);
    }

    private static void setClass(UIElement element, String cssClass, boolean on) {
        if (on) {
            if (!element.hasClass(cssClass)) {
                element.addClass(cssClass);
            }
        } else if (element.hasClass(cssClass)) {
            element.removeClass(cssClass);
        }
    }
}
