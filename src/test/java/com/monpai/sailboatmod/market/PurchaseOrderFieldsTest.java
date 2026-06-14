package com.monpai.sailboatmod.market;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PurchaseOrderFieldsTest {
    private static PurchaseOrder sample(String fulfillment, BlockPos target) {
        return new PurchaseOrder(
                "ord-1", "lst-1", "buyer-uuid", "Buyer",
                64, 768,
                new BlockPos(10, 64, 20), "SrcDock",
                new BlockPos(30, 64, 40), "TargetDock",
                "WAITING_DISPATCH",
                fulfillment, target);
    }

    @Test
    void newFieldsSurviveNbtRoundTrip() {
        PurchaseOrder order = sample("SELLER_SHIP", new BlockPos(100, 64, 200));
        PurchaseOrder loaded = PurchaseOrder.load(order.save());

        assertEquals("SELLER_SHIP", loaded.fulfillment());
        assertEquals(new BlockPos(100, 64, 200), loaded.targetWarehousePos());
        assertEquals("buyer-uuid", loaded.buyerUuid());
        assertEquals("WAITING_DISPATCH", loaded.status());
    }

    @Test
    void legacyNbtWithoutNewFieldsDefaults() {
        PurchaseOrder order = sample("SELLER_SHIP", new BlockPos(100, 64, 200));
        CompoundTag tag = order.save();
        tag.remove("Fulfillment");
        tag.remove("TargetWarehousePos");

        PurchaseOrder loaded = PurchaseOrder.load(tag);
        assertEquals("SELLER_SHIP", loaded.fulfillment());
        assertEquals(loaded.targetDockPos(), loaded.targetWarehousePos());
    }

    @Test
    void blankFulfillmentSanitizesToSellerShip() {
        PurchaseOrder order = sample("", new BlockPos(1, 64, 1));
        assertEquals("SELLER_SHIP", order.fulfillment());
    }
}
