package com.monpai.sailboatmod.client.ui.market;

import com.monpai.sailboatmod.market.MarketListing;
import com.monpai.sailboatmod.market.MarketOverviewData;
import com.monpai.sailboatmod.market.analytics.CommodityCandlePoint;
import com.monpai.sailboatmod.market.analytics.CommodityCandleSeries;
import com.monpai.sailboatmod.market.commodity.CommodityMarketService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.monpai.sailboatmod.client.ui.market.MarketFixtures.listing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bid prices, order book, 24h change, watchlist and pinyin search on the market screen. */
class MarketTradingInfoTest {
    private static final long HOUR = 60L * 60L * 1000L;

    private static MarketOverviewData.CommodityBuyEntry bid(String id, int quantity, int minBp, int maxBp) {
        return new MarketOverviewData.CommodityBuyEntry(id, "Buyer " + id, quantity, minBp, maxBp, 0L, "ACTIVE");
    }

    private static List<String> catalogKeys(MarketViewModel model) {
        return model.catalog().stream().map(MarketOverviewData.ListingEntry::commodityKey).toList();
    }

    @Test
    void bidPricesUseTheServersReservationFormula() {
        for (int reference : new int[]{1, 7, 100, 841, 5200}) {
            for (int bp : new int[]{-6000, -1000, -1, 0, 1, 590, 1000, 9000}) {
                assertEquals(CommodityMarketService.reservedBalanceForBuyOrder(reference, 1, bp),
                        MarketViewModel.bidUnitPrice(reference, bp), "reference " + reference + ", " + bp + " bp");
            }
        }
        assertEquals(0, MarketViewModel.bidUnitPrice(0, 590), "no reference price: the screen falls back to bp");
    }

    @Test
    void bestBidIsTheHighestBandEdgeInEmeralds() {
        MarketFixtures f = new MarketFixtures();
        f.buyBooks.add(new MarketOverviewData.CommodityBuyBook("minecraft:diamond", "Diamond", 800,
                List.of(bid("B1", 4, -200, 100), bid("B2", 2, 0, 500), bid("B3", 0, 0, 900))));
        MarketViewModel model = new MarketViewModel(f.build());

        MarketViewModel.Bid best = model.bestBid("minecraft:diamond");
        assertEquals(500, best.maxPriceBp(), "an order with nothing left to buy is not a bid");
        assertEquals(840, best.unitPrice());
        assertEquals(2, best.quantity());
        assertNull(model.bestBid("minecraft:coal"));
    }

    @Test
    void orderBookMergesLevelsAndAccumulatesFromTheBestPrice() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("L1", "minecraft:diamond", "Diamond", 820, 5, "s1", "gems", 2));
        f.listings.add(listing("L2", "minecraft:diamond", "Diamond", 800, 3, "s2", "gems", 2));
        f.listings.add(listing("L3", "minecraft:diamond", "Diamond", 820, 2, "s3", "gems", 2));
        f.listings.add(listing("L4", "minecraft:diamond", "Diamond", 900, MarketListing.INFINITE_THRESHOLD, "s4", "gems", 2));
        f.listings.add(listing("L5", "minecraft:diamond", "Diamond", 700, 0, "s5", "gems", 2));
        f.buyBooks.add(new MarketOverviewData.CommodityBuyBook("minecraft:diamond", "Diamond", 800,
                List.of(bid("B1", 4, -200, -100), bid("B2", 6, 0, 0), bid("B3", 1, -500, -100))));
        MarketViewModel.OrderBook book = new MarketViewModel(f.build()).orderBook("minecraft:diamond");

        assertEquals(List.of(new MarketViewModel.DepthLevel(800, 3, 3), new MarketViewModel.DepthLevel(820, 7, 10),
                new MarketViewModel.DepthLevel(900, 0, 10)), book.asks(), "sold-out rows skipped, unlimited stock adds no depth");
        assertEquals(List.of(new MarketViewModel.DepthLevel(800, 6, 6), new MarketViewModel.DepthLevel(792, 5, 11)),
                book.bids(), "highest bid first");
        assertEquals(11, book.depth());
    }

    @Test
    void bidsWithoutAReferencePriceStayOutOfTheBook() {
        MarketFixtures f = new MarketFixtures();
        f.buyBooks.add(new MarketOverviewData.CommodityBuyBook("minecraft:diamond", "Diamond", List.of(bid("B1", 4, 0, 100))));
        MarketViewModel model = new MarketViewModel(f.build());
        assertTrue(model.orderBook("minecraft:diamond").isEmpty());
        assertEquals(0, model.bestBid("minecraft:diamond").unitPrice());
    }

    @Test
    void changeOver24HoursComparesTheLatestCloseWithTheEarliestInTheWindow() {
        long now = 1_000 * HOUR;
        MarketFixtures f = new MarketFixtures();
        f.candles.add(new CommodityCandleSeries("minecraft:diamond", "Diamond", "1h", List.of(
                candle(now - 30 * HOUR, 50), candle(now - 23 * HOUR, 100), candle(now - 5 * HOUR, 104), candle(now, 110))));
        f.candles.add(new CommodityCandleSeries("minecraft:coal", "Coal", "1h", List.of(candle(now, 3))));
        f.candles.add(new CommodityCandleSeries("minecraft:iron_ingot", "Iron", "1h", List.of(candle(now - HOUR, 40), candle(now, 30))));
        MarketViewModel model = new MarketViewModel(f.build());

        assertEquals(0.10F, model.change24h("minecraft:diamond"), 1e-6F, "the close from 30 hours ago is outside the window");
        assertEquals(-0.25F, model.change24h("minecraft:iron_ingot"), 1e-6F);
        assertNull(model.change24h("minecraft:coal"), "a single hour has no change");
        assertNull(model.change24h("minecraft:wheat"));
        assertEquals("+10.0%", MarketText.change(0.10F));
        assertEquals("-25.0%", MarketText.change(-0.25F));
        assertEquals("0.0%", MarketText.change(0F));
    }

    private static CommodityCandlePoint candle(long bucketAt, int close) {
        return new CommodityCandlePoint(bucketAt, close, close, close, close, 1, 1);
    }

    @Test
    void pinyinInitialsFindChineseNamesButNotEnglishOnes() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("L1", "minecraft:diamond", "钻石", 800, 3, "s1", "gems", 2));
        f.listings.add(listing("L2", "minecraft:emerald", "绿宝石", 260, 3, "s2", "gems", 1));
        f.listings.add(listing("L3", "minecraft:diamond_sword", "Diamond Sword", 1700, 1, "s3", "weapon", 2));
        f.listings.add(listing("L4", "minecraft:netherite_ingot", "下界合金锭", 5200, 1, "s4", "metal", 4));
        MarketViewModel model = new MarketViewModel(f.build());

        model.setSearch("zs");
        assertEquals(List.of("minecraft:diamond"), catalogKeys(model));
        model.setSearch("LBS");
        assertEquals(List.of("minecraft:emerald"), catalogKeys(model), "case does not matter");
        model.setSearch("hjd");
        assertEquals(List.of("minecraft:netherite_ingot"), catalogKeys(model), "initials can match inside the name");
        model.setSearch("ds");
        assertEquals(List.of(), catalogKeys(model), "English names are matched as text, not by initials");
        model.setSearch("sword");
        assertEquals(List.of("minecraft:diamond_sword"), catalogKeys(model));
        model.setSearch("钻");
        assertEquals(List.of("minecraft:diamond"), catalogKeys(model));
    }

    @Test
    void pinyinTableCoversCommonItemNames() {
        assertEquals("zs", PinyinInitials.of("钻石"));
        assertEquals("xjhjd", PinyinInitials.of("下界合金锭"));
        assertEquals("sxy", PinyinInitials.of("生鳕鱼"));
        assertEquals("qc", PinyinInitials.of("鞘翅"));
        assertEquals("hyzx", PinyinInitials.of("海洋之心"));
        assertEquals("jd", PinyinInitials.of("金锭 Gold"));
        assertEquals("", PinyinInitials.of("Diamond"));
        assertEquals("", PinyinInitials.of(null));
    }

    @Test
    void watchlistFilterAndClearing() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("L1", "minecraft:diamond", "Diamond", 800, 3, "s1", "gems", 2));
        f.listings.add(listing("L2", "minecraft:coal", "Coal", 3, 64, "s2", "ore", 0));
        MarketViewModel model = new MarketViewModel(f.build());
        model.setWatchlist(Set.of("minecraft:coal"));

        assertTrue(model.isWatched("minecraft:coal"));
        assertFalse(model.isWatched("minecraft:diamond"));
        model.setWatchOnly(true);
        assertTrue(model.hasActiveFilters());
        assertEquals(List.of("minecraft:coal"), catalogKeys(model));
        model.clearFilters();
        assertFalse(model.watchOnly());
        assertEquals(2, model.catalog().size());
    }

    @Test
    void orderProgressFlows() {
        assertEquals(List.of("PAID", "WAITING_SHIPMENT", "IN_TRANSIT", "DELIVERED"), MarketViewModel.orderFlow("waiting_shipment"));
        assertEquals(1, MarketViewModel.orderStep("WAITING_SHIPMENT"));
        assertEquals(List.of("PAID", "PICKUP_LOCKED", "DELIVERED"), MarketViewModel.orderFlow("PICKUP_LOCKED"));
        assertEquals(1, MarketViewModel.orderStep("PICKUP_LOCKED"));
        assertEquals(3, MarketViewModel.orderStep("DELIVERED"));
        assertEquals(List.of(), MarketViewModel.orderFlow("CANCELLED"));
        assertEquals(-1, MarketViewModel.orderStep("CANCELLED"));
        assertEquals(-1, MarketViewModel.orderStep(null));
    }

    @Test
    void queueEtaCountsDownFromWhenTheSnapshotArrived() {
        MarketOverviewData.MyOrderEntry queued = new MarketOverviewData.MyOrderEntry("PO-1", "L1", "Iron", 64, 2880,
                "Rotterdam", "Harbor", "WAITING_SHIPMENT", 2, 60, "", "", "");
        MarketViewModel model = new MarketViewModel(new MarketFixtures().build());
        model.markReceived(100);
        assertEquals(60, model.remainingEtaSeconds(queued, 100));
        assertEquals(40, model.remainingEtaSeconds(queued, 100 + 20 * 20));
        assertEquals(0, model.remainingEtaSeconds(queued, 100 + 20 * 90));
        model.applyLiveUpdate(new MarketFixtures().build(), 5_000);
        assertEquals(60, model.remainingEtaSeconds(queued, 5_000), "a fresh snapshot restarts the countdown");
    }
}
