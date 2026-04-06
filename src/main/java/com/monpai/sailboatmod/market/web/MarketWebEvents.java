package com.monpai.sailboatmod.market.web;

import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SailboatMarketWebAddon.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MarketWebEvents {
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MarketWebServer.start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        MarketWebServer.stop();
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MarketWebCommands.register(event.getDispatcher());
    }

    private MarketWebEvents() {
    }
}

