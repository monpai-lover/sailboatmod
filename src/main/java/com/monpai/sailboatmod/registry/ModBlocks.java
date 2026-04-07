package com.monpai.sailboatmod.registry;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.block.BankBlock;
import com.monpai.sailboatmod.block.BarBlock;
import com.monpai.sailboatmod.block.BarracksBlock;
import com.monpai.sailboatmod.block.CottageBlock;
import com.monpai.sailboatmod.block.DockBlock;
import com.monpai.sailboatmod.block.MarketBlock;
import com.monpai.sailboatmod.block.NationCoreBlock;
import com.monpai.sailboatmod.block.NationFlagBlock;
import com.monpai.sailboatmod.block.PostStationBlock;
import com.monpai.sailboatmod.block.SchoolBlock;
import com.monpai.sailboatmod.block.TownFlagBlock;
import com.monpai.sailboatmod.block.TownWarehouseBlock;
import com.monpai.sailboatmod.block.TownCoreBlock;
import com.monpai.sailboatmod.block.WallNationFlagBlock;
import com.monpai.sailboatmod.block.WallTownFlagBlock;
import com.monpai.sailboatmod.block.WorkstationBlock;
import com.monpai.sailboatmod.resident.model.Profession;
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

    public static final RegistryObject<Block> POST_STATION_BLOCK = BLOCKS.register(
            "post_station",
            () -> new PostStationBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(2.8F).sound(SoundType.WOOD))
    );

    public static final RegistryObject<Block> TOWN_WAREHOUSE_BLOCK = BLOCKS.register(
            "town_warehouse",
            () -> new TownWarehouseBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(3.2F).sound(SoundType.WOOD))
    );

    public static final RegistryObject<Block> TOWN_CORE_BLOCK = BLOCKS.register(
            "town_core",
            () -> new TownCoreBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_CYAN).strength(-1.0F, 3600000.0F).sound(SoundType.STONE).noLootTable().pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK))
    );

    public static final RegistryObject<Block> NATION_CORE_BLOCK = BLOCKS.register(
            "nation_core",
            () -> new NationCoreBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(-1.0F, 3600000.0F).sound(SoundType.METAL).noLootTable().pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK))
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

    public static final RegistryObject<Block> BANK_BLOCK = BLOCKS.register(
            "bank_block",
            () -> new BankBlock(BlockBehaviour.Properties.of().mapColor(MapColor.GOLD).strength(3.0F).sound(SoundType.METAL))
    );

    public static final RegistryObject<Block> COTTAGE_BLOCK = BLOCKS.register(
            "cottage",
            () -> new CottageBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD))
    );

    public static final RegistryObject<Block> BAR_BLOCK = BLOCKS.register(
            "bar",
            () -> new BarBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).strength(3.0F).sound(SoundType.WOOD))
    );

    public static final RegistryObject<Block> BARRACKS_BLOCK = BLOCKS.register(
            "barracks",
            () -> new BarracksBlock(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(3.5F).sound(SoundType.STONE))
    );

    public static final RegistryObject<Block> WALL_TOWN_FLAG_BLOCK = BLOCKS.register(
            "wall_town_flag",
            () -> new WallTownFlagBlock(BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_CYAN).strength(2.0F).sound(SoundType.WOOL).noOcclusion().dropsLike(TOWN_FLAG_BLOCK.get()))
    );

    public static final RegistryObject<Block> WORKSTATION_BLOCK = BLOCKS.register(
            "workstation",
            () -> new WorkstationBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.5F).sound(SoundType.WOOD), Profession.UNEMPLOYED, 1)
    );

    public static final RegistryObject<Block> SCHOOL_BLOCK = BLOCKS.register(
            "school",
            () -> new SchoolBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(3.0F).sound(SoundType.WOOD))
    );

    private ModBlocks() {
    }
}
