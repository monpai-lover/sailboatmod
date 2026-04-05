package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class CancelMarketListingPacket {
    private final BlockPos marketPos;
    private final int listingIndex;

    public CancelMarketListingPacket(BlockPos marketPos, int listingIndex) {
        this.marketPos = marketPos;
        this.listingIndex = listingIndex;
    }

    public static void encode(CancelMarketListingPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        buffer.writeVarInt(packet.listingIndex);
    }

    public static CancelMarketListingPacket decode(FriendlyByteBuf buffer) {
        return new CancelMarketListingPacket(buffer.readBlockPos(), buffer.readVarInt());
    }

    public static void handle(CancelMarketListingPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            MarketBlockEntity.CancelListingResult result = market.cancelListingResult(player, packet.listingIndex);
            String messageKey = result.messageKey();
            if (!messageKey.isBlank()) {
                ModNetwork.CHANNEL.send(
                        PacketDistributor.PLAYER.with(() -> player),
                        new MarketStatusNoticePacket(packet.marketPos, Component.translatable(messageKey).getString(), result.success())
                );
            }
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
