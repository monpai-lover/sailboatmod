package com.monpai.sailboatmod.market.web;

import com.monpai.sailboatmod.market.web.map.MarketWebMapRenderService;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

@EventBusSubscriber(modid = SailboatMarketWebAddon.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class MarketWebEvents {
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        // After a renderer upgrade, chunks drawn by the old renderer are cleared once (before the web server serves
        // tiles, and before rendering starts writing them) and repainted in the background.
        int cleared = com.monpai.sailboatmod.market.web.map.MarketWebMapTileCache.forServer(event.getServer())
                .clearStaleServerChunksAfterRendererUpgrade();
        if (cleared > 0) {
            MarketWebMapRenderService.global().enqueueRegionRepairScan(event.getServer().overworld(), 64);
        }
        MarketWebServer.start(event.getServer());
        MarketWebMapRenderService.global().startRegionWatcher(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MarketWebMapRenderService.global().stopRegionWatcher(event.getServer());
        com.monpai.sailboatmod.market.web.map.MarketWebTellusTileQueue.shutdownGlobal();
        MarketWebServer.stop();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            MarketWebMapRenderService.global().tick(server);
            MarketWebServer web = MarketWebServer.get();
            if (web != null) {
                web.publishMarketChanges(server.getTickCount());
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MarketWebCommands.register(event.getDispatcher());
    }

    private MarketWebEvents() {
    }
}

