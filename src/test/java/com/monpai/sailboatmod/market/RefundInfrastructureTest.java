package com.monpai.sailboatmod.market;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RefundInfrastructureTest {
    private static PurchaseOrder order(String orderId, String listingId, String status) {
        return new PurchaseOrder(
                orderId, listingId, "buyer-uuid", "Buyer",
                4, 100,
                new BlockPos(1, 64, 1), "Src",
                new BlockPos(2, 64, 2), "Dst",
                status, "SELLER_SHIP", new BlockPos(2, 64, 2));
    }

    @Test
    void cancelledStatusConstantExists() {
        assertEquals("CANCELLED", PurchaseOrder.STATUS_CANCELLED);
    }

    @Test
    void removePurchaseOrderDropsIt() {
        MarketSavedData data = new MarketSavedData();
        data.putPurchaseOrder(order("ord-1", "lst-1", "WAITING_SHIPMENT"));
        assertNotNull(data.getPurchaseOrder("ord-1"));
        data.removePurchaseOrder("ord-1");
        assertNull(data.getPurchaseOrder("ord-1"));
    }

    @Test
    void activeOrdersForListingFiltersByStatus() {
        MarketSavedData data = new MarketSavedData();
        data.putPurchaseOrder(order("a", "lst-1", "WAITING_SHIPMENT"));
        data.putPurchaseOrder(order("b", "lst-1", "PICKUP_LOCKED"));
        data.putPurchaseOrder(order("c", "lst-1", "IN_TRANSIT"));   // 已发货，不算 active
        data.putPurchaseOrder(order("d", "lst-1", PurchaseOrder.STATUS_CANCELLED)); // 已取消，不算
        data.putPurchaseOrder(order("e", "lst-2", "WAITING_SHIPMENT")); // 别的挂单

        var active = data.getActiveOrdersForListing("lst-1");
        assertEquals(2, active.size());
        assertTrue(active.stream().anyMatch(o -> o.orderId().equals("a")));
        assertTrue(active.stream().anyMatch(o -> o.orderId().equals("b")));
    }
}
