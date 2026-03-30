package com.monpai.sailboatmod.registry;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.entity.BankBlockEntity;
import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.block.entity.NationCoreBlockEntity;
import com.monpai.sailboatmod.block.entity.NationFlagBlockEntity;
import com.monpai.sailboatmod.block.entity.TownFlagBlockEntity;
import com.monpai.sailboatmod.block.entity.TownCoreBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, SailboatMod.MODID);

    public static final RegistryObject<BlockEntityType<DockBlockEntity>> DOCK_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "dock",
            () -> BlockEntityType.Builder.of(DockBlockEntity::new, ModBlocks.DOCK_BLOCK.get()).build(null)
    );

    public static final RegistryObject<BlockEntityType<MarketBlockEntity>> MARKET_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "market",
            () -> BlockEntityType.Builder.of(MarketBlockEntity::new, ModBlocks.MARKET_BLOCK.get()).build(null)
    );

    public static final RegistryObject<BlockEntityType<TownCoreBlockEntity>> TOWN_CORE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "town_core",
            () -> BlockEntityType.Builder.of(TownCoreBlockEntity::new, ModBlocks.TOWN_CORE_BLOCK.get()).build(null)
    );

    public static final RegistryObject<BlockEntityType<NationCoreBlockEntity>> NATION_CORE_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "nation_core",
            () -> BlockEntityType.Builder.of(NationCoreBlockEntity::new, ModBlocks.NATION_CORE_BLOCK.get()).build(null)
    );

    public static final RegistryObject<BlockEntityType<NationFlagBlockEntity>> NATION_FLAG_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "nation_flag",
            () -> BlockEntityType.Builder.of(NationFlagBlockEntity::new, ModBlocks.NATION_FLAG_BLOCK.get(), ModBlocks.WALL_NATION_FLAG_BLOCK.get()).build(null)
    );

    public static final RegistryObject<BlockEntityType<TownFlagBlockEntity>> TOWN_FLAG_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "town_flag",
            () -> BlockEntityType.Builder.of(TownFlagBlockEntity::new, ModBlocks.TOWN_FLAG_BLOCK.get(), ModBlocks.WALL_TOWN_FLAG_BLOCK.get()).build(null)
    );

    public static final RegistryObject<BlockEntityType<BankBlockEntity>> BANK_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "bank_block",
            () -> BlockEntityType.Builder.of(BankBlockEntity::new, ModBlocks.BANK_BLOCK.get()).build(null)
    );

    private ModBlockEntities() {
    }
}