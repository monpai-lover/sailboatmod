package com.monpai.sailboatmod.compat;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ItemStackNbtTest {
    @Test
    void customDataChangesPersistWithoutMutatingEarlierSnapshots() {
        ItemStack stack = new ItemStack(Items.PAPER);
        ItemStackNbt.updateTag(stack, tag -> tag.putString("SelectedTownId", "town-a"));
        CompoundTag snapshot = ItemStackNbt.tag(stack);

        ItemStackNbt.updateTag(stack, tag -> tag.putString("SelectedTownId", "town-b"));

        assertEquals("town-a", snapshot.getString("SelectedTownId"));
        assertEquals("town-b", ItemStackNbt.tag(stack).getString("SelectedTownId"));
        ItemStackNbt.updateTag(stack, tag -> tag.remove("SelectedTownId"));
        assertFalse(ItemStackNbt.hasTag(stack));
    }

    @Test
    void readsLegacyCargoCountAndCustomRoutesAndWritesModernComponents() {
        var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        CompoundTag legacy = new CompoundTag();
        legacy.putString("id", "minecraft:paper");
        legacy.putByte("Count", (byte) 7);
        CompoundTag custom = new CompoundTag();
        custom.putString("RouteId", "preserved-route");
        legacy.put("tag", custom);

        ItemStack decoded = ItemStackNbt.read(provider, legacy);

        assertEquals(Items.PAPER, decoded.getItem());
        assertEquals(7, decoded.getCount());
        assertEquals("preserved-route", ItemStackNbt.tag(decoded).getString("RouteId"));
        CompoundTag prefix = new CompoundTag();
        prefix.putInt("Slot", 3);
        CompoundTag saved = ItemStackNbt.save(provider, decoded, prefix);
        assertSame(prefix, saved);
        assertEquals(3, prefix.getInt("Slot"));
        ItemStack reloaded = ItemStackNbt.read(provider, saved);
        assertTrue(ItemStack.isSameItemSameComponents(decoded, reloaded));
        assertEquals(7, reloaded.getCount());
        assertFalse(saved.contains("Count"));
        assertEquals(7, legacy.getByte("Count"));
    }
}
