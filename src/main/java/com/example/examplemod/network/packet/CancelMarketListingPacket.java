package com.example.examplemod.network.packet;

import com.example.examplemod.block.entity.MarketBlockEntity;
import com.example.examplemod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
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
            market.cancelListing(player, packet.listingIndex);
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
