package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.TownService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ClearTerrainCachePacket {
    public static void encode(ClearTerrainCachePacket msg, FriendlyByteBuf buf) {}

    public static ClearTerrainCachePacket decode(FriendlyByteBuf buf) {
        return new ClearTerrainCachePacket();
    }

    public static void handle(ClearTerrainCachePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            ChunkPos centerChunk = player.chunkPosition();
            String dimensionId = player.level().dimension().location().toString();
            int radius = Math.max(0, com.monpai.sailboatmod.ModConfig.claimPreviewRadius());
            long revision = System.nanoTime();

            NationSavedData data = NationSavedData.get(player.level());
            String nationId = "";
            String townId = "";
            NationMemberRecord member = data.getMember(player.getUUID());
            if (member != null) {
                nationId = member.nationId();
                TownRecord town = TownService.getTownForMember(data, member);
                townId = town == null ? "" : town.townId();
            } else {
                java.util.List<TownRecord> mayorTowns = data.getTownsForMayor(player.getUUID());
                if (!mayorTowns.isEmpty()) {
                    townId = mayorTowns.get(0).townId();
                }
            }

            RequestClaimMapViewportPacket.dispatch(
                    player,
                    new RequestClaimMapViewportPacket(
                            RequestClaimMapViewportPacket.ScreenKind.TOWN,
                            townId,
                            dimensionId,
                            revision,
                            radius,
                            centerChunk.x,
                            centerChunk.z,
                            0
                    )
            );
            RequestClaimMapViewportPacket.dispatch(
                    player,
                    new RequestClaimMapViewportPacket(
                            RequestClaimMapViewportPacket.ScreenKind.NATION,
                            nationId,
                            dimensionId,
                            revision,
                            radius,
                            centerChunk.x,
                            centerChunk.z,
                            0
                    )
            );
        });
        context.setPacketHandled(true);
    }
}
