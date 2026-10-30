package com.monpai.sailboatmod.client.ui.market;

import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.market.analytics.CommodityCandleSeries;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Small builder for market snapshots used by the market UI tests. */
final class MarketFixtures {
    static final BlockPos MARKET = new BlockPos(10, 64, -20);
    static final String VIEWER = "viewer-uuid";

    String viewerUuid = VIEWER;
    boolean canChooseReceiving = true;
    final List<MarketOverviewData.StorageEntry> storage = new ArrayList<>();
    final List<MarketOverviewData.ListingEntry> listings = new ArrayList<>();
    final List<MarketOverviewData.OrderEntry> orders = new ArrayList<>();
    final List<MarketOverviewData.DispatchOption> carriers = new ArrayList<>();
    final List<MarketOverviewData.BuyOrderEntry> buyOrders = new ArrayList<>();
    final List<MarketOverviewData.WarehouseOption> warehouses = new ArrayList<>();
    final List<MarketOverviewData.MyOrderEntry> myOrders = new ArrayList<>();
    final List<MarketOverviewData.CommodityBuyBook> buyBooks = new ArrayList<>();
    final List<CommodityCandleSeries> candles = new ArrayList<>();

    static MarketOverviewData.ListingEntry listing(String id, String commodity, String name, int price, int stock,
                                                   String sellerUuid, String category, int rarity) {
        return new MarketOverviewData.ListingEntry(id, name, commodity, name, stock, 0, price, "Seller " + id,
                sellerUuid, "Dock", "", "", category, rarity);
    }

    static MarketOverviewData.StorageEntry storage(String commodity, String label, int qty, int suggested,
                                                   int min, int max, boolean constrained) {
        return new MarketOverviewData.StorageEntry(label, commodity, label, qty, suggested, min, max, constrained,
                label + " x" + qty, "material", 0, "fixture:" + commodity + ":" + label);
    }

    static MarketOverviewData.DispatchOption carrier(String kind, String name) {
        return new MarketOverviewData.DispatchOption(kind, kind + " terminal", name, "Route " + name, "From", "To",
                100, 60, true, "Ready", "");
    }

    static MarketOverviewData.OrderEntry order(String id, List<MarketOverviewData.DispatchOption> options) {
        return new MarketOverviewData.OrderEntry(id, "Order " + id, "From", "To", 4, "PAID", 0, 0, "buyer", options);
    }

    static MarketOverviewData.BuyOrderEntry buyOrder(String id, String commodity) {
        return new MarketOverviewData.BuyOrderEntry(id, "Want " + commodity, commodity, 8, -100, 200, "Buyer", "OPEN", 0L);
    }

    MarketOverviewData build() {
        return new MarketOverviewData(
                MARKET, "Harbor", "Owner", "owner-uuid", viewerUuid,
                5, 100L, 0L, 100L, 0L, false,
                true, "Harbor Dock", "0, 64, 0", true, true,
                "town", "Town", 0, 0, 0, 0, 0, 0L, 0L, 0L, 0.0F,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.copyOf(storage), List.copyOf(listings), List.copyOf(orders), List.of(), List.copyOf(carriers),
                List.copyOf(buyOrders), List.of(), List.copyOf(buyBooks), List.copyOf(candles), List.of(), List.of(),
                List.copyOf(warehouses), canChooseReceiving, List.copyOf(myOrders));
    }
}
