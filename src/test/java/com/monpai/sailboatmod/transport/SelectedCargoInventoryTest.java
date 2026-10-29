package com.monpai.sailboatmod.transport;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SelectedCargoInventoryTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void capacitySimulationAccumulatesEveryRequestedStackWithoutMutatingRealCargo() {
        var hold = new ItemStackHandler(1);
        var cargo = new SelectedCargoInventory(List.of(hold));
        assertFalse(cargo.canLoad(List.of(new ItemStack(Items.STONE, 40), new ItemStack(Items.STONE, 40))));
        assertTrue(hold.getStackInSlot(0).isEmpty());
        assertTrue(cargo.canLoad(List.of(new ItemStack(Items.STONE, 32), new ItemStack(Items.STONE, 32))));
    }

    @Test void rollbackRemovesOnlyThisDispatchAndPreservesEarlierItems() {
        var hold = new ItemStackHandler(2);
        hold.setStackInSlot(0, new ItemStack(Items.DIAMOND, 7));
        var cargo = new SelectedCargoInventory(List.of(hold));
        CargoLoadReceipt receipt = cargo.load(List.of(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.GOLD_INGOT, 9)));
        assertNotNull(receipt); assertEquals(12, hold.getStackInSlot(0).getCount());
        receipt.rollback(); receipt.rollback();
        assertEquals(7, hold.getStackInSlot(0).getCount()); assertTrue(hold.getStackInSlot(1).isEmpty());
    }

    @Test void committedReceiptCannotDrainLaterCargo() {
        var hold = new ItemStackHandler(1);
        var cargo = new SelectedCargoInventory(List.of(hold));
        var receipt = cargo.load(List.of(new ItemStack(Items.DIAMOND, 5)));
        assertNotNull(receipt); receipt.commit();
        hold.insertItem(0, new ItemStack(Items.DIAMOND, 3), false); receipt.rollback();
        assertEquals(8, hold.getStackInSlot(0).getCount());
    }

    @Test void rejectedCapacityDoesNotPartiallyFillOtherSlots() {
        var hold = new ItemStackHandler(1);
        hold.setStackInSlot(0, new ItemStack(Items.DIAMOND, 7));
        var cargo = new SelectedCargoInventory(List.of(hold));
        assertNull(cargo.load(List.of(new ItemStack(Items.DIAMOND, 60), new ItemStack(Items.GOLD_INGOT, 1))));
        assertEquals(7, hold.getStackInSlot(0).getCount());
    }

    @Test void realInsertionFailureRollsBackAllEarlierAcceptedItems() {
        var first = new ItemStackHandler(1);
        var rejecting = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                return simulate ? super.insertItem(slot, stack, true) : stack;
            }
        };
        first.setStackInSlot(0, new ItemStack(Items.DIAMOND, 7));
        var cargo = new SelectedCargoInventory(List.of(first, rejecting));
        assertNull(cargo.load(List.of(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.GOLD_INGOT, 1))));
        assertEquals(7, first.getStackInSlot(0).getCount()); assertTrue(rejecting.getStackInSlot(0).isEmpty());
    }

    @Test void itemFiltersAndPerItemStackLimitsAreHonoured() {
        var filtered = new ItemStackHandler(1) {
            @Override public boolean isItemValid(int slot, ItemStack stack) { return stack.is(Items.DIAMOND); }
            @Override public int getSlotLimit(int slot) { return 8; }
        };
        var cargo = new SelectedCargoInventory(List.of(filtered));
        assertFalse(cargo.canLoad(List.of(new ItemStack(Items.GOLD_INGOT, 1))));
        assertFalse(cargo.canLoad(List.of(new ItemStack(Items.DIAMOND, 9))));
        assertTrue(cargo.canLoad(List.of(new ItemStack(Items.DIAMOND, 8))));
    }

    @Test void drainingOnlyTouchesSelectedRealInventories() {
        var selected = new ItemStackHandler(1); var personal = new ItemStackHandler(1);
        selected.setStackInSlot(0, new ItemStack(Items.STONE, 12));
        personal.setStackInSlot(0, new ItemStack(Items.DIAMOND, 7));
        var cargo = new SelectedCargoInventory(List.of(selected));
        assertEquals(12, cargo.drain().getFirst().getCount());
        assertTrue(selected.getStackInSlot(0).isEmpty()); assertEquals(7, personal.getStackInSlot(0).getCount());
    }

    @Test void failedDispatchCanRestoreAnInsertOnlySlotWithoutPretendingExtractionSucceeded() {
        var insertOnly = new ItemStackHandler(1) {
            @Override public ItemStack extractItem(int slot, int amount, boolean simulate) { return ItemStack.EMPTY; }
        };
        insertOnly.setStackInSlot(0, new ItemStack(Items.DIAMOND, 7));
        var rejecting = new ItemStackHandler(1) {
            @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) { return simulate ? super.insertItem(slot, stack, true) : stack; }
        };
        var cargo = new SelectedCargoInventory(List.of(insertOnly, rejecting));
        assertNull(cargo.load(List.of(new ItemStack(Items.DIAMOND, 5), new ItemStack(Items.GOLD_INGOT, 1))));
        assertEquals(7, insertOnly.getStackInSlot(0).getCount(), "failed cargo must not remain after the source refunds it");
    }

    @Test void repeatedIdenticalCapabilityCannotDoubleItsAdvertisedCapacity() {
        var oneSlot = new ItemStackHandler(1);
        assertFalse(new SelectedCargoInventory(List.of(oneSlot, oneSlot)).canLoad(List.of(new ItemStack(Items.STONE, 128))));
    }
}
