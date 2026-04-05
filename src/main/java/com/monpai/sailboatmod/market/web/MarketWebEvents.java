package com.monpai.sailboatmod.market.web;

import com.monpai.sailboatmod.SailboatMod;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MarketWebEvents {
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        MarketWebCommands.register(event.getDispatcher());
    }

    private MarketWebEvents() {
    }
}

