package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.nation.service.TownOverviewService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class OpenTownMenuPacket {
    private final String townId;
    private final int previewCenterChunkX;
    private final int previewCenterChunkZ;

    public OpenTownMenuPacket(String townId) {
        this(townId, Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public OpenTownMenuPacket(String townId, int previewCenterChunkX, int previewCenterChunkZ) {
        this.townId = townId == null ? "" : townId.trim();
        this.previewCenterChunkX = previewCenterChunkX;
        this.previewCenterChunkZ = previewCenterChunkZ;
    }

    public static void encode(OpenTownMenuPacket packet, FriendlyByteBuf buffer) {
        PacketStringCodec.writeUtfSafe(buffer, packet.townId, 40);
        buffer.writeInt(packet.previewCenterChunkX);
        buffer.writeInt(packet.previewCenterChunkZ);
    }

    public static OpenTownMenuPacket decode(FriendlyByteBuf buffer) {
        return new OpenTownMenuPacket(buffer.readUtf(40), buffer.readInt(), buffer.readInt());
    }

    public static void handle(OpenTownMenuPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ChunkPos previewCenter;
            if (packet.previewCenterChunkX == Integer.MIN_VALUE || packet.previewCenterChunkZ == Integer.MIN_VALUE) {
                previewCenter = TownOverviewService.getCoreCenterOrPlayer(player, packet.townId);
            } else {
                previewCenter = new ChunkPos(packet.previewCenterChunkX, packet.previewCenterChunkZ);
            }
            TownOverviewData data = TownOverviewService.buildFor(player, packet.townId, previewCenter);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenTownScreenPacket(data));
        });
        context.setPacketHandled(true);
    }
}
