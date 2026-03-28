package com.example.examplemod.network.packet;

import com.example.examplemod.nation.menu.NationOverviewData;
import com.example.examplemod.nation.service.NationOverviewService;
import com.example.examplemod.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class OpenNationMenuPacket {
    public static void encode(OpenNationMenuPacket packet, FriendlyByteBuf buffer) {
    }

    public static OpenNationMenuPacket decode(FriendlyByteBuf buffer) {
        return new OpenNationMenuPacket();
    }

    public static void handle(OpenNationMenuPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            NationOverviewData data = NationOverviewService.buildFor(player);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenNationScreenPacket(data));
        });
        context.setPacketHandled(true);
    }
}
