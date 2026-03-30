package com.monpai.sailboatmod.registry;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.DockBlock;
import com.monpai.sailboatmod.block.MarketBlock;
import com.monpai.sailboatmod.block.NationCoreBlock;
import com.monpai.sailboatmod.block.NationFlagBlock;
import com.monpai.sailboatmod.block.TownFlagBlock;
import com.monpai.sailboatmod.block.TownCoreBlock;
import com.monpai.sailboatmod.block.WallNationFlagBlock;
import com.monpai.sailboatmod.block.WallTownFlagBlock;
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

    public static final RegistryObject<Block> TOWN_CORE_BLOCK = BLOCKS.register(
            "town_core",
            () -> new TownCoreBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_CYAN).strength(4.0F).sound(SoundType.STONE).requiresCorrectToolForDrops())
    );

    public static final RegistryObject<Block> NATION_CORE_BLOCK = BLOCKS.register(
            "nation_core",
            () -> new NationCoreBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(4.0F).sound(SoundType.METAL).requiresCorrectToolForDrops())
    );

    public static final RegistryObject<Block> NATION_FLAG_BLOCK = BLOCKS.register(
            "nation_flag",
            () -> new NationFlagBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(2.0F).sound(SoundType.WOOL).noOcclusion())
    );

    public static final RegistryObject<Block> TOWN_FLAG_BLOCK = BLOCKS.register(
            "town_flag",
            () -> new TownFlagBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_CYAN).strength(2.0F).sound(SoundType.WOOL).noOcclusion())
    );

    public static final RegistryObject<Block> WALL_NATION_FLAG_BLOCK = BLOCKS.register(
            "wall_nation_flag",
            () -> new WallNationFlagBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(2.0F).sound(SoundType.WOOL).noOcclusion().dropsLike(NATION_FLAG_BLOCK.get()))
    );

    public static final RegistryObject<Block> WALL_TOWN_FLAG_BLOCK = BLOCKS.register(
            "wall_town_flag",
            () -> new WallTownFlagBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_CYAN).strength(2.0F).sound(SoundType.WOOL).noOcclusion().dropsLike(TOWN_FLAG_BLOCK.get()))
    );

    private ModBlocks() {
    }
}