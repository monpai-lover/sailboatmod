package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class DispatchMarketOrderPacket {
    private final BlockPos marketPos;
    private final String orderId;
    private final TransportTerminalKind terminalKind;

    public DispatchMarketOrderPacket(BlockPos marketPos, String orderId, TransportTerminalKind terminalKind) {
        this.marketPos = marketPos;
        this.orderId = orderId == null ? "" : orderId;
        this.terminalKind = terminalKind == null ? TransportTerminalKind.AUTO : terminalKind;
    }

    public static void encode(DispatchMarketOrderPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        PacketStringCodec.writeUtfSafe(buffer, packet.orderId, 64);
        buffer.writeEnum(packet.terminalKind);
    }

    public static DispatchMarketOrderPacket decode(FriendlyByteBuf buffer) {
        return new DispatchMarketOrderPacket(buffer.readBlockPos(), buffer.readUtf(64), buffer.readEnum(TransportTerminalKind.class));
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
            market.dispatchOrderById(
                    player.getUUID().toString(),
                    player.getGameProfile() == null ? player.getName().getString() : player.getGameProfile().getName(),
                    player,
                    packet.orderId,
                    packet.terminalKind
            );
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }
}
