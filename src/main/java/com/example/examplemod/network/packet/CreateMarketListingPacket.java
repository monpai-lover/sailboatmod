package com.example.examplemod.network.packet;

import com.example.examplemod.block.entity.MarketBlockEntity;
import com.example.examplemod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class CreateMarketListingPacket {
    private final BlockPos marketPos;
    private final int quantity;
    private final int unitPrice;

    public CreateMarketListingPacket(BlockPos marketPos, int quantity, int unitPrice) {
        this.marketPos = marketPos;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public static void encode(CreateMarketListingPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        buffer.writeVarInt(packet.quantity);
        buffer.writeVarInt(packet.unitPrice);
    }

    public static CreateMarketListingPacket decode(FriendlyByteBuf buffer) {
        return new CreateMarketListingPacket(buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(CreateMarketListingPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            market.createListingFromHeldItem(player, packet.quantity, packet.unitPrice);
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
