package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.nation.service.ClaimPreviewTerrainService;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ClearTerrainCachePacket {
    public static void encode(ClearTerrainCachePacket msg, FriendlyByteBuf buf) {}

    public static ClearTerrainCachePacket decode(FriendlyByteBuf buf) {
        return new ClearTerrainCachePacket();
    }

    public static void handle(ClearTerrainCachePacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;
            ClaimPreviewTerrainService.clearAllPersistedColors(player.serverLevel());
        });
        ctx.get().setPacketHandled(true);
    }
}
