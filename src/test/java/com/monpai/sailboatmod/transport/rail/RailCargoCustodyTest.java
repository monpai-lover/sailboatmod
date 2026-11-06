package com.monpai.sailboatmod.transport.rail;

import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RailCargoCustodyTest {
    private static final UUID CUSTODY = UUID.fromString("12345678-1234-4321-8123-123456789abc");
    private static final UUID OTHER_CUSTODY = UUID.fromString("12345678-1234-4321-8123-123456789abd");

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void roundTripPreservesOriginalComponentsAndQuantityWithoutChangingInput() {
        ItemStack cargo = namedWheat(7, "seller-lot");
        ItemStack original = cargo.copy();

        ItemStack marked = RailCargoCustody.mark(cargo, CUSTODY);
        assertTrue(ItemStack.isSameItemSameComponents(original, cargo));
        assertEquals(7, cargo.getCount());
        assertEquals(7, marked.getCount());
        assertEquals(original.get(DataComponents.CUSTOM_NAME), marked.get(DataComponents.CUSTOM_NAME));
        assertFalse(ItemStack.isSameItemSameComponents(original, marked));

        ItemStack restored = RailCargoCustody.restore(marked, CUSTODY);
        assertNotNull(restored);
        assertEquals(7, restored.getCount());
        assertTrue(ItemStack.isSameItemSameComponents(original, restored));
        assertEquals(original.get(DataComponents.CUSTOM_DATA), restored.get(DataComponents.CUSTOM_DATA));
        assertNull(RailCargoCustody.restore(cargo, CUSTODY));
    }

    @Test
    void restorationDistinguishesAbsentCustomDataFromExplicitlyEmptyCustomData() {
        ItemStack absent = new ItemStack(Items.WHEAT, 3);
        ItemStack empty = absent.copy();
        empty.set(DataComponents.CUSTOM_DATA, CustomData.of(new CompoundTag()));

        ItemStack restoredAbsent = RailCargoCustody.restore(RailCargoCustody.mark(absent, CUSTODY), CUSTODY);
        ItemStack restoredEmpty = RailCargoCustody.restore(RailCargoCustody.mark(empty, CUSTODY), CUSTODY);

        assertNotNull(restoredAbsent);
        assertNotNull(restoredEmpty);
        assertNull(restoredAbsent.get(DataComponents.CUSTOM_DATA));
        assertEquals(empty.get(DataComponents.CUSTOM_DATA), restoredEmpty.get(DataComponents.CUSTOM_DATA));
        assertFalse(ItemStack.isSameItemSameComponents(restoredAbsent, restoredEmpty));
    }

    @Test
    void exactMatchAcceptsPhysicalSlotReorderingAndStackSplits() {
        ItemStack wheat = namedWheat(7, "seller-lot");
        ItemStack carrot = new ItemStack(Items.CARROT, 2);
        List<ItemStack> actual = List.of(
                RailCargoCustody.mark(wheat.copyWithCount(2), CUSTODY),
                RailCargoCustody.mark(carrot, CUSTODY),
                RailCargoCustody.mark(wheat.copyWithCount(5), CUSTODY));

        assertTrue(RailCargoCustody.matchesExact(actual, List.of(wheat, carrot), CUSTODY));
    }

    @Test
    void sameNameDoesNotSubstituteForExactItemComponents() {
        ItemStack expected = namedWheat(4, "seller-lot");
        ItemStack otherLot = namedWheat(4, "foreign-lot");
        ItemStack otherItem = new ItemStack(Items.CARROT, 4);
        otherItem.applyComponents(expected.getComponentsPatch());

        assertFalse(RailCargoCustody.matchesExact(
                List.of(RailCargoCustody.mark(otherLot, CUSTODY)), List.of(expected), CUSTODY));
        assertFalse(RailCargoCustody.matchesExact(
                List.of(RailCargoCustody.mark(otherItem, CUSTODY)), List.of(expected), CUSTODY));
    }

    @Test
    void foreignOrderMarkerAndUnmarkedIdenticalCargoFailClosed() {
        ItemStack wheat = namedWheat(4, "seller-lot");
        ItemStack foreign = RailCargoCustody.mark(wheat, OTHER_CUSTODY);
        ItemStack ownedPart = RailCargoCustody.mark(wheat.copyWithCount(3), CUSTODY);

        assertNull(RailCargoCustody.restore(foreign, CUSTODY));
        assertFalse(RailCargoCustody.matchesExact(List.of(foreign), List.of(wheat), CUSTODY));
        assertFalse(RailCargoCustody.matchesExact(
                List.of(ownedPart, wheat.copyWithCount(1)), List.of(wheat), CUSTODY));
    }

    @Test
    void exactMatchRejectsShortfallAndSurplus() {
        ItemStack wheat = namedWheat(4, "seller-lot");
        ItemStack shortage = RailCargoCustody.mark(wheat.copyWithCount(3), CUSTODY);
        ItemStack surplus = RailCargoCustody.mark(wheat.copyWithCount(5), CUSTODY);

        assertFalse(RailCargoCustody.matchesExact(List.of(shortage), List.of(wheat), CUSTODY));
        assertFalse(RailCargoCustody.matchesExact(List.of(surplus), List.of(wheat), CUSTODY));
    }

    private static ItemStack namedWheat(int quantity, String lot) {
        ItemStack cargo = new ItemStack(Items.WHEAT, quantity);
        cargo.set(DataComponents.CUSTOM_NAME, Component.literal("Station Cargo"));
        CompoundTag original = new CompoundTag();
        original.putString("lot", lot);
        CompoundTag origin = new CompoundTag();
        origin.putInt("harvest", 127);
        original.put("origin", origin);
        cargo.set(DataComponents.CUSTOM_DATA, CustomData.of(original));
        return cargo;
    }
}
