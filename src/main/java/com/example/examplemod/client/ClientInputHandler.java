package com.example.examplemod.client;

import com.example.examplemod.SailboatMod;
import com.example.examplemod.client.screen.SailboatInfoScreen;
import com.example.examplemod.entity.SailboatEntity;
import com.example.examplemod.network.ModNetwork;
import com.example.examplemod.network.packet.OpenSailboatStoragePacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = SailboatMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ClientInputHandler {
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.screen != null) {
            return;
        }

        if (player.getVehicle() instanceof SailboatEntity && minecraft.options.keyInventory.consumeClick()) {
            ModNetwork.CHANNEL.sendToServer(new OpenSailboatStoragePacket());
        }

        if (player.getVehicle() instanceof SailboatEntity sailboat && ClientKeyMappings.OPEN_SAILBOAT_INFO.consumeClick()) {
            minecraft.setScreen(new SailboatInfoScreen(sailboat));
        }
    }

    private ClientInputHandler() {
    }
}
