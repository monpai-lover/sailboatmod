package com.monpai.sailboatmod.registry;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.item.CommandBatonItem;
import com.monpai.sailboatmod.item.DescribedBlockItem;
import com.monpai.sailboatmod.item.NationCoreItem;
import com.monpai.sailboatmod.item.RouteBookItem;
import com.monpai.sailboatmod.item.SailboatItem;
import com.monpai.sailboatmod.item.StandingAndWallFlagBlockItem;
import com.monpai.sailboatmod.item.TownCoreItem;
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

    public static final RegistryObject<Item> TOWN_CORE_ITEM = ITEMS.register(
            "town_core",
            () -> new TownCoreItem(ModBlocks.TOWN_CORE_BLOCK.get(), new Item.Properties().stacksTo(1), "item.sailboatmod.town_core.desc")
    );

    public static final RegistryObject<Item> NATION_CORE_ITEM = ITEMS.register(
            "nation_core",
            () -> new NationCoreItem(ModBlocks.NATION_CORE_BLOCK.get(), new Item.Properties().stacksTo(1), "item.sailboatmod.nation_core.desc")
    );

    public static final RegistryObject<Item> BANK_ITEM = ITEMS.register(
            "bank_block",
            () -> new DescribedBlockItem(ModBlocks.BANK_BLOCK.get(), new Item.Properties(), "item.sailboatmod.bank_block.desc")
    );

    public static final RegistryObject<Item> NATION_FLAG_ITEM = ITEMS.register(
            "nation_flag",
            () -> new StandingAndWallFlagBlockItem(ModBlocks.NATION_FLAG_BLOCK.get(), ModBlocks.WALL_NATION_FLAG_BLOCK.get(), new Item.Properties().stacksTo(16), "item.sailboatmod.nation_flag.desc")
    );

    public static final RegistryObject<Item> TOWN_FLAG_ITEM = ITEMS.register(
            "town_flag",
            () -> new StandingAndWallFlagBlockItem(ModBlocks.TOWN_FLAG_BLOCK.get(), ModBlocks.WALL_TOWN_FLAG_BLOCK.get(), new Item.Properties().stacksTo(16), "item.sailboatmod.town_flag.desc")
    );

    public static final RegistryObject<Item> BANK_CONSTRUCTOR_ITEM = ITEMS.register(
            "bank_constructor",
            () -> new BankConstructorItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<Item> COTTAGE_ITEM = ITEMS.register(
            "cottage",
            () -> new DescribedBlockItem(ModBlocks.COTTAGE_BLOCK.get(), new Item.Properties(), "item.sailboatmod.cottage.desc")
    );

    public static final RegistryObject<Item> BAR_ITEM = ITEMS.register(
            "bar",
            () -> new DescribedBlockItem(ModBlocks.BAR_BLOCK.get(), new Item.Properties(), "item.sailboatmod.bar.desc")
    );

    public static final RegistryObject<Item> BARRACKS_ITEM = ITEMS.register(
            "barracks",
            () -> new DescribedBlockItem(ModBlocks.BARRACKS_BLOCK.get(), new Item.Properties(), "item.sailboatmod.barracks.desc")
    );

    public static final RegistryObject<Item> COMMAND_BATON_ITEM = ITEMS.register(
            "command_baton",
            () -> new CommandBatonItem(new Item.Properties().stacksTo(1))
    );

    public static final RegistryObject<Item> WORKSTATION_ITEM = ITEMS.register(
            "workstation",
            () -> new DescribedBlockItem(ModBlocks.WORKSTATION_BLOCK.get(), new Item.Properties(), "item.sailboatmod.workstation.desc")
    );

    public static final RegistryObject<Item> SCHOOL_ITEM = ITEMS.register(
            "school",
            () -> new DescribedBlockItem(ModBlocks.SCHOOL_BLOCK.get(), new Item.Properties(), "item.sailboatmod.school.desc")
    );

    private ModItems() {
    }
}
