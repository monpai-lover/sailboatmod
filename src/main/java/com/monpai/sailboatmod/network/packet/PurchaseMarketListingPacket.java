package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class PurchaseMarketListingPacket {
    private final BlockPos marketPos;
    private final String listingId;
    private final int quantity;
    private final String fulfillment;
    private final BlockPos targetWarehousePos;

    public PurchaseMarketListingPacket(BlockPos marketPos, String listingId, int quantity) {
        this(marketPos, listingId, quantity, "SELLER_SHIP", BlockPos.ZERO);
    }

    public PurchaseMarketListingPacket(BlockPos marketPos, String listingId, int quantity,
                                       String fulfillment, BlockPos targetWarehousePos) {
        this.marketPos = marketPos;
        this.listingId = listingId == null ? "" : listingId;
        this.quantity = quantity;
        this.fulfillment = fulfillment == null ? "SELLER_SHIP" : fulfillment;
        this.targetWarehousePos = targetWarehousePos == null ? BlockPos.ZERO : targetWarehousePos;
    }

    public static void encode(PurchaseMarketListingPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        PacketStringCodec.writeUtfSafe(buffer, packet.listingId, 64);
        buffer.writeVarInt(packet.quantity);
        PacketStringCodec.writeUtfSafe(buffer, packet.fulfillment, 32);
        buffer.writeBlockPos(packet.targetWarehousePos);
    }

    public static PurchaseMarketListingPacket decode(FriendlyByteBuf buffer) {
        return new PurchaseMarketListingPacket(
                buffer.readBlockPos(),
                buffer.readUtf(64),
                buffer.readVarInt(),
                buffer.readUtf(32),
                buffer.readBlockPos());
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
            market.purchaseListingById(
                    player.getUUID().toString(),
                    player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                    player,
                    packet.listingId,
                    packet.quantity,
                    packet.fulfillment,
                    packet.targetWarehousePos
            );
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
