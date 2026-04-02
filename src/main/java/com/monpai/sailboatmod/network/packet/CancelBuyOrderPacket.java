package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.market.commodity.CommodityMarketService;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class CancelBuyOrderPacket {
    private final BlockPos marketPos;
    private final String orderId;

    public CancelBuyOrderPacket(BlockPos marketPos, String orderId) {
        this.marketPos = marketPos;
        this.orderId = orderId;
    }

    public static void encode(CancelBuyOrderPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        buffer.writeUtf(packet.orderId);
    }

    public static CancelBuyOrderPacket decode(FriendlyByteBuf buffer) {
        return new CancelBuyOrderPacket(buffer.readBlockPos(), buffer.readUtf());
    }

    public static void handle(CancelBuyOrderPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || !(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            try {
                new com.monpai.sailboatmod.market.commodity.CommodityMarketService().cancelBuyOrder(packet.orderId);
            } catch (Exception ignored) {
            }
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
