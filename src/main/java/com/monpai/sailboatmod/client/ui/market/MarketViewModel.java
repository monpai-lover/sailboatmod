package com.monpai.sailboatmod.client.ui.market;

import com.monpai.sailboatmod.market.MarketOverviewData;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Framework-independent state for the market terminal screen.
 *
 * <p>Holds what the player is looking at (page, goods view, filters), what they selected and what they typed. Every
 * selection is stored by a stable identifier (listing ID, order ID, commodity key, storage key), never by list
 * position, so a server refresh that reorders or removes rows cannot move the selection onto a different item. When
 * a selected row disappears the selection falls back to the first remaining row of the same list.</p>
 *
 * <p>Nothing here grants permission. Flags such as {@link #isOwnListing} only shape what the screen offers; the
 * server re-checks identity, ownership and funds for every packet.</p>
 */
public final class MarketViewModel {
    public enum Page {
        GOODS, MARKET_INDEX, SELL, DISPATCH, FINANCE, BUY_ORDERS, MY_ORDERS
    }

    /** Goods page depth: catalog of commodities, then the sellers of one commodity. */
    public enum GoodsView {
        BROWSE_MARKET, PRODUCT_LISTINGS
    }

    /** Tabs shown for the selected commodity. */
    public enum ProductTab {
        SELLING, BUYING, PRICE_CHART
    }

    public enum Sort {
        PRICE_ASC, STOCK_DESC, NAME_ASC
    }

    public enum PriceBand {
        ALL, LOW, MID, HIGH;

        public boolean matches(int price) {
            return switch (this) {
                case ALL -> true;
                case LOW -> price < 16;
                case MID -> price >= 16 && price < 64;
                case HIGH -> price >= 64;
            };
        }
    }

    /** Result of checking the listing price the player typed against the selected storage row. */
    public enum PriceCheck {
        OK, NO_STORAGE, INVALID, OUT_OF_RANGE
    }

    /** What pressing Escape did. */
    public enum EscapeResult {
        HANDLED, CLOSE_SCREEN
    }

    public static final String MODE_SELLER_SHIP = "SELLER_SHIP";
    public static final String MODE_AUTO_PICKUP = "AUTO_PICKUP";
    public static final String MODE_REAL_PICKUP = "REAL_PICKUP";
    public static final int RARITY_ALL = -1;
    /** Buy-order price band limits in basis points, matching the server's accepted range. */
    public static final int BP_MIN = -1000;
    public static final int BP_MAX = 1000;

    private MarketOverviewData data;

    private Page page = Page.GOODS;
    private GoodsView goodsView = GoodsView.BROWSE_MARKET;
    private ProductTab productTab = ProductTab.SELLING;

    private String search = "";
    private String storageSearch = "";
    private String category = "";
    private int rarity = RARITY_ALL;
    private PriceBand priceBand = PriceBand.ALL;
    private Sort sort = Sort.PRICE_ASC;

    private String selectedCommodityKey = "";
    private String selectedListingId = "";
    private int selectedStorageRow = -1;
    private String selectedStorageIdentity = "";
    private boolean storageSelectionLost;
    private String selectedOrderId = "";
    private String selectedDispatchKey = "";
    private String selectedBuyOrderId = "";

    private String listingQty = "1";
    private String listingUnitPrice = "";
    private String buyQty = "1";
    private String buyOrderCommodity = "";
    private String buyOrderQty = "1";
    private String buyOrderMinBp = String.valueOf(BP_MIN);
    private String buyOrderMaxBp = String.valueOf(BP_MAX);
    private String walletAmount = "";
    /** {@code null} while the player has not edited the market name, so refreshes keep showing the server name. */
    private String renameDraft;

    // Buy dialog
    private boolean buyDialogOpen;
    private String buyDialogListingId = "";
    private String buyFulfillment = MODE_SELLER_SHIP;
    private BlockPos buyReceivingPos;
    private boolean sellerShipReachable;
    private boolean autoPickupReachable;
    private boolean realPickupReachable = true;
    private boolean probeAnswered;

    public MarketViewModel(MarketOverviewData data) {
        this.data = Objects.requireNonNull(data, "data");
        reconcileSelections();
    }

    // ---------------------------------------------------------------- data

    /** Placeholder shown until the first server reply arrives (same values as the previous screen used). */
    public static MarketOverviewData placeholder(BlockPos pos) {
        return new MarketOverviewData(pos, "Market", "-", "", "", 0, 0L, 0L, 0L, 0L, false, false, "-", "-", false, false,
                "", "", 0, 0, 0, 0, 0, 0L, 0L, 0L, 0.0F,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), false, List.of());
    }


    public MarketOverviewData data() {
        return data;
    }

    public BlockPos marketPos() {
        return data.marketPos();
    }

    /**
     * Replaces the snapshot with a newer server reply. Selections, filters, typed text and the open dialog survive;
     * selections whose row disappeared fall back to the first remaining row.
     */
    public void update(MarketOverviewData updated) {
        this.data = Objects.requireNonNull(updated, "updated");
        reconcileSelections();
        if (buyDialogOpen && findListing(buyDialogListingId) == null) {
            closeBuyDialog();
        }
    }

    private void reconcileSelections() {
        List<MarketOverviewData.ListingEntry> listings = data.listingEntries();
        if (findListing(selectedListingId) == null) {
            MarketOverviewData.ListingEntry fallback = firstListingOf(selectedCommodityKey);
            if (fallback == null && !listings.isEmpty()) {
                fallback = listings.get(0);
            }
            selectedListingId = fallback == null ? "" : fallback.listingId();
        }
        if (!selectedCommodityKey.isBlank() && firstListingOf(selectedCommodityKey) == null) {
            selectedCommodityKey = "";
            if (goodsView == GoodsView.PRODUCT_LISTINGS) {
                goodsView = GoodsView.BROWSE_MARKET;
            }
        }
        reconcileStorageSelection();
        if (findOrder(selectedOrderId) == null) {
            selectedOrderId = data.orderEntries().isEmpty() ? "" : nullToEmpty(data.orderEntries().get(0).orderId());
        }
        if (findDispatchOption(selectedDispatchKey) == null) {
            List<MarketOverviewData.DispatchOption> options = currentDispatchOptions();
            selectedDispatchKey = options.isEmpty() ? "" : dispatchKey(options.get(0));
        }
        if (findBuyOrder(selectedBuyOrderId) == null) {
            selectedBuyOrderId = data.buyOrderEntries().isEmpty() ? "" : nullToEmpty(data.buyOrderEntries().get(0).orderId());
        }
    }

    // ---------------------------------------------------------------- navigation

    public Page page() {
        return page;
    }

    public void openPage(Page target) {
        page = Objects.requireNonNull(target, "target");
        if (target != Page.GOODS) {
            closeBuyDialog();
        }
    }

    public GoodsView goodsView() {
        return goodsView;
    }

    public ProductTab productTab() {
        return productTab;
    }

    public void openProductTab(ProductTab tab) {
        productTab = Objects.requireNonNull(tab, "tab");
    }

    /** Opens the sellers of one commodity. */
    public void openCommodity(String commodityKey) {
        MarketOverviewData.ListingEntry first = firstListingOf(commodityKey);
        if (first == null) {
            return;
        }
        page = Page.GOODS;
        selectedCommodityKey = commodityKey;
        MarketOverviewData.ListingEntry current = findListing(selectedListingId);
        if (current == null || !commodityKey.equals(current.commodityKey())) {
            selectedListingId = sellersOfSelectedCommodity().get(0).listingId();
        }
        goodsView = GoodsView.PRODUCT_LISTINGS;
        productTab = ProductTab.SELLING;
    }

    public void backToCatalog() {
        goodsView = GoodsView.BROWSE_MARKET;
        closeBuyDialog();
    }

    /**
     * Steps back one level: dialog, then product view, then other pages to Goods; only an Escape on the Goods
     * catalog closes the screen. Focused text fields are handled by the screen before this is called.
     */
    public EscapeResult escape() {
        if (buyDialogOpen) {
            closeBuyDialog();
            return EscapeResult.HANDLED;
        }
        if (page == Page.GOODS && goodsView == GoodsView.PRODUCT_LISTINGS) {
            backToCatalog();
            return EscapeResult.HANDLED;
        }
        if (page != Page.GOODS) {
            page = Page.GOODS;
            goodsView = GoodsView.BROWSE_MARKET;
            return EscapeResult.HANDLED;
        }
        return EscapeResult.CLOSE_SCREEN;
    }

    // ---------------------------------------------------------------- catalog filters

    public String search() {
        return search;
    }

    public void setSearch(String value) {
        search = nullToEmpty(value);
    }

    public String category() {
        return category;
    }

    public void setCategory(String categoryId) {
        category = nullToEmpty(categoryId).trim().toLowerCase(Locale.ROOT);
    }

    public int rarity() {
        return rarity;
    }

    public void setRarity(int value) {
        rarity = value < 0 ? RARITY_ALL : Math.min(5, value);
    }

    public PriceBand priceBand() {
        return priceBand;
    }

    public void setPriceBand(PriceBand band) {
        priceBand = band == null ? PriceBand.ALL : band;
    }

    public Sort sort() {
        return sort;
    }

    public void setSort(Sort value) {
        sort = value == null ? Sort.PRICE_ASC : value;
    }

    public boolean hasActiveFilters() {
        return !search.isBlank() || !category.isEmpty() || rarity != RARITY_ALL || priceBand != PriceBand.ALL;
    }

    public void clearFilters() {
        search = "";
        category = "";
        rarity = RARITY_ALL;
        priceBand = PriceBand.ALL;
    }

    /** One entry per commodity after search, category, rarity and price filters, in the chosen order. */
    public List<MarketOverviewData.ListingEntry> catalog() {
        String query = search.trim().toLowerCase(Locale.ROOT);
        List<MarketOverviewData.ListingEntry> matches = new ArrayList<>();
        for (MarketOverviewData.ListingEntry entry : data.listingEntries()) {
            if (!query.isEmpty()) {
                String haystack = (nullToEmpty(entry.itemName()) + " " + nullToEmpty(entry.sellerName()) + " "
                        + nullToEmpty(entry.sourceDockName()) + " " + nullToEmpty(entry.commodityKey()))
                        .toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) {
                    continue;
                }
            }
            if (!category.isEmpty() && !category.equals(nullToEmpty(entry.category()).trim().toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (rarity != RARITY_ALL && clampRarity(entry.rarity()) != rarity) {
                continue;
            }
            if (!priceBand.matches(entry.unitPrice())) {
                continue;
            }
            matches.add(entry);
        }
        matches.sort(listingOrder());
        List<MarketOverviewData.ListingEntry> perCommodity = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MarketOverviewData.ListingEntry entry : matches) {
            if (seen.add(nullToEmpty(entry.commodityKey()))) {
                perCommodity.add(entry);
            }
        }
        return perCommodity;
    }

    /** Distinct category IDs present in the current snapshot, for the category filter. */
    public List<String> availableCategories() {
        Set<String> categories = new LinkedHashSet<>();
        for (MarketOverviewData.ListingEntry entry : data.listingEntries()) {
            String id = nullToEmpty(entry.category()).trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) {
                categories.add(id);
            }
        }
        List<String> sorted = new ArrayList<>(categories);
        sorted.sort(String::compareTo);
        return sorted;
    }

    public int listingCountFor(String commodityKey) {
        int count = 0;
        for (MarketOverviewData.ListingEntry entry : data.listingEntries()) {
            if (Objects.equals(entry.commodityKey(), commodityKey)) {
                count++;
            }
        }
        return count;
    }

    private Comparator<MarketOverviewData.ListingEntry> listingOrder() {
        Comparator<MarketOverviewData.ListingEntry> primary = switch (sort) {
            case PRICE_ASC -> Comparator.comparingInt(MarketOverviewData.ListingEntry::unitPrice);
            case STOCK_DESC -> Comparator.comparingInt(MarketOverviewData.ListingEntry::availableCount).reversed();
            case NAME_ASC -> Comparator.comparing(entry -> nullToEmpty(entry.itemName()), String.CASE_INSENSITIVE_ORDER);
        };
        // Stable tie-break by listing ID so equal rows do not swap places between refreshes.
        return primary.thenComparing(entry -> nullToEmpty(entry.listingId()));
    }

    // ---------------------------------------------------------------- listing selection

    public String selectedCommodityKey() {
        return selectedCommodityKey;
    }

    /** Sellers of the commodity opened in the product view, cheapest first under the current sort. */
    public List<MarketOverviewData.ListingEntry> sellersOfSelectedCommodity() {
        List<MarketOverviewData.ListingEntry> sellers = new ArrayList<>();
        for (MarketOverviewData.ListingEntry entry : data.listingEntries()) {
            if (!selectedCommodityKey.isBlank() && selectedCommodityKey.equals(entry.commodityKey())) {
                sellers.add(entry);
            }
        }
        sellers.sort(listingOrder());
        return sellers;
    }

    public void selectListing(String listingId) {
        MarketOverviewData.ListingEntry entry = findListing(listingId);
        if (entry != null) {
            selectedListingId = entry.listingId();
            if (!Objects.equals(selectedCommodityKey, entry.commodityKey()) && goodsView == GoodsView.PRODUCT_LISTINGS) {
                selectedCommodityKey = entry.commodityKey();
            }
        }
    }

    public MarketOverviewData.ListingEntry selectedListing() {
        return findListing(selectedListingId);
    }

    /** Whether the selected listing belongs to the viewer; drives "Unlist" instead of "Buy" on screen only. */
    public boolean isOwnListing(MarketOverviewData.ListingEntry listing) {
        if (listing == null || data.viewerUuid() == null || data.viewerUuid().isBlank()) {
            return false;
        }
        return data.viewerUuid().equals(listing.sellerUuid());
    }

    public MarketOverviewData.ListingEntry findListing(String listingId) {
        if (listingId == null || listingId.isBlank()) {
            return null;
        }
        for (MarketOverviewData.ListingEntry entry : data.listingEntries()) {
            if (listingId.equals(entry.listingId())) {
                return entry;
            }
        }
        return null;
    }

    private MarketOverviewData.ListingEntry firstListingOf(String commodityKey) {
        if (commodityKey == null || commodityKey.isBlank()) {
            return null;
        }
        MarketOverviewData.ListingEntry best = null;
        for (MarketOverviewData.ListingEntry entry : data.listingEntries()) {
            if (commodityKey.equals(entry.commodityKey()) && (best == null || listingOrder().compare(entry, best) < 0)) {
                best = entry;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- buy dialog

    public boolean isBuyDialogOpen() {
        return buyDialogOpen;
    }

    public String buyDialogListingId() {
        return buyDialogListingId;
    }

    /**
     * Opens the purchase dialog for the selected listing with the default mode and first receiving warehouse.
     * Reachability starts pessimistic until the probe reply arrives.
     *
     * @return {@code false} when there is nothing to buy or the listing is the viewer's own.
     */
    public boolean openBuyDialog() {
        MarketOverviewData.ListingEntry listing = selectedListing();
        if (listing == null || isOwnListing(listing)) {
            return false;
        }
        buyDialogOpen = true;
        buyDialogListingId = listing.listingId();
        buyFulfillment = MODE_SELLER_SHIP;
        buyReceivingPos = data.receivingWarehouseOptions().isEmpty() ? null : data.receivingWarehouseOptions().get(0).pos();
        sellerShipReachable = false;
        autoPickupReachable = false;
        realPickupReachable = true;
        probeAnswered = false;
        return true;
    }

    public void closeBuyDialog() {
        buyDialogOpen = false;
        buyDialogListingId = "";
    }

    public String buyFulfillment() {
        return buyFulfillment;
    }

    public BlockPos buyReceivingPos() {
        return buyReceivingPos;
    }

    /** Receiving position sent with probes and purchases; {@link BlockPos#ZERO} when none is chosen. */
    public BlockPos buyReceivingPosOrZero() {
        return buyReceivingPos == null ? BlockPos.ZERO : buyReceivingPos;
    }

    public boolean isProbeAnswered() {
        return probeAnswered;
    }

    /**
     * Applies a reachability probe reply. Replies for another listing or a closed dialog are ignored, matching the
     * previous screen. If the chosen mode became unavailable the choice moves to the first available mode.
     */
    public boolean applyProbeResult(String listingId, boolean sellerShip, boolean autoPickup, boolean realPickup) {
        if (!buyDialogOpen || listingId == null || !listingId.equals(buyDialogListingId)) {
            return false;
        }
        sellerShipReachable = sellerShip;
        autoPickupReachable = autoPickup;
        realPickupReachable = realPickup;
        probeAnswered = true;
        if (!isModeEnabled(buyFulfillment)) {
            for (String mode : List.of(MODE_SELLER_SHIP, MODE_AUTO_PICKUP, MODE_REAL_PICKUP)) {
                if (isModeEnabled(mode)) {
                    buyFulfillment = mode;
                    break;
                }
            }
        }
        return true;
    }

    /** Delivery modes need a reachable route; shipped modes also need a receiving warehouse. */
    public boolean isModeEnabled(String mode) {
        return switch (mode) {
            case MODE_SELLER_SHIP -> data.canChooseReceiving() && sellerShipReachable;
            case MODE_AUTO_PICKUP -> data.canChooseReceiving() && autoPickupReachable;
            case MODE_REAL_PICKUP -> realPickupReachable;
            default -> false;
        };
    }

    /** Message key explaining why a mode is unavailable, or empty when it is available. */
    public String modeBlockedReasonKey(String mode) {
        if (isModeEnabled(mode)) {
            return "";
        }
        if (!MODE_REAL_PICKUP.equals(mode) && !data.canChooseReceiving()) {
            return "screen.sailboatmod.market.buy.modal.mode.need_warehouse";
        }
        return probeAnswered
                ? "screen.sailboatmod.market.buy.modal.mode.unreachable"
                : "screen.sailboatmod.ui.checking_route";
    }

    public boolean chooseFulfillment(String mode) {
        if (!isModeEnabled(mode)) {
            return false;
        }
        buyFulfillment = mode;
        return true;
    }

    /** Selects a receiving warehouse offered by the server; the caller must send a fresh probe afterwards. */
    public boolean chooseReceiving(BlockPos pos) {
        for (MarketOverviewData.WarehouseOption option : data.receivingWarehouseOptions()) {
            if (option.pos().equals(pos)) {
                buyReceivingPos = option.pos();
                probeAnswered = false;
                return true;
            }
        }
        return false;
    }

    /**
     * Confirm is offered only when the selected mode is available and, for shipped modes, a warehouse is chosen.
     * The old screen allowed confirming an unreachable mode and relied on the server to refuse it.
     */
    public boolean canConfirmBuy() {
        if (!buyDialogOpen || findListing(buyDialogListingId) == null || parseQuantity(buyQty) <= 0) {
            return false;
        }
        if (!isModeEnabled(buyFulfillment)) {
            return false;
        }
        return MODE_REAL_PICKUP.equals(buyFulfillment) || buyReceivingPos != null;
    }

    // ---------------------------------------------------------------- sell (storage) selection

    public String storageSearch() {
        return storageSearch;
    }

    public void setStorageSearch(String value) {
        storageSearch = nullToEmpty(value);
    }

    /** Server storage indexes of the rows matching the search, in order. */
    public List<Integer> filteredStorageRows() {
        List<MarketOverviewData.StorageEntry> entries = data.dockStorageEntries();
        List<MarketOverviewData.StorageEntry> visible = filteredStorage();
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            MarketOverviewData.StorageEntry entry = entries.get(i);
            for (MarketOverviewData.StorageEntry shown : visible) {
                if (shown == entry) {
                    rows.add(i);
                    break;
                }
            }
        }
        return rows;
    }

    public List<MarketOverviewData.StorageEntry> filteredStorage() {
        String query = storageSearch.trim().toLowerCase(Locale.ROOT);
        List<MarketOverviewData.StorageEntry> rows = new ArrayList<>();
        for (MarketOverviewData.StorageEntry entry : data.dockStorageEntries()) {
            if (!query.isEmpty()) {
                String haystack = (nullToEmpty(entry.itemName()) + " " + nullToEmpty(entry.detail())).toLowerCase(Locale.ROOT);
                if (!haystack.contains(query)) {
                    continue;
                }
            }
            rows.add(entry);
        }
        return rows;
    }

    /**
     * What a storage row holds, independent of its count. Production rows carry the server's component fingerprint;
     * the display-only legacy fallback cannot authorize a listing.
     */
    public static String storageKey(MarketOverviewData.StorageEntry entry) {
        return entry.stockIdentity().isBlank()
                ? "legacy:" + nullToEmpty(entry.commodityKey()) + "|" + nullToEmpty(entry.itemName())
                : entry.stockIdentity();
    }

    /** Key of the row at {@code index}: its server storage position plus its identity. Used by the view. */
    public String storageRowKey(int index) {
        List<MarketOverviewData.StorageEntry> entries = data.dockStorageEntries();
        if (index < 0 || index >= entries.size()) {
            return "";
        }
        return index + "#" + storageKey(entries.get(index));
    }

    /**
     * Selects a storage row by row key (from {@link #storageRowKey}) or, for a plain identity, the first row holding
     * it. Picking the row that is already selected keeps the typed price; picking another row clears it.
     */
    public void selectStorage(String key) {
        int row = resolveStorageKey(key);
        if (row < 0) {
            return;
        }
        if (row != selectedStorageRow) {
            listingUnitPrice = "";
        }
        selectedStorageRow = row;
        selectedStorageIdentity = storageKey(data.dockStorageEntries().get(row));
    }

    private int resolveStorageKey(String key) {
        if (key == null || key.isBlank()) {
            return -1;
        }
        List<MarketOverviewData.StorageEntry> entries = data.dockStorageEntries();
        int hash = key.indexOf('#');
        if (hash > 0) {
            try {
                int row = Integer.parseInt(key.substring(0, hash));
                String identity = key.substring(hash + 1);
                return row < entries.size() && storageKey(entries.get(row)).equals(identity) ? row : -1;
            } catch (NumberFormatException ignored) {
                // not a row key; fall through to identity lookup
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            if (storageKey(entries.get(i)).equals(key)) {
                return i;
            }
        }
        return -1;
    }

    public String selectedStorageKey() {
        return selectedStorage() == null ? "" : storageRowKey(selectedStorageRow);
    }

    public MarketOverviewData.StorageEntry selectedStorage() {
        List<MarketOverviewData.StorageEntry> entries = data.dockStorageEntries();
        if (selectedStorageRow < 0 || selectedStorageRow >= entries.size()) {
            return null;
        }
        MarketOverviewData.StorageEntry entry = entries.get(selectedStorageRow);
        return storageKey(entry).equals(selectedStorageIdentity) ? entry : null;
    }

    /** Server storage index of the selected row, as {@code CreateMarketListingPacket} expects; -1 without one. */
    public int selectedStorageIndex() {
        return selectedStorage() == null ? -1 : selectedStorageRow;
    }

    /**
     * After new data: keep the row if it still holds the same thing (its count may change), follow it if exactly one
     * row now holds it, and otherwise drop the selection and the typed price rather than guess which stack was meant.
     */
    private void reconcileStorageSelection() {
        List<MarketOverviewData.StorageEntry> entries = data.dockStorageEntries();
        if (selectedStorageIdentity.isEmpty()) {
            if (!entries.isEmpty()) {
                selectedStorageRow = 0;
                selectedStorageIdentity = storageKey(entries.get(0));
            }
            return;
        }
        int match = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (storageKey(entries.get(i)).equals(selectedStorageIdentity)) {
                if (match >= 0) {
                    match = -2;
                    break;
                }
                match = i;
            }
        }
        if (match >= 0) {
            selectedStorageRow = match;
            return;
        }
        selectedStorageRow = -1;
        selectedStorageIdentity = "";
        listingQty = "";
        listingUnitPrice = "";
        storageSelectionLost = true;
    }

    /** True once after a refresh dropped the selected stock (it was sold out or became ambiguous). */
    public boolean consumeStorageSelectionLost() {
        boolean lost = storageSelectionLost;
        storageSelectionLost = false;
        return lost;
    }

    public int suggestedUnitPrice() {
        MarketOverviewData.StorageEntry entry = selectedStorage();
        return entry == null ? 1 : Math.max(1, entry.suggestedUnitPrice());
    }

    public int minAllowedUnitPrice() {
        MarketOverviewData.StorageEntry entry = selectedStorage();
        return entry == null ? 1 : Math.max(1, entry.minAllowedUnitPrice());
    }

    public int maxAllowedUnitPrice() {
        MarketOverviewData.StorageEntry entry = selectedStorage();
        return entry == null ? 1 : Math.max(1, entry.maxAllowedUnitPrice());
    }

    /** Price that will be sent: the typed price, or the suggested price while the field is empty. */
    public int requestedUnitPrice() {
        if (listingUnitPrice.isBlank()) {
            return suggestedUnitPrice();
        }
        int parsed = parseQuantity(listingUnitPrice);
        return parsed <= 0 ? -1 : parsed;
    }

    /** Price adjustment implied by the typed price, in basis points relative to the suggested price. */
    public int derivedPriceAdjustmentBp() {
        MarketOverviewData.StorageEntry entry = selectedStorage();
        if (entry == null || !entry.priceConstrained()) {
            return 0;
        }
        int reference = suggestedUnitPrice();
        int requested = requestedUnitPrice();
        if (reference <= 0 || requested <= 0) {
            return 0;
        }
        return (int) Math.round((((double) requested / (double) reference) - 1.0D) * 10000.0D);
    }

    public PriceCheck checkListingPrice() {
        MarketOverviewData.StorageEntry entry = selectedStorage();
        if (entry == null) {
            return PriceCheck.NO_STORAGE;
        }
        int requested = requestedUnitPrice();
        if (requested <= 0) {
            return PriceCheck.INVALID;
        }
        if (!entry.priceConstrained()) {
            return PriceCheck.OK;
        }
        int bp = derivedPriceAdjustmentBp();
        boolean inRange = requested >= minAllowedUnitPrice() && requested <= maxAllowedUnitPrice()
                && bp >= BP_MIN && bp <= BP_MAX;
        return inRange ? PriceCheck.OK : PriceCheck.OUT_OF_RANGE;
    }

    // ---------------------------------------------------------------- dispatch selection

    public List<MarketOverviewData.DispatchOption> currentDispatchOptions() {
        return dispatchOptionsFor(selectedOrder(), data.availableDispatchOptions());
    }

    /** Options for the selected order, or the market-wide carriers when no order is selected. */
    public static List<MarketOverviewData.DispatchOption> dispatchOptionsFor(MarketOverviewData.OrderEntry order,
                                                                           List<MarketOverviewData.DispatchOption> available) {
        if (order != null) {
            return order.dispatchOptions();
        }
        return available == null ? List.of() : available;
    }

    public static String dispatchKey(MarketOverviewData.DispatchOption option) {
        return nullToEmpty(option.terminalKind()) + "|" + nullToEmpty(option.carrierName()) + "|" + nullToEmpty(option.routeName());
    }

    public void selectOrder(String orderId) {
        if (findOrder(orderId) != null) {
            selectedOrderId = orderId;
            List<MarketOverviewData.DispatchOption> options = currentDispatchOptions();
            if (findDispatchOption(selectedDispatchKey) == null) {
                selectedDispatchKey = options.isEmpty() ? "" : dispatchKey(options.get(0));
            }
        }
    }

    public String selectedOrderId() {
        return selectedOrderId;
    }

    public MarketOverviewData.OrderEntry selectedOrder() {
        return findOrder(selectedOrderId);
    }

    public void selectDispatchOption(String key) {
        if (findDispatchOption(key) != null) {
            selectedDispatchKey = key;
        }
    }

    public String selectedDispatchKey() {
        return selectedDispatchKey;
    }

    public MarketOverviewData.DispatchOption selectedDispatchOption() {
        return findDispatchOption(selectedDispatchKey);
    }

    private MarketOverviewData.OrderEntry findOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        for (MarketOverviewData.OrderEntry entry : data.orderEntries()) {
            if (orderId.equals(entry.orderId())) {
                return entry;
            }
        }
        return null;
    }

    private MarketOverviewData.DispatchOption findDispatchOption(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        for (MarketOverviewData.DispatchOption option : currentDispatchOptions()) {
            if (dispatchKey(option).equals(key)) {
                return option;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- buy orders

    public void selectBuyOrder(String orderId) {
        if (findBuyOrder(orderId) != null) {
            selectedBuyOrderId = orderId;
        }
    }

    public String selectedBuyOrderId() {
        return selectedBuyOrderId;
    }

    public MarketOverviewData.BuyOrderEntry selectedBuyOrder() {
        return findBuyOrder(selectedBuyOrderId);
    }

    private MarketOverviewData.BuyOrderEntry findBuyOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        for (MarketOverviewData.BuyOrderEntry entry : data.buyOrderEntries()) {
            if (orderId.equals(entry.orderId())) {
                return entry;
            }
        }
        return null;
    }

    /** Commodity for a new buy order: the typed key, else the selected listing's commodity. */
    public String resolvedBuyOrderCommodity() {
        String manual = buyOrderCommodity.trim();
        if (!manual.isEmpty()) {
            return manual;
        }
        MarketOverviewData.ListingEntry listing = selectedListing();
        return listing == null ? "" : nullToEmpty(listing.commodityKey());
    }

    public void useSelectedListingForBuyOrder() {
        MarketOverviewData.ListingEntry listing = selectedListing();
        if (listing != null) {
            buyOrderCommodity = nullToEmpty(listing.commodityKey());
        }
    }

    public boolean isBuyOrderValid() {
        if (!isBpInput(buyOrderMinBp) || !isBpInput(buyOrderMaxBp)) {
            return false;
        }
        int min = parseBp(buyOrderMinBp);
        int max = parseBp(buyOrderMaxBp);
        return !resolvedBuyOrderCommodity().isBlank() && parseQuantity(buyOrderQty) > 0 && min <= max;
    }

    /** An empty band reads as 0 as before; anything else must be a whole number inside the accepted band. */
    public static boolean isBpInput(String value) {
        String text = nullToEmpty(value).trim();
        if (text.isEmpty()) {
            return true;
        }
        try {
            int parsed = Integer.parseInt(text);
            return parsed >= BP_MIN && parsed <= BP_MAX;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public MarketOverviewData.MyOrderEntry findMyOrder(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return null;
        }
        for (MarketOverviewData.MyOrderEntry entry : data.myOrders()) {
            if (orderId.equals(entry.orderId())) {
                return entry;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- drafts

    public String listingQty() { return listingQty; }
    public void setListingQty(String value) { listingQty = nullToEmpty(value); }
    public String listingUnitPrice() { return listingUnitPrice; }
    public void setListingUnitPrice(String value) { listingUnitPrice = nullToEmpty(value); }
    public String buyQty() { return buyQty; }
    public void setBuyQty(String value) { buyQty = nullToEmpty(value); }
    public String buyOrderCommodity() { return buyOrderCommodity; }
    public void setBuyOrderCommodity(String value) { buyOrderCommodity = nullToEmpty(value); }
    public String buyOrderQty() { return buyOrderQty; }
    public void setBuyOrderQty(String value) { buyOrderQty = nullToEmpty(value); }
    public String buyOrderMinBp() { return buyOrderMinBp; }
    public void setBuyOrderMinBp(String value) { buyOrderMinBp = nullToEmpty(value); }
    public String buyOrderMaxBp() { return buyOrderMaxBp; }
    public void setBuyOrderMaxBp(String value) { buyOrderMaxBp = nullToEmpty(value); }
    public String walletAmount() { return walletAmount; }
    public void setWalletAmount(String value) { walletAmount = nullToEmpty(value); }

    /** Name shown in the rename field: the draft while editing, otherwise the server's current name. */
    public String renameFieldValue() {
        return renameDraft == null ? nullToEmpty(data.marketName()) : renameDraft;
    }

    public void setRenameDraft(String value) {
        renameDraft = value;
    }

    public String takeRenameValue() {
        String value = renameDraft == null ? nullToEmpty(data.marketName()) : renameDraft.trim();
        renameDraft = null;
        return value;
    }

    // ---------------------------------------------------------------- parsing

    /** Positive integer, or {@code -1} for empty, non-numeric or non-positive input. */
    public static int parseQuantity(String value) {
        try {
            int parsed = Integer.parseInt(nullToEmpty(value).trim());
            return parsed > 0 ? parsed : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** Basis points clamped to the accepted band; non-numeric input reads as 0, as before. */
    public static int parseBp(String value) {
        try {
            return Math.max(BP_MIN, Math.min(BP_MAX, Integer.parseInt(nullToEmpty(value).trim())));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public static int clampRarity(int value) {
        return Math.max(0, Math.min(5, value));
    }

    static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
