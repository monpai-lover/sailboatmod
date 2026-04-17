package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.service.NationOverviewService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class OpenNationMenuPacket {
    public OpenNationMenuPacket() {}

    public static void encode(OpenNationMenuPacket packet, FriendlyByteBuf buffer) {}

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
            ChunkPos previewCenter = NationOverviewService.getCoreCenterOrPlayer(player);
            NationOverviewData data = NationOverviewService.buildFor(player, previewCenter);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenNationScreenPacket(data));
        });
        context.setPacketHandled(true);
    }
}
