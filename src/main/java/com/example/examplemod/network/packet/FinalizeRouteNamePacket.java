package com.example.examplemod.network.packet;

import com.example.examplemod.item.RouteBookItem;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class FinalizeRouteNamePacket {
    private final InteractionHand hand;
    private final String routeName;

    public FinalizeRouteNamePacket(InteractionHand hand, String routeName) {
        this.hand = hand;
        this.routeName = routeName == null ? "" : routeName;
    }

    public static void encode(FinalizeRouteNamePacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.hand);
        buffer.writeUtf(packet.routeName, 64);
    }

    public static FinalizeRouteNamePacket decode(FriendlyByteBuf buffer) {
        return new FinalizeRouteNamePacket(buffer.readEnum(InteractionHand.class), buffer.readUtf(64));
    }

    public static void handle(FinalizeRouteNamePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            boolean ok = RouteBookItem.finalizeActiveRoute(player, packet.hand, packet.routeName);
            if (!ok) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("item.sailboatmod.route_book.finish_invalid"));
            }
        });
        context.setPacketHandled(true);
    }
}
