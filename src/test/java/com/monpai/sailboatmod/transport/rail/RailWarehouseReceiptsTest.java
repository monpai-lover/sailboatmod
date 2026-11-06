package com.monpai.sailboatmod.transport.rail;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.monpai.sailboatmod.transport.rail.RailWarehouseReceipts.Result.*;
import static org.junit.jupiter.api.Assertions.*;

class RailWarehouseReceiptsTest {
    private static final String ORDER = "12345678123443218123123456789abc";
    private static final String OTHER_ORDER = "12345678123443218123123456789abd";
    private static final UUID BUYER = new UUID(0, 1);
    private static final UUID OTHER_BUYER = new UUID(0, 2);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void savedReceiptPreventsSecondInsertionEvenAfterBuyerWithdrawsCargo() {
        var receipts = RailWarehouseReceipts.load(null);
        AtomicInteger cargoInWarehouse = new AtomicInteger();
        List<ItemStack> cargo = wheat(4);
        assertEquals(INSERTED, receipts.insertOnce(ORDER, BUYER, cargo, () -> cargoInWarehouse.addAndGet(4) == 4));
        assertEquals(4, cargoInWarehouse.get());

        cargoInWarehouse.set(0);
        var loaded = RailWarehouseReceipts.load(receipts.save());
        assertTrue(loaded.hasReceipt(ORDER, BUYER, 4));
        assertEquals(ALREADY_RECEIVED, loaded.insertOnce(ORDER, BUYER, cargo, () -> cargoInWarehouse.incrementAndGet() > 0));
        assertEquals(0, cargoInWarehouse.get());
    }

    @Test
    void receiptCannotAuthorizeDifferentBuyerOrQuantity() {
        var receipts = RailWarehouseReceipts.load(null);
        assertEquals(INSERTED, receipts.insertOnce(ORDER, BUYER, wheat(4), () -> true));
        assertFalse(receipts.hasReceipt(ORDER, BUYER, 3));
        assertFalse(receipts.hasReceipt(ORDER, OTHER_BUYER, 4));
        assertEquals(REJECTED, receipts.insertOnce(ORDER, BUYER, wheat(3), RailWarehouseReceiptsTest::unexpectedInsertion));
        assertEquals(REJECTED, receipts.insertOnce(ORDER, OTHER_BUYER, wheat(4), RailWarehouseReceiptsTest::unexpectedInsertion));
    }

    @Test
    void failedWarehouseInsertionLeavesNoReceiptAndCanBeRetried() {
        var receipts = RailWarehouseReceipts.load(null);
        assertEquals(REJECTED, receipts.insertOnce(ORDER, BUYER, wheat(4), () -> false));
        assertFalse(receipts.hasReceipt(ORDER, BUYER, 4));
        assertNull(receipts.save());
        assertEquals(INSERTED, receipts.insertOnce(ORDER, BUYER, wheat(4), () -> true));
        assertTrue(receipts.hasReceipt(ORDER, BUYER, 4));
    }

    @Test
    void validatesOrderIdentityAndRejectsEvenMalformedFreightMarkers() {
        var receipts = RailWarehouseReceipts.load(null);
        ItemStack marked = RailCargoCustody.mark(new ItemStack(Items.WHEAT, 4), UUID.randomUUID());
        ItemStack malformed = new ItemStack(Items.WHEAT, 4);
        CompoundTag tag = new CompoundTag();
        tag.putString("sailboatmod:rail_freight", "invalid custody data");
        malformed.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        assertEquals(REJECTED, receipts.insertOnce("not-a-shipping-order", BUYER, wheat(4), RailWarehouseReceiptsTest::unexpectedInsertion));
        assertEquals(REJECTED, receipts.insertOnce(ORDER, BUYER, List.of(marked), RailWarehouseReceiptsTest::unexpectedInsertion));
        assertEquals(REJECTED, receipts.insertOnce(ORDER, BUYER, List.of(malformed), RailWarehouseReceiptsTest::unexpectedInsertion));
        assertFalse(receipts.hasReceipt(ORDER, BUYER, 4));
    }

    @Test
    void corruptSavedReceiptDisablesAllRailWritesAndSurvivesResave() {
        var receipts = RailWarehouseReceipts.load(null);
        assertEquals(INSERTED, receipts.insertOnce(ORDER, BUYER, wheat(4), () -> true));
        CompoundTag damaged = (CompoundTag) receipts.save();
        damaged.getList("Entries", Tag.TAG_COMPOUND).getCompound(0).putString("Quantity", "4");
        Tag original = damaged.copy();

        var rejected = RailWarehouseReceipts.load(damaged);
        assertFalse(rejected.hasReceipt(ORDER, BUYER, 4));
        assertEquals(REJECTED, rejected.insertOnce(OTHER_ORDER, BUYER, wheat(4), RailWarehouseReceiptsTest::unexpectedInsertion));
        damaged.putInt("Version", -5);
        assertEquals(original, rejected.save());
    }

    private static List<ItemStack> wheat(int quantity) {
        return List.of(new ItemStack(Items.WHEAT, quantity));
    }

    private static boolean unexpectedInsertion() {
        fail("Invalid or repeated receipt must not invoke the warehouse write");
        return false;
    }
}
