package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.MarketBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class MarketGuiActionPacket {
    private final BlockPos marketPos;
    private final Action action;

    public MarketGuiActionPacket(BlockPos marketPos, Action action) {
        this.marketPos = marketPos;
        this.action = action;
    }

    public static void encode(MarketGuiActionPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.marketPos);
        buffer.writeEnum(packet.action);
    }

    public static MarketGuiActionPacket decode(FriendlyByteBuf buffer) {
        return new MarketGuiActionPacket(buffer.readBlockPos(), buffer.readEnum(Action.class));
    }

    public static void handle(MarketGuiActionPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.marketPos) instanceof MarketBlockEntity market)) {
                return;
            }
            switch (packet.action) {
                case REFRESH -> { }
                case BIND_NEAREST_DOCK -> {
                    boolean bound = market.bindNearestDock();
                    String noticeMessage;
                    if (bound) {
                        String dockName = market.getLinkedWarehouse() != null
                                ? market.getLinkedWarehouse().getDisplayName().getString()
                                : (market.getLinkedDockPos() == null ? "-" : market.getLinkedDockPos().toShortString());
                        noticeMessage = Component.translatable("screen.sailboatmod.market.bind_dock.success", dockName).getString();
                    } else {
                        noticeMessage = Component.translatable("screen.sailboatmod.market.bind_dock.failed").getString();
                    }
                    ModNetwork.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> player),
                            new MarketStatusNoticePacket(packet.marketPos, noticeMessage, bound)
                    );
                }
            }
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenMarketScreenPacket(market.buildOverview(player))
            );
        });
        context.setPacketHandled(true);
    }

    public enum Action {
        REFRESH,
        BIND_NEAREST_DOCK
    }
}
