package com.example.examplemod.client;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.client.renderer.SailboatEntityRenderer;
import com.example.examplemod.client.renderer.blockentity.NationCoreBlockEntityRenderer;
import com.example.examplemod.client.renderer.blockentity.NationFlagBlockEntityRenderer;
import com.example.examplemod.client.renderer.blockentity.TownCoreBlockEntityRenderer;
import com.example.examplemod.client.renderer.blockentity.TownFlagBlockEntityRenderer;
import com.example.examplemod.client.screen.DockScreen;
import com.example.examplemod.client.screen.MarketScreen;
import com.example.examplemod.registry.ModBlockEntities;
import com.example.examplemod.registry.ModEntities;
import com.example.examplemod.registry.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SAILBOAT.get(), SailboatEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.NATION_CORE_BLOCK_ENTITY.get(), NationCoreBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.NATION_FLAG_BLOCK_ENTITY.get(), NationFlagBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.TOWN_CORE_BLOCK_ENTITY.get(), TownCoreBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.TOWN_FLAG_BLOCK_ENTITY.get(), TownFlagBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MenuScreens.register(ModMenus.DOCK_MENU.get(), DockScreen::new);
            MenuScreens.register(ModMenus.MARKET_MENU.get(), MarketScreen::new);
        });
    }

    private ClientEvents() {
    }
}