package com.monpai.sailboatmod.client.integration.xaero;

import com.monpai.sailboatmod.SailboatMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class SailboatXaeroClientEvents {
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        SailboatXaeroCompat.onClientTickEnd();
    }

    private SailboatXaeroClientEvents() {
    }
}
