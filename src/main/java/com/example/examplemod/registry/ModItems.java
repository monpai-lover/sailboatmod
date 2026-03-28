package com.example.examplemod.registry;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.item.DescribedBlockItem;
import com.example.examplemod.item.RouteBookItem;
import com.example.examplemod.item.SailboatItem;
import net.minecraft.world.item.Item;
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
            () -> new DescribedBlockItem(ModBlocks.DOCK_BLOCK.get(), new Item.Properties(), "item.sailboatmod.dock.desc")
    );

    public static final RegistryObject<Item> MARKET_ITEM = ITEMS.register(
            "market",
            () -> new DescribedBlockItem(ModBlocks.MARKET_BLOCK.get(), new Item.Properties(), "item.sailboatmod.market.desc")
    );

    public static final RegistryObject<Item> NATION_CORE_ITEM = ITEMS.register(
            "nation_core",
            () -> new DescribedBlockItem(ModBlocks.NATION_CORE_BLOCK.get(), new Item.Properties().stacksTo(1), "item.sailboatmod.nation_core.desc")
    );

    public static final RegistryObject<Item> NATION_FLAG_ITEM = ITEMS.register(
            "nation_flag",
            () -> new DescribedBlockItem(ModBlocks.NATION_FLAG_BLOCK.get(), new Item.Properties().stacksTo(16), "item.sailboatmod.nation_flag.desc")
    );

    private ModItems() {
    }
}
