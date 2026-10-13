package com.monpai.sailboatmod.market;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MarketStockIdentityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void identityIgnoresCountButIncludesComponents() throws Exception {
        ItemStack bookA = book("A", 10);
        ItemStack bookB = book("B", 9);
        String before = identity(bookA);
        bookA.setCount(8);

        assertFalse(before.isBlank(), "a nonempty stack needs a verifiable identity");
        assertEquals(before, identity(bookA));
        assertNotEquals(before, identity(bookB), "same item and hover name must not hide different components");
    }

    @Test
    void equivalentNestedComponentTagsHaveTheSameIdentityRegardlessOfKeyInsertionOrder() throws Exception {
        CompoundTag first = new CompoundTag();
        CompoundTag firstNested = new CompoundTag();
        firstNested.putInt("a", 1);
        firstNested.putString("z", "value");
        first.put("nested", firstNested);
        first.putInt("other", 2);
        CompoundTag second = new CompoundTag();
        second.putInt("other", 2);
        CompoundTag secondNested = new CompoundTag();
        secondNested.putString("z", "value");
        secondNested.putInt("a", 1);
        second.put("nested", secondNested);
        ItemStack left = new ItemStack(Items.ENCHANTED_BOOK, 10);
        left.set(DataComponents.CUSTOM_DATA, CustomData.of(first));
        ItemStack right = new ItemStack(Items.ENCHANTED_BOOK, 9);
        right.set(DataComponents.CUSTOM_DATA, CustomData.of(second));

        assertTrue(ItemStack.isSameItemSameComponents(left, right));
        assertFalse(identity(left).isBlank());
        assertEquals(identity(left), identity(right));
    }

    @Test
    void emptyStockCannotSupplyAnExpectedIdentity() throws Exception {
        assertEquals("", identity(ItemStack.EMPTY));
    }

    @Test
    void distinctUtf16ComponentStringsCannotCollapseToTheSameIdentity() {
        ItemStack first = book("\uD800", 1);
        ItemStack second = book("\uD801", 1);
        ItemStack questionMark = book("?", 1);

        assertFalse(ItemStack.isSameItemSameComponents(first, second));
        assertNotEquals(identity(first), identity(second));
        assertNotEquals(identity(first), identity(questionMark));
    }

    private static ItemStack book(String variant, int count) {
        ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK, count);
        CompoundTag tag = new CompoundTag();
        tag.putString("variant", variant);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    private static String identity(ItemStack stack) {
        return MarketStockIdentity.fingerprint(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), stack);
    }
}
