package com.example.examplemod.registry;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.block.entity.DockBlockEntity;
import com.example.examplemod.block.entity.MarketBlockEntity;
import com.example.examplemod.block.entity.NationCoreBlockEntity;
import com.example.examplemod.block.entity.NationFlagBlockEntity;
import com.example.examplemod.block.entity.TownFlagBlockEntity;
import com.example.examplemod.block.entity.TownCoreBlockEntity;
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
            () -> BlockEntityType.Builder.of(NationFlagBlockEntity::new, ModBlocks.NATION_FLAG_BLOCK.get()).build(null)
    );

    public static final RegistryObject<BlockEntityType<TownFlagBlockEntity>> TOWN_FLAG_BLOCK_ENTITY = BLOCK_ENTITIES.register(
            "town_flag",
            () -> BlockEntityType.Builder.of(TownFlagBlockEntity::new, ModBlocks.TOWN_FLAG_BLOCK.get()).build(null)
    );

    private ModBlockEntities() {
    }
}