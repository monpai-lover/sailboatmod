package com.monpai.sailboatmod;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.monpai.sailboatmod.registry.ModBlocks;
import com.monpai.sailboatmod.registry.ModCreativeTabs;
import com.monpai.sailboatmod.registry.ModEntities;
import com.monpai.sailboatmod.registry.ModItems;
import com.monpai.sailboatmod.registry.ModMenus;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import software.bernie.geckolib.GeckoLib;

@Mod(SailboatMod.MODID)
public class SailboatMod {
    public static final String MODID = "sailboatmod";

    public SailboatMod() {
        GeckoLib.initialize();

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModEntities.ENTITY_TYPES.register(modEventBus);
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
        ModMenus.MENUS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModNetwork.register();
    }
}
