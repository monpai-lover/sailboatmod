package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.menu.TownOverviewData;
import com.monpai.sailboatmod.nation.service.NationResult;
import com.monpai.sailboatmod.nation.service.TownClaimService;
import com.monpai.sailboatmod.nation.service.TownOverviewService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class SetTownClaimPermissionPacket {
    private final String townId;
    private final int chunkX;
    private final int chunkZ;
    private final String actionId;
    private final String levelId;

    public SetTownClaimPermissionPacket(String townId, int chunkX, int chunkZ, String actionId, String levelId) {
        this.townId = townId == null ? "" : townId.trim();
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.actionId = actionId == null ? "" : actionId.trim();
        this.levelId = levelId == null ? "" : levelId.trim();
    }

    public static void encode(SetTownClaimPermissionPacket packet, FriendlyByteBuf buffer) {
        PacketStringCodec.writeUtfSafe(buffer, packet.townId, 40);
        buffer.writeInt(packet.chunkX);
        buffer.writeInt(packet.chunkZ);
        PacketStringCodec.writeUtfSafe(buffer, packet.actionId, 16);
        PacketStringCodec.writeUtfSafe(buffer, packet.levelId, 16);
    }

    public static SetTownClaimPermissionPacket decode(FriendlyByteBuf buffer) {
        return new SetTownClaimPermissionPacket(
                buffer.readUtf(40),
                buffer.readInt(),
                buffer.readInt(),
                buffer.readUtf(16),
                buffer.readUtf(16)
        );
    }

    public static void handle(SetTownClaimPermissionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            NationResult result = TownClaimService.setChunkPermission(
                    player,
                    packet.townId,
                    new ChunkPos(packet.chunkX, packet.chunkZ),
                    packet.actionId,
                    packet.levelId
            );
            if (!result.message().getString().isBlank()) {
                player.sendSystemMessage(result.message());
            }
            TownOverviewData data = TownOverviewService.buildFor(player, packet.townId);
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenTownScreenPacket(data));
        });
        context.setPacketHandled(true);
    }
}