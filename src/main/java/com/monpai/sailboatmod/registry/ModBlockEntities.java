package com.monpai.sailboatmod.registry;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.BankBlockEntity;
import com.monpai.sailboatmod.block.entity.BarBlockEntity;
import com.monpai.sailboatmod.block.entity.BarracksBlockEntity;
import com.monpai.sailboatmod.block.entity.CottageBlockEntity;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.block.entity.NationCoreBlockEntity;
import com.monpai.sailboatmod.block.entity.NationFlagBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.block.entity.TownFlagBlockEntity;
import com.monpai.sailboatmod.block.entity.TownCoreBlockEntity;
import com.monpai.sailboatmod.block.entity.TownWarehouseBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, SailboatMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DockBlockEntity>> DOCK_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "dock",
            () -> BlockEntityType.Builder.of(DockBlockEntity::new, ModBlocks.DOCK_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MarketBlockEntity>> MARKET_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "market",
            () -> BlockEntityType.Builder.of(MarketBlockEntity::new, ModBlocks.MARKET_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PostStationBlockEntity>> POST_STATION_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "post_station",
            () -> BlockEntityType.Builder.of(PostStationBlockEntity::new, ModBlocks.POST_STATION_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TownWarehouseBlockEntity>> TOWN_WAREHOUSE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "town_warehouse",
            () -> BlockEntityType.Builder.of(TownWarehouseBlockEntity::new, ModBlocks.TOWN_WAREHOUSE_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TownCoreBlockEntity>> TOWN_CORE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "town_core",
            () -> BlockEntityType.Builder.of(TownCoreBlockEntity::new, ModBlocks.TOWN_CORE_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NationCoreBlockEntity>> NATION_CORE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "nation_core",
            () -> BlockEntityType.Builder.of(NationCoreBlockEntity::new, ModBlocks.NATION_CORE_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NationFlagBlockEntity>> NATION_FLAG_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "nation_flag",
            () -> BlockEntityType.Builder.of(NationFlagBlockEntity::new, ModBlocks.NATION_FLAG_BLOCK.get(), ModBlocks.WALL_NATION_FLAG_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TownFlagBlockEntity>> TOWN_FLAG_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "town_flag",
            () -> BlockEntityType.Builder.of(TownFlagBlockEntity::new, ModBlocks.TOWN_FLAG_BLOCK.get(), ModBlocks.WALL_TOWN_FLAG_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BankBlockEntity>> BANK_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "bank_block",
            () -> BlockEntityType.Builder.of(BankBlockEntity::new, ModBlocks.BANK_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CottageBlockEntity>> COTTAGE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "cottage",
            () -> BlockEntityType.Builder.of(CottageBlockEntity::new, ModBlocks.COTTAGE_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BarBlockEntity>> BAR_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "bar",
            () -> BlockEntityType.Builder.of(BarBlockEntity::new, ModBlocks.BAR_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BarracksBlockEntity>> BARRACKS_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "barracks",
            () -> BlockEntityType.Builder.of(BarracksBlockEntity::new, ModBlocks.BARRACKS_BLOCK.get()).build(null)
    );

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.monpai.sailboatmod.block.entity.RegisteredVehicleControllerBlockEntity>> REGISTERED_VEHICLE_CONTROLLER_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "registered_vehicle_controller", () -> BlockEntityType.Builder.of(com.monpai.sailboatmod.block.entity.RegisteredVehicleControllerBlockEntity::new,
                    ModBlocks.VESSEL_REGISTRATION_CONTROLLER.get(), ModBlocks.LAND_VEHICLE_REGISTRATION_CONTROLLER.get(),
                    ModBlocks.AIR_VEHICLE_REGISTRATION_CONTROLLER.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.monpai.sailboatmod.block.entity.AirportBlockEntity>> AIRPORT_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "airport", () -> BlockEntityType.Builder.of(com.monpai.sailboatmod.block.entity.AirportBlockEntity::new, ModBlocks.AIRPORT.get()).build(null));

    private ModBlockEntities() {
    }
}
