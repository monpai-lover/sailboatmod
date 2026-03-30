package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class DispatchMarketOrderPacket {
    private final BlockPos marketPos;
    private final int orderIndex;
    private final int boatIndex;

    public DispatchMarketOrderPacket(BlockPos marketPos, int orderIndex, int boatIndex) {
        this.marketPos = marketPos;
        this.orderIndex = orderIndex;
        this.boatIndex = boatIndex;
    }

    public static void encode(DispatchMarketOrderPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        buffer.writeVarInt(packet.orderIndex);
        buffer.writeVarInt(packet.boatIndex);
    }

    public static DispatchMarketOrderPacket decode(FriendlyByteBuf buffer) {
        return new DispatchMarketOrderPacket(buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(DispatchMarketOrderPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            market.dispatchOrder(player, packet.orderIndex, packet.boatIndex);
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
