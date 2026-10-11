package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.NationClientHooks;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.api.distmarker.Dist;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.util.ClientSideExecutor;

import java.util.function.Supplier;

public class NationToastPacket {
    private final String title;
    private final String message;

    public NationToastPacket(Component title, Component message) {
        this(title == null ? "" : title.getString(), message == null ? "" : message.getString());
    }

    public NationToastPacket(String title, String message) {
        this.title = title == null ? "" : title.trim();
        this.message = message == null ? "" : message.trim();
    }

    public static void encode(NationToastPacket packet, FriendlyByteBuf buffer) {
        PacketStringCodec.writeUtfSafe(buffer, packet.title, 64);
        PacketStringCodec.writeUtfSafe(buffer, packet.message, 192);
    }

    public static NationToastPacket decode(FriendlyByteBuf buffer) {
        return new NationToastPacket(
                buffer.readUtf(64),
                buffer.readUtf(192)
        );
    }

    public static void handle(NationToastPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> NationClientHooks.showToast(packet.title, packet.message)));
        context.setPacketHandled(true);
    }

    public static void send(ServerPlayer player, Component title, Component message) {
        if (player == null || title == null || message == null) {
            return;
        }
        ModNetwork.CHANNEL.send(player, new NationToastPacket(title, message));
    }
}