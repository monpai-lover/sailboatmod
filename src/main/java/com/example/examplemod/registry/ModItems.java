package com.example.examplemod.registry;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.item.RouteBookItem;
import com.example.examplemod.item.SailboatItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, SailboatMod.MODID);

    public static final RegistryObject<Item> SAILBOAT_ITEM = ITEMS.register(
            "sailboat",
            () -> new SailboatItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<Item> ROUTE_BOOK_ITEM = ITEMS.register(
            "route_book",
            () -> new RouteBookItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<Item> DOCK_ITEM = ITEMS.register(
            "dock",
            () -> new BlockItem(ModBlocks.DOCK_BLOCK.get(), new Item.Properties())
    );

    private ModItems() {
    }
}
