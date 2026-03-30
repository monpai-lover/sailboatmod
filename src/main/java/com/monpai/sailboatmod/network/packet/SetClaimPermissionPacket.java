package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.NationOverviewData;
import com.monpai.sailboatmod.nation.service.NationClaimService;
import com.monpai.sailboatmod.nation.service.NationOverviewService;
import com.monpai.sailboatmod.nation.service.NationResult;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class SetClaimPermissionPacket {
    private final int chunkX;
    private final int chunkZ;
    private final String actionId;
    private final String levelId;

    public SetClaimPermissionPacket(int chunkX, int chunkZ, String actionId, String levelId) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.actionId = actionId == null ? "" : actionId.trim();
        this.levelId = levelId == null ? "" : levelId.trim();
    }

    public static void encode(SetClaimPermissionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.chunkX);
        buffer.writeInt(packet.chunkZ);
        PacketStringCodec.writeUtfSafe(buffer, packet.actionId, 16);
        PacketStringCodec.writeUtfSafe(buffer, packet.levelId, 16);
    }

    public static SetClaimPermissionPacket decode(FriendlyByteBuf buffer) {
        return new SetClaimPermissionPacket(
                buffer.readInt(),
                buffer.readInt(),
                buffer.readUtf(16),
                buffer.readUtf(16)
        );
    }

    public static void handle(SetClaimPermissionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            NationResult result = NationClaimService.setChunkPermission(
                    player,
                    new ChunkPos(packet.chunkX, packet.chunkZ),
                    packet.actionId,
                    packet.levelId
            );
            player.sendSystemMessage(result.message());

            NationOverviewData data = NationOverviewService.buildFor(player);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenNationScreenPacket(data));
        });
        context.setPacketHandled(true);
    }
}
