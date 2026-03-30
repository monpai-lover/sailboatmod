package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class RenameMarketPacket {
    private final BlockPos marketPos;
    private final String marketName;

    public RenameMarketPacket(BlockPos marketPos, String marketName) {
        this.marketPos = marketPos;
        this.marketName = marketName == null ? "" : marketName;
    }

    public static void encode(RenameMarketPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        PacketStringCodec.writeUtfSafe(buffer, packet.marketName, 64);
    }

    public static RenameMarketPacket decode(FriendlyByteBuf buffer) {
        return new RenameMarketPacket(buffer.readBlockPos(), buffer.readUtf(64));
    }

    public static void handle(RenameMarketPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            if (market.canManageMarket(player)) {
                market.setMarketName(packet.marketName);
            }
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
