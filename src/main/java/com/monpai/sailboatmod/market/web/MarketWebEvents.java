package com.monpai.sailboatmod.market.web;

import com.monpai.sailboatmod.market.web.map.MarketWebMapRenderService;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = SailboatMarketWebAddon.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
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
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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

