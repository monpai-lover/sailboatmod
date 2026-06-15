package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class ProbeFulfillmentModesPacket {
    private final BlockPos marketPos;
    private final String listingId;
    private final BlockPos targetWarehousePos;

    public ProbeFulfillmentModesPacket(BlockPos marketPos, String listingId, BlockPos targetWarehousePos) {
        this.marketPos = marketPos;
        this.listingId = listingId == null ? "" : listingId;
        this.targetWarehousePos = targetWarehousePos == null ? BlockPos.ZERO : targetWarehousePos;
    }

    public static void encode(ProbeFulfillmentModesPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        PacketStringCodec.writeUtfSafe(buffer, packet.listingId, 64);
        buffer.writeBlockPos(packet.targetWarehousePos);
    }

    public static ProbeFulfillmentModesPacket decode(FriendlyByteBuf buffer) {
        return new ProbeFulfillmentModesPacket(
                buffer.readBlockPos(),
                buffer.readUtf(64),
                buffer.readBlockPos());
    }

    public static void handle(ProbeFulfillmentModesPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            MarketBlockEntity.ModeReachability r = market.probeFulfillmentModes(
                    player.getUUID().toString(), packet.targetWarehousePos, player);
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new ProbeFulfillmentModesResultPacket(packet.listingId, r.sellerShip(), r.autoPickup(), r.realPickup()));
        });
        context.setPacketHandled(true);
    }
}
