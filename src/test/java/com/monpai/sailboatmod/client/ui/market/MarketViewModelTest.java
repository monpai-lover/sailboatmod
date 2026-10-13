package com.monpai.sailboatmod.client.ui.market;

import com.monpai.sailboatmod.market.MarketOverviewData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.monpai.sailboatmod.client.ui.market.MarketFixtures.listing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketViewModelTest {
    private static MarketFixtures twoCommodities() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("L1", "minecraft:wheat", "Wheat", 9, 20, "seller-a", "crop", 0));
        f.listings.add(listing("L2", "minecraft:wheat", "Wheat", 7, 5, "seller-b", "crop", 0));
        f.listings.add(listing("L3", "minecraft:diamond", "Diamond", 120, 2, "seller-c", "gems", 2));
        return f;
    }

    @Test
    void catalogShowsOneEntryPerCommodityCheapestFirst() {
        MarketViewModel model = new MarketViewModel(twoCommodities().build());
        List<MarketOverviewData.ListingEntry> catalog = model.catalog();
        assertEquals(List.of("L2", "L3"), catalog.stream().map(MarketOverviewData.ListingEntry::listingId).toList());
    }

    @Test
    void filtersCombineSearchCategoryRarityAndPriceBand() {
        MarketViewModel model = new MarketViewModel(twoCommodities().build());
        model.setRarity(2);
        assertEquals(List.of("L3"), ids(model.catalog()));
        model.setRarity(MarketViewModel.RARITY_ALL);
        model.setCategory("CROP");
        assertEquals(List.of("L2"), ids(model.catalog()));
        model.setCategory("");
        model.setPriceBand(MarketViewModel.PriceBand.HIGH);
        assertEquals(List.of("L3"), ids(model.catalog()));
        model.setPriceBand(MarketViewModel.PriceBand.ALL);
        model.setSearch("seller-a dock");
        assertEquals(List.of(), ids(model.catalog()), "search matches a single substring, not words");
        model.setSearch("seller l1");
        assertEquals(List.of("L1"), ids(model.catalog()));
        assertTrue(model.hasActiveFilters());
        model.clearFilters();
        assertFalse(model.hasActiveFilters());
    }

    @Test
    void selectionFollowsListingIdWhenRefreshReordersRows() {
        MarketFixtures f = twoCommodities();
        MarketViewModel model = new MarketViewModel(f.build());
        model.selectListing("L3");

        MarketFixtures reordered = new MarketFixtures();
        reordered.listings.add(f.listings.get(2));
        reordered.listings.add(f.listings.get(0));
        reordered.listings.add(f.listings.get(1));
        model.update(reordered.build());

        assertEquals("L3", model.selectedListing().listingId(), "selection must not jump to whatever is now at the old index");
    }

    @Test
    void removedSelectionFallsBackToSameCommodityThenFirstRow() {
        MarketFixtures f = twoCommodities();
        MarketViewModel model = new MarketViewModel(f.build());
        model.openCommodity("minecraft:wheat");
        model.selectListing("L1");

        MarketFixtures withoutL1 = new MarketFixtures();
        withoutL1.listings.add(f.listings.get(1));
        withoutL1.listings.add(f.listings.get(2));
        model.update(withoutL1.build());
        assertEquals("L2", model.selectedListing().listingId());
        assertEquals(MarketViewModel.GoodsView.PRODUCT_LISTINGS, model.goodsView());

        MarketFixtures noWheat = new MarketFixtures();
        noWheat.listings.add(f.listings.get(2));
        model.update(noWheat.build());
        assertEquals("L3", model.selectedListing().listingId());
        assertEquals(MarketViewModel.GoodsView.BROWSE_MARKET, model.goodsView(),
                "a commodity that sold out returns the player to the catalog");
    }

    @Test
    void escapeStepsBackOneLevelBeforeClosing() {
        MarketFixtures f = twoCommodities();
        f.warehouses.add(new MarketOverviewData.WarehouseOption(new BlockPos(1, 2, 3), "Depot", "Town"));
        MarketViewModel model = new MarketViewModel(f.build());
        model.openCommodity("minecraft:wheat");
        model.openBuyDialog();

        assertEquals(MarketViewModel.EscapeResult.HANDLED, model.escape());
        assertFalse(model.isBuyDialogOpen());
        assertEquals(MarketViewModel.EscapeResult.HANDLED, model.escape());
        assertEquals(MarketViewModel.GoodsView.BROWSE_MARKET, model.goodsView());
        model.openPage(MarketViewModel.Page.FINANCE);
        assertEquals(MarketViewModel.EscapeResult.HANDLED, model.escape());
        assertEquals(MarketViewModel.Page.GOODS, model.page());
        assertEquals(MarketViewModel.EscapeResult.CLOSE_SCREEN, model.escape());
    }

    @Test
    void buyDialogOnlyConfirmsAnAvailableMode() {
        MarketFixtures f = twoCommodities();
        BlockPos depot = new BlockPos(1, 2, 3);
        f.warehouses.add(new MarketOverviewData.WarehouseOption(depot, "Depot", "Town"));
        MarketViewModel model = new MarketViewModel(f.build());
        model.selectListing("L1");
        assertTrue(model.openBuyDialog());
        assertEquals(depot, model.buyReceivingPos());
        assertFalse(model.canConfirmBuy(), "shipping is unconfirmed until the route probe answers");

        assertFalse(model.applyProbeResult("L3", true, true, true), "replies for another listing are ignored");
        assertTrue(model.applyProbeResult("L1", false, false, true));
        assertEquals(MarketViewModel.MODE_REAL_PICKUP, model.buyFulfillment(),
                "an unreachable choice moves to the first available mode");
        assertTrue(model.canConfirmBuy());
        assertFalse(model.chooseFulfillment(MarketViewModel.MODE_SELLER_SHIP));
        assertEquals("screen.sailboatmod.market.buy.modal.mode.unreachable",
                model.modeBlockedReasonKey(MarketViewModel.MODE_SELLER_SHIP));
    }

    @Test
    void shippedModesNeedAWarehouse() {
        MarketFixtures f = twoCommodities();
        f.canChooseReceiving = false;
        MarketViewModel model = new MarketViewModel(f.build());
        model.selectListing("L1");
        model.openBuyDialog();
        model.applyProbeResult("L1", true, true, true);
        assertFalse(model.isModeEnabled(MarketViewModel.MODE_SELLER_SHIP));
        assertEquals("screen.sailboatmod.market.buy.modal.mode.need_warehouse",
                model.modeBlockedReasonKey(MarketViewModel.MODE_AUTO_PICKUP));
        assertTrue(model.isModeEnabled(MarketViewModel.MODE_REAL_PICKUP));
    }

    @Test
    void ownListingCannotBeBoughtButIsReportedAsOwn() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("MINE", "minecraft:wheat", "Wheat", 9, 20, MarketFixtures.VIEWER, "crop", 0));
        MarketViewModel model = new MarketViewModel(f.build());
        assertTrue(model.isOwnListing(model.selectedListing()));
        assertFalse(model.openBuyDialog());
    }

    @Test
    void listingPriceIsCheckedAgainstAllowedRange() {
        MarketFixtures f = new MarketFixtures();
        f.storage.add(MarketFixtures.storage("minecraft:wheat", "Wheat", 64, 10, 9, 11, true));
        f.storage.add(MarketFixtures.storage("minecraft:stick", "Stick", 64, 2, 1, 1, false));
        MarketViewModel model = new MarketViewModel(f.build());

        assertEquals(MarketViewModel.PriceCheck.OK, model.checkListingPrice(), "an empty field uses the suggested price");
        model.setListingUnitPrice("12");
        assertEquals(MarketViewModel.PriceCheck.OUT_OF_RANGE, model.checkListingPrice());
        model.setListingUnitPrice("abc");
        assertEquals(MarketViewModel.PriceCheck.INVALID, model.checkListingPrice());
        model.setListingUnitPrice("11");
        assertEquals(MarketViewModel.PriceCheck.OK, model.checkListingPrice());
        assertEquals(1000, model.derivedPriceAdjustmentBp());

        model.selectStorage(MarketViewModel.storageKey(f.storage.get(1)));
        assertEquals("", model.listingUnitPrice(), "switching stock resets the typed price");
        model.setListingUnitPrice("500");
        assertEquals(MarketViewModel.PriceCheck.OK, model.checkListingPrice(), "unconstrained stock accepts any positive price");
    }

    @Test
    void storageSelectionResolvesToCurrentIndexAtSendTime() {
        MarketFixtures f = new MarketFixtures();
        MarketOverviewData.StorageEntry wheat = MarketFixtures.storage("minecraft:wheat", "Wheat", 64, 10, 9, 11, true);
        MarketOverviewData.StorageEntry stick = MarketFixtures.storage("minecraft:stick", "Stick", 8, 2, 1, 3, true);
        f.storage.add(wheat);
        f.storage.add(stick);
        MarketViewModel model = new MarketViewModel(f.build());
        model.selectStorage(MarketViewModel.storageKey(stick));
        assertEquals(1, model.selectedStorageIndex());

        MarketFixtures reordered = new MarketFixtures();
        reordered.storage.add(stick);
        reordered.storage.add(wheat);
        model.update(reordered.build());
        assertEquals(0, model.selectedStorageIndex());
        assertEquals("Stick", model.selectedStorage().itemName());
    }

    @Test
    void dispatchOptionsComeFromSelectedOrderOrMarketCarriers() {
        MarketFixtures f = new MarketFixtures();
        MarketOverviewData.DispatchOption boat = MarketFixtures.carrier("PORT", "Boat");
        MarketOverviewData.DispatchOption cart = MarketFixtures.carrier("POST_STATION", "Cart");
        f.carriers.add(boat);
        f.orders.add(MarketFixtures.order("O1", List.of(cart)));
        MarketViewModel model = new MarketViewModel(f.build());
        assertEquals(List.of(cart), model.currentDispatchOptions());
        assertEquals(MarketViewModel.dispatchKey(cart), model.selectedDispatchKey());
        assertEquals(List.of(boat), MarketViewModel.dispatchOptionsFor(null, List.of(boat)));
        assertEquals(List.of(), MarketViewModel.dispatchOptionsFor(null, null));
    }

    @Test
    void renameKeepsServerNameUntilEdited() {
        MarketViewModel model = new MarketViewModel(new MarketFixtures().build());
        assertEquals("Harbor", model.renameFieldValue());
        model.setRenameDraft("  New Harbor ");
        assertEquals("New Harbor", model.takeRenameValue());
        assertEquals("Harbor", model.renameFieldValue(), "after sending, the field shows the server name again");
    }

    @Test
    void buyOrderNeedsCommodityPositiveQuantityAndOrderedBand() {
        MarketViewModel model = new MarketViewModel(new MarketFixtures().build());
        assertFalse(model.isBuyOrderValid(), "no commodity and no listing to fall back on");
        model.setBuyOrderCommodity("minecraft:wheat");
        assertTrue(model.isBuyOrderValid());
        model.setBuyOrderMinBp("500");
        model.setBuyOrderMaxBp("-500");
        assertFalse(model.isBuyOrderValid());
        assertEquals(1000, MarketViewModel.parseBp("5000"));
        assertEquals(0, MarketViewModel.parseBp("x"));
        assertEquals(-1, MarketViewModel.parseQuantity("0"));
    }

    @Test
    void probeChangeOfWarehouseRequiresFreshAnswer() {
        MarketFixtures f = twoCommodities();
        BlockPos a = new BlockPos(1, 2, 3);
        BlockPos b = new BlockPos(4, 5, 6);
        f.warehouses.add(new MarketOverviewData.WarehouseOption(a, "A", ""));
        f.warehouses.add(new MarketOverviewData.WarehouseOption(b, "B", ""));
        MarketViewModel model = new MarketViewModel(f.build());
        model.selectListing("L1");
        model.openBuyDialog();
        model.applyProbeResult("L1", true, true, true);
        assertTrue(model.isProbeAnswered());
        assertTrue(model.chooseReceiving(b));
        assertFalse(model.isProbeAnswered());
        assertFalse(model.chooseReceiving(new BlockPos(9, 9, 9)), "only server-offered warehouses can be chosen");
    }

    @Test
    void dialogClosesWhenItsListingDisappears() {
        MarketFixtures f = twoCommodities();
        MarketViewModel model = new MarketViewModel(f.build());
        model.selectListing("L3");
        model.openBuyDialog();
        MarketFixtures sold = new MarketFixtures();
        sold.listings.add(f.listings.get(0));
        model.update(sold.build());
        assertFalse(model.isBuyDialogOpen());
        assertNull(model.findListing("L3"));
    }

    private static List<String> ids(List<MarketOverviewData.ListingEntry> entries) {
        return entries.stream().map(MarketOverviewData.ListingEntry::listingId).toList();
    }
}
