package com.monpai.sailboatmod.client.ui.market;

import com.monpai.sailboatmod.market.MarketOverviewData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.monpai.sailboatmod.client.ui.market.MarketFixtures.listing;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Snapshots pushed after other players' changes (live updates) versus answers to the viewer's own actions. */
class MarketLiveUpdateTest {
    private final List<Object> sent = new ArrayList<>();
    private long tick;

    private static MarketOverviewData.MyOrderEntry myOrder(String id, String status) {
        return new MarketOverviewData.MyOrderEntry(id, "L1", "Wheat", 3, 21, "Dock", "Harbor", status, 0, 0, "", "", "");
    }

    @Test
    void movedCheapestAsksFlashUntilTheyExpire() {
        MarketFixtures before = new MarketFixtures();
        before.listings.add(listing("L1", "minecraft:wheat", "Wheat", 9, 20, "s1", "crop", 0));
        before.listings.add(listing("L2", "minecraft:wheat", "Wheat", 12, 20, "s2", "crop", 0));
        before.listings.add(listing("L3", "minecraft:iron_ingot", "Iron", 40, 5, "s3", "metal", 0));
        before.listings.add(listing("L4", "minecraft:coal", "Coal", 3, 5, "s4", "ore", 0));
        MarketViewModel model = new MarketViewModel(before.build());

        MarketFixtures after = new MarketFixtures();
        // the 9-emerald wheat sold out, iron got a cheaper seller, coal is unchanged
        after.listings.add(listing("L2", "minecraft:wheat", "Wheat", 12, 20, "s2", "crop", 0));
        after.listings.add(listing("L3", "minecraft:iron_ingot", "Iron", 40, 5, "s3", "metal", 0));
        after.listings.add(listing("L5", "minecraft:iron_ingot", "Iron", 35, 5, "s5", "metal", 0));
        after.listings.add(listing("L4", "minecraft:coal", "Coal", 3, 5, "s4", "ore", 0));
        model.applyLiveUpdate(after.build(), 100);

        assertEquals(MarketViewModel.PriceMove.UP, model.priceMove("minecraft:wheat", 100));
        assertEquals(MarketViewModel.PriceMove.DOWN, model.priceMove("minecraft:iron_ingot", 120));
        assertNull(model.priceMove("minecraft:coal", 100));
        assertFalse(model.expirePriceFlashes(100 + MarketViewModel.PRICE_FLASH_TICKS - 1));
        assertTrue(model.expirePriceFlashes(100 + MarketViewModel.PRICE_FLASH_TICKS));
        assertNull(model.priceMove("minecraft:wheat", 100 + MarketViewModel.PRICE_FLASH_TICKS));
    }

    @Test
    void reportsTheViewersOrdersWhoseStatusChanged() {
        MarketFixtures before = new MarketFixtures();
        before.myOrders.add(myOrder("PO-1", "WAITING_SHIPMENT"));
        before.myOrders.add(myOrder("PO-2", "IN_TRANSIT"));
        MarketViewModel model = new MarketViewModel(before.build());

        MarketFixtures after = new MarketFixtures();
        after.myOrders.add(myOrder("PO-1", "IN_TRANSIT"));
        after.myOrders.add(myOrder("PO-2", "IN_TRANSIT"));
        after.myOrders.add(myOrder("PO-3", "PAID"));
        List<MarketOverviewData.MyOrderEntry> changed = model.applyLiveUpdate(after.build(), 0);

        assertEquals(1, changed.size(), "new orders are not changes; unchanged ones are ignored");
        assertEquals("PO-1", changed.get(0).orderId());
        assertEquals("IN_TRANSIT", changed.get(0).status());
    }

    @Test
    void aLiveUpdateDoesNotAnswerTheViewersPendingPurchase() {
        MarketFixtures f = new MarketFixtures();
        f.listings.add(listing("L1", "minecraft:wheat", "Wheat", 9, 20, "seller", "crop", 0));
        MarketController controller = new MarketController(new MarketViewModel(f.build()), sent::add, () -> tick);
        controller.model().selectListing("L1");
        assertEquals(MarketController.Result.SENT, controller.openBuyDialog());
        controller.model().chooseFulfillment(MarketViewModel.MODE_REAL_PICKUP);
        assertEquals(MarketController.Result.SENT, controller.confirmBuy());
        assertTrue(controller.isPending(MarketController.KEY_BUY));

        controller.onLiveUpdate(f.build());
        assertTrue(controller.isPending(MarketController.KEY_BUY), "someone else's change is not the answer");
        controller.model().openBuyDialog();
        controller.model().chooseFulfillment(MarketViewModel.MODE_REAL_PICKUP);
        assertEquals(MarketController.Result.ALREADY_PENDING, controller.confirmBuy(), "so the purchase cannot be sent twice");

        controller.onDataUpdated(f.build());
        assertFalse(controller.isPending(MarketController.KEY_BUY), "the reply to the purchase answers it");
    }
}
