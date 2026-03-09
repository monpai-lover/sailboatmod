package com.example.examplemod.registry;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.block.entity.DockBlockEntity;
import com.example.examplemod.block.entity.MarketBlockEntity;
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

    private ModBlockEntities() {
    }
}
