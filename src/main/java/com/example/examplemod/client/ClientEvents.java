package com.example.examplemod.client;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.client.screen.DockScreen;
import com.example.examplemod.client.screen.MarketScreen;
import com.example.examplemod.client.renderer.SailboatEntityRenderer;
import com.example.examplemod.registry.ModEntities;
import com.example.examplemod.registry.ModMenus;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SAILBOAT.get(), SailboatEntityRenderer::new);
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
