package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.nation.service.TownOverviewService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class OpenTownMenuPacket {
    private final String townId;

    public OpenTownMenuPacket(String townId) {
        this.townId = townId == null ? "" : townId.trim();
    }

    public static void encode(OpenTownMenuPacket packet, FriendlyByteBuf buffer) {
        PacketStringCodec.writeUtfSafe(buffer, packet.townId, 40);
    }

    public static OpenTownMenuPacket decode(FriendlyByteBuf buffer) {
        return new OpenTownMenuPacket(buffer.readUtf(40));
    }

    public static void handle(OpenTownMenuPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            TownOverviewData data = TownOverviewService.buildFor(player, packet.townId);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenTownScreenPacket(data));
        });
        context.setPacketHandled(true);
    }
}