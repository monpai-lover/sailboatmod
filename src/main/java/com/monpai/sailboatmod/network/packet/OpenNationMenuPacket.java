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
    private final int previewCenterChunkX;
    private final int previewCenterChunkZ;

    public OpenNationMenuPacket() {
        this(Integer.MIN_VALUE, Integer.MIN_VALUE);
    }

    public OpenNationMenuPacket(int previewCenterChunkX, int previewCenterChunkZ) {
        this.previewCenterChunkX = previewCenterChunkX;
        this.previewCenterChunkZ = previewCenterChunkZ;
    }

    public static void encode(OpenNationMenuPacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.previewCenterChunkX);
        buffer.writeInt(packet.previewCenterChunkZ);
    }

    public static OpenNationMenuPacket decode(FriendlyByteBuf buffer) {
        return new OpenNationMenuPacket(buffer.readInt(), buffer.readInt());
    }

    public static void handle(OpenNationMenuPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ChunkPos previewCenter = packet.previewCenterChunkX == Integer.MIN_VALUE || packet.previewCenterChunkZ == Integer.MIN_VALUE
                    ? player.chunkPosition()
                    : new ChunkPos(packet.previewCenterChunkX, packet.previewCenterChunkZ);
            NationOverviewData data = NationOverviewService.buildFor(player, previewCenter);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenNationScreenPacket(data));
        });
        context.setPacketHandled(true);
    }
}
