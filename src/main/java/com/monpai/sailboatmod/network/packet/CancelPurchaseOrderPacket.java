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

public class CancelPurchaseOrderPacket {
    private final BlockPos marketPos;
    private final String orderId;

    public CancelPurchaseOrderPacket(BlockPos marketPos, String orderId) {
        this.marketPos = marketPos;
        this.orderId = orderId == null ? "" : orderId;
    }

    public static void encode(CancelPurchaseOrderPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        PacketStringCodec.writeUtfSafe(buffer, packet.orderId, 64);
    }

    public static CancelPurchaseOrderPacket decode(FriendlyByteBuf buffer) {
        return new CancelPurchaseOrderPacket(buffer.readBlockPos(), buffer.readUtf(64));
    }

    public static void handle(CancelPurchaseOrderPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            MarketBlockEntity.CancelPurchaseResult result = market.cancelPurchaseOrderById(player.getUUID().toString(), packet.orderId);
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
