package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class ClaimMarketCreditsPacket {
    private final BlockPos marketPos;

    public ClaimMarketCreditsPacket(BlockPos marketPos) {
        this.marketPos = marketPos;
    }

    public static void encode(ClaimMarketCreditsPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
    }

    public static ClaimMarketCreditsPacket decode(FriendlyByteBuf buffer) {
        return new ClaimMarketCreditsPacket(buffer.readBlockPos());
    }

    public static void handle(ClaimMarketCreditsPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            market.claimPendingCredits(player);
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
