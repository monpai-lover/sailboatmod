package com.example.examplemod;

import com.example.examplemod.integration.bluemap.BlueMapIntegration;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerEvents {
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        BlueMapIntegration.onServerStarted(event.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            BlueMapIntegration.onServerTick(server);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BlueMapIntegration.onServerStopped();
    }

    private ServerEvents() {
    }
}
