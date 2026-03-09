package com.example.examplemod.network.packet;

import com.example.examplemod.block.entity.MarketBlockEntity;
import com.example.examplemod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class PurchaseMarketListingPacket {
    private final BlockPos marketPos;
    private final int listingIndex;
    private final int quantity;

    public PurchaseMarketListingPacket(BlockPos marketPos, int listingIndex, int quantity) {
        this.marketPos = marketPos;
        this.listingIndex = listingIndex;
        this.quantity = quantity;
    }

    public static void encode(PurchaseMarketListingPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        buffer.writeVarInt(packet.listingIndex);
        buffer.writeVarInt(packet.quantity);
    }

    public static PurchaseMarketListingPacket decode(FriendlyByteBuf buffer) {
        return new PurchaseMarketListingPacket(buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(PurchaseMarketListingPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            market.purchaseListing(player, packet.listingIndex, packet.quantity);
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
