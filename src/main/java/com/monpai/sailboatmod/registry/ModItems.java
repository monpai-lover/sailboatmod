package com.monpai.sailboatmod.registry;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.item.BankConstructorItem;
import com.monpai.sailboatmod.item.BuilderHammerItem;
import com.monpai.sailboatmod.item.CarriageItem;
import com.monpai.sailboatmod.item.CommandBatonItem;
import com.monpai.sailboatmod.item.DescribedBlockItem;
import com.monpai.sailboatmod.item.NationCoreItem;
import com.monpai.sailboatmod.item.PostRouteBookItem;
import com.monpai.sailboatmod.item.RoadPlannerItem;
import com.monpai.sailboatmod.item.RouteBookItem;
import com.monpai.sailboatmod.item.RouteAtlasItem;
import com.monpai.sailboatmod.item.SailboatItem;
import com.monpai.sailboatmod.item.StandingAndWallFlagBlockItem;
import com.monpai.sailboatmod.item.TownCoreItem;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, SailboatMod.MODID);

    public static final DeferredHolder<Item, Item> SAILBOAT_ITEM = ITEMS.register(
            "sailboat",
            () -> new SailboatItem(new Item.Properties().stacksTo(1))
    );

    public static final DeferredHolder<Item, Item> ROUTE_BOOK_ITEM = ITEMS.register(
            "route_book",
            () -> new RouteBookItem(new Item.Properties().stacksTo(1))
    );

    public static final DeferredHolder<Item, Item> POST_ROUTE_BOOK_ITEM = ITEMS.register(
            "post_route_book",
            () -> new PostRouteBookItem(new Item.Properties().stacksTo(1))
    );

    public static final DeferredHolder<Item, Item> ROAD_PLANNER_ITEM = ITEMS.register(
            "road_planner",
            () -> new RoadPlannerItem(new Item.Properties().stacksTo(1))
    );

    public static final DeferredHolder<Item, Item> ROUTE_ATLAS_ITEM = ITEMS.register(
            "route_atlas",
            () -> new RouteAtlasItem(new Item.Properties().stacksTo(1))
    );

    public static final DeferredHolder<Item, Item> DOCK_ITEM = ITEMS.register(
            "dock",
            () -> new DescribedBlockItem(ModBlocks.DOCK_BLOCK.get(), new Item.Properties(), "item.sailboatmod.dock.desc")
    );

    public static final DeferredHolder<Item, Item> POST_STATION_ITEM = ITEMS.register(
            "post_station",
            () -> new DescribedBlockItem(ModBlocks.POST_STATION_BLOCK.get(), new Item.Properties(), "item.sailboatmod.post_station.desc")
    );

    public static final DeferredHolder<Item, Item> TOWN_WAREHOUSE_ITEM = ITEMS.register(
            "town_warehouse",
            () -> new DescribedBlockItem(ModBlocks.TOWN_WAREHOUSE_BLOCK.get(), new Item.Properties(), "item.sailboatmod.town_warehouse.desc")
    );

    public static final DeferredHolder<Item, Item> MARKET_ITEM = ITEMS.register(
            "market",
            () -> new DescribedBlockItem(ModBlocks.MARKET_BLOCK.get(), new Item.Properties(), "item.sailboatmod.market.desc")
    );

    public static final DeferredHolder<Item, Item> TOWN_CORE_ITEM = ITEMS.register(
            "town_core",
            () -> new TownCoreItem(ModBlocks.TOWN_CORE_BLOCK.get(), new Item.Properties().stacksTo(1), "item.sailboatmod.town_core.desc")
    );

    public static final DeferredHolder<Item, Item> NATION_CORE_ITEM = ITEMS.register(
            "nation_core",
            () -> new NationCoreItem(ModBlocks.NATION_CORE_BLOCK.get(), new Item.Properties().stacksTo(1), "item.sailboatmod.nation_core.desc")
    );

    public static final DeferredHolder<Item, Item> BANK_ITEM = ITEMS.register(
            "bank_block",
            () -> new DescribedBlockItem(ModBlocks.BANK_BLOCK.get(), new Item.Properties(), "item.sailboatmod.bank_block.desc")
    );

    public static final DeferredHolder<Item, Item> NATION_FLAG_ITEM = ITEMS.register(
            "nation_flag",
            () -> new StandingAndWallFlagBlockItem(ModBlocks.NATION_FLAG_BLOCK.get(), ModBlocks.WALL_NATION_FLAG_BLOCK.get(), new Item.Properties().stacksTo(16), "item.sailboatmod.nation_flag.desc")
    );

    public static final DeferredHolder<Item, Item> TOWN_FLAG_ITEM = ITEMS.register(
            "town_flag",
            () -> new StandingAndWallFlagBlockItem(ModBlocks.TOWN_FLAG_BLOCK.get(), ModBlocks.WALL_TOWN_FLAG_BLOCK.get(), new Item.Properties().stacksTo(16), "item.sailboatmod.town_flag.desc")
    );

    public static final DeferredHolder<Item, Item> BANK_CONSTRUCTOR_ITEM = ITEMS.register(
            "bank_constructor",
            () -> new BankConstructorItem(new Item.Properties().stacksTo(1))
    );

    public static final DeferredHolder<Item, Item> BUILDER_HAMMER_ITEM = ITEMS.register(
            "builder_hammer",
            () -> new BuilderHammerItem(new Item.Properties().stacksTo(1).durability(256))
    );

    public static final DeferredHolder<Item, Item> COTTAGE_ITEM = ITEMS.register(
            "cottage",
            () -> new DescribedBlockItem(ModBlocks.COTTAGE_BLOCK.get(), new Item.Properties(), "item.sailboatmod.cottage.desc")
    );

    public static final DeferredHolder<Item, Item> BAR_ITEM = ITEMS.register(
            "bar",
            () -> new DescribedBlockItem(ModBlocks.BAR_BLOCK.get(), new Item.Properties(), "item.sailboatmod.bar.desc")
    );

    public static final DeferredHolder<Item, Item> BARRACKS_ITEM = ITEMS.register(
            "barracks",
            () -> new DescribedBlockItem(ModBlocks.BARRACKS_BLOCK.get(), new Item.Properties(), "item.sailboatmod.barracks.desc")
    );

    public static final DeferredHolder<Item, Item> COMMAND_BATON_ITEM = ITEMS.register(
            "command_baton",
            () -> new CommandBatonItem(new Item.Properties().stacksTo(1))
    );

    public static final DeferredHolder<Item, Item> WORKSTATION_ITEM = ITEMS.register(
            "workstation",
            () -> new DescribedBlockItem(ModBlocks.WORKSTATION_BLOCK.get(), new Item.Properties(), "item.sailboatmod.workstation.desc")
    );

    public static final DeferredHolder<Item, Item> SCHOOL_ITEM = ITEMS.register(
            "school",
            () -> new DescribedBlockItem(ModBlocks.SCHOOL_BLOCK.get(), new Item.Properties(), "item.sailboatmod.school.desc")
    );

    public static final DeferredHolder<Item, Item> HALF_NUGGET_ITEM = ITEMS.register(
            "half_nugget",
            () -> new Item(new Item.Properties().stacksTo(64))
    );

    /** 紫水晶本位的半单位货币(值 1):2 个合成 1 个原版紫水晶(amethyst_shard,值 2)。照搬 half_nugget。 */
    public static final DeferredHolder<Item, Item> HALF_AMETHYST_ITEM = ITEMS.register(
            "half_amethyst",
            () -> new Item(new Item.Properties().stacksTo(64))
    );

    public static final DeferredHolder<Item, Item> CARRIAGE_ITEM = ITEMS.register(
            "carriage",
            () -> new CarriageItem(new Item.Properties().stacksTo(1))
    );

    public static final DeferredHolder<Item, Item> VESSEL_REGISTRATION_CONTROLLER = ITEMS.register("vessel_registration_controller",
            () -> new DescribedBlockItem(ModBlocks.VESSEL_REGISTRATION_CONTROLLER.get(), new Item.Properties(), "item.sailboatmod.vessel_registration_controller.desc"));
    public static final DeferredHolder<Item, Item> LAND_VEHICLE_REGISTRATION_CONTROLLER = ITEMS.register("land_vehicle_registration_controller",
            () -> new DescribedBlockItem(ModBlocks.LAND_VEHICLE_REGISTRATION_CONTROLLER.get(), new Item.Properties(), "item.sailboatmod.land_vehicle_registration_controller.desc"));

    private ModItems() {
    }
}
