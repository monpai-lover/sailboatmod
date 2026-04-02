package com.monpai.sailboatmod.market.commodity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

public final class CommodityKeyResolver {
    private static final String FALLBACK_KEY = "minecraft:air";

    private CommodityKeyResolver() {
    }

    public static String resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return FALLBACK_KEY;
        }
        return resolve(stack.getItem());
    }

    public static String resolve(Item item) {
        Item safeItem = item == null ? Items.AIR : item;
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(safeItem);
        return key == null ? FALLBACK_KEY : key.toString();
    }

    public static String resolve(BlockState state) {
        if (state == null) {
            return FALLBACK_KEY;
        }
        return resolve(state.getBlock().asItem());
    }

    public static ItemStack normalizedTemplate(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }
}
