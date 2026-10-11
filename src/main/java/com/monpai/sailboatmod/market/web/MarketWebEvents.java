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
        MarketWebServer.start(event.getServer());
        MarketWebMapRenderService.global().startRegionWatcher(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MarketWebMapRenderService.global().stopRegionWatcher(event.getServer());
        MarketWebServer.stop();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            MarketWebMapRenderService.global().tick(server);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MarketWebCommands.register(event.getDispatcher());
    }

    private MarketWebEvents() {
    }
}

