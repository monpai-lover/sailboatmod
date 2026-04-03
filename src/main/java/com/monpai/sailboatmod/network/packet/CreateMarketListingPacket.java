package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class CreateMarketListingPacket {
    private final BlockPos marketPos;
    private final int storageIndex;
    private final int quantity;
    private final int unitPrice;
    private final int priceAdjustmentBp;
    private final String sellerNote;

    public CreateMarketListingPacket(BlockPos marketPos, int storageIndex, int quantity, int unitPrice, int priceAdjustmentBp, String sellerNote) {
        this.marketPos = marketPos;
        this.storageIndex = storageIndex;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.priceAdjustmentBp = priceAdjustmentBp;
        this.sellerNote = sellerNote == null ? "" : sellerNote;
    }
    public CreateMarketListingPacket(BlockPos marketPos, int storageIndex, int quantity, int unitPrice, int priceAdjustmentBp) {
        this(marketPos, storageIndex, quantity, unitPrice, priceAdjustmentBp, "");
    }


    public static void encode(CreateMarketListingPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        buffer.writeVarInt(packet.storageIndex);
        buffer.writeVarInt(packet.quantity);
        buffer.writeVarInt(packet.unitPrice);
        buffer.writeVarInt(packet.priceAdjustmentBp);
        PacketStringCodec.writeUtfSafe(buffer, packet.sellerNote, 120);
    }

    public static CreateMarketListingPacket decode(FriendlyByteBuf buffer) {
        return new CreateMarketListingPacket(buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(120));
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
            market.createListingFromDockStorage(player, packet.storageIndex, packet.quantity, packet.unitPrice, packet.priceAdjustmentBp, packet.sellerNote);
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
