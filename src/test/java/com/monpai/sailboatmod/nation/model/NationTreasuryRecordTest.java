package com.monpai.sailboatmod.nation.model;

import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NationTreasuryRecordTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void depositUsesExistingEmptySlots() {
        NationTreasuryRecord treasury = NationTreasuryRecord.empty("alpha");

        assertEquals(65, treasury.addItem(new ItemStack(Items.DIAMOND, 65), "Alice"));

        assertEquals(NationTreasuryRecord.TREASURY_SLOTS, treasury.visibleSlotCount());
        assertEquals(64, treasury.items().get(0).getCount());
        assertEquals(1, treasury.items().get(1).getCount());
        assertEquals("Alice", treasury.getDepositor(0));
        assertEquals("Alice", treasury.getDepositor(1));
    }

    @Test
    void fullTreasuryCanGrowAndShrinkBeyondInitialSlots() {
        NationTreasuryRecord treasury = NationTreasuryRecord.empty("alpha");
        for (int slot = 0; slot < NationTreasuryRecord.TREASURY_SLOTS; slot++) {
            treasury.items().set(slot, new ItemStack(Items.STONE, 64));
        }

        treasury.addItem(new ItemStack(Items.DIAMOND, 1), "Alice");

        assertEquals(NationTreasuryRecord.TREASURY_SLOTS + 1, treasury.visibleSlotCount());
        assertEquals("Alice", treasury.getDepositor(NationTreasuryRecord.TREASURY_SLOTS));
        assertEquals(1, treasury.removeMatching(new ItemStack(Items.DIAMOND), 1).getFirst().getCount());
        assertEquals(NationTreasuryRecord.TREASURY_SLOTS, treasury.visibleSlotCount());
    }

    @Test
    void loadRetainsOverflowSlotsAndRemainsResizable() {
        NonNullList<ItemStack> items = NonNullList.withSize(NationTreasuryRecord.TREASURY_SLOTS + 1, ItemStack.EMPTY);
        items.set(NationTreasuryRecord.TREASURY_SLOTS, new ItemStack(Items.DIAMOND, 1));
        NationTreasuryRecord treasury = new NationTreasuryRecord("alpha", 10L, items,
                new String[items.size()], 500, 1000, 0, 0L);
        treasury.setDepositor(NationTreasuryRecord.TREASURY_SLOTS, "Alice");

        NationTreasuryRecord loaded = NationTreasuryRecord.load(treasury.save());

        assertEquals(10L, loaded.currencyBalance());
        assertEquals("Alice", loaded.getDepositor(NationTreasuryRecord.TREASURY_SLOTS));
        assertEquals(1, loaded.removeMatching(new ItemStack(Items.DIAMOND), 1).getFirst().getCount());
        assertEquals(NationTreasuryRecord.TREASURY_SLOTS, loaded.visibleSlotCount());
        loaded.addItem(new ItemStack(Items.EMERALD, 1), "Bob");
        assertEquals(1, loaded.totalItemUnits());
        assertEquals("Bob", loaded.getDepositor(0));
    }
}
