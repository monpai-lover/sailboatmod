package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.SailboatMod;
import com.monpai.sailboatmod.client.renderer.SailboatEntityRenderer;
import com.monpai.sailboatmod.client.renderer.CarriageEntityRenderer;
import com.monpai.sailboatmod.client.renderer.BankConstructorPreviewRenderer;
import com.monpai.sailboatmod.client.renderer.resident.ResidentEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.BarBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.BarracksBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.BankBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.CottageBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.NationCoreBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.DockBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.MarketBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.NationFlagBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.PostStationBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.TownCoreBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.TownFlagBlockEntityRenderer;
import com.monpai.sailboatmod.client.renderer.blockentity.TownWarehouseBlockEntityRenderer;
import com.monpai.sailboatmod.client.screen.BankScreen;
import com.monpai.sailboatmod.client.screen.DockScreen;
import com.monpai.sailboatmod.client.screen.MarketScreen;
import com.monpai.sailboatmod.client.screen.PostStationScreen;
import com.monpai.sailboatmod.client.screen.StandardDockScreen;
import com.monpai.sailboatmod.client.screen.WarehouseScreen;
import com.monpai.sailboatmod.client.integration.xaero.SailboatXaeroCompat;
import com.monpai.sailboatmod.registry.ModBlockEntities;
import com.monpai.sailboatmod.registry.ModEntities;
import com.monpai.sailboatmod.registry.ModMenus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = SailboatMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientEvents {
    @SubscribeEvent
    public static void registerClientReloadListeners(RegisterClientReloadListenersEvent event) {
        if (shouldRegisterEmbeddedBlockUiLoader(ModList.get().isLoaded("blockui"))) {
            try {
                event.registerReloadListener((net.minecraft.server.packs.resources.PreparableReloadListener)
                        Class.forName("com.ldtteam.blockui.Loader").getField("INSTANCE").get(null));
            } catch (Exception ignored) {}
        }
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.SAILBOAT.get(), SailboatEntityRenderer::new);
        event.registerEntityRenderer(ModEntities.CARRIAGE.get(), CarriageEntityRenderer::new);
        event.registerEntityRenderer(ModEntities.RESIDENT.get(), ResidentEntityRenderer::new);
        event.registerEntityRenderer(ModEntities.SOLDIER.get(), ResidentEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.DOCK_BLOCK_ENTITY.get(), DockBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.POST_STATION_BLOCK_ENTITY.get(), PostStationBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.MARKET_BLOCK_ENTITY.get(), MarketBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.NATION_CORE_BLOCK_ENTITY.get(), NationCoreBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.NATION_FLAG_BLOCK_ENTITY.get(), NationFlagBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.BANK_BLOCK_ENTITY.get(), BankBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.COTTAGE_BLOCK_ENTITY.get(), CottageBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.BAR_BLOCK_ENTITY.get(), BarBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.BARRACKS_BLOCK_ENTITY.get(), BarracksBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.TOWN_CORE_BLOCK_ENTITY.get(), TownCoreBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.TOWN_FLAG_BLOCK_ENTITY.get(), TownFlagBlockEntityRenderer::new);
        event.registerBlockEntityRenderer(ModBlockEntities.TOWN_WAREHOUSE_BLOCK_ENTITY.get(), TownWarehouseBlockEntityRenderer::new);
    }

    @SubscribeEvent
    public static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.BANK_MENU.get(), BankScreen::new);
        event.register(ModMenus.DOCK_MENU.get(), StandardDockScreen::new);
        event.register(ModMenus.POST_STATION_MENU.get(), PostStationScreen::new);
        event.register(ModMenus.MARKET_MENU.get(), MarketScreen::new);
        event.register(ModMenus.WAREHOUSE_MENU.get(), WarehouseScreen::new);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            SailboatXaeroCompat.onClientSetup();
        });
    }

    static boolean shouldRegisterEmbeddedBlockUiLoader(boolean blockUiModLoaded) {
        return !blockUiModLoaded;
    }

    private ClientEvents() {
    }
}
