package com.example.examplemod.registry;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.block.DockBlock;
import com.example.examplemod.block.MarketBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, SailboatMod.MODID);

    public static final RegistryObject<Block> DOCK_BLOCK = BLOCKS.register(
            "dock",
            () -> new DockBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD))
    );

    public static final RegistryObject<Block> MARKET_BLOCK = BLOCKS.register(
            "market",
            () -> new MarketBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(3.0F).sound(SoundType.WOOD))
    );

    private ModBlocks() {
    }
}
