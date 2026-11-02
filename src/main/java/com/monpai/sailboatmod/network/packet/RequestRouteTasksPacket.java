package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.route.water.RouteUiSessions;
import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;
import java.util.function.Supplier;

/** Reads the caller's bounded server task history; no world IO or chunk loading. */
public record RequestRouteTasksPacket(UUID query) {
    public static void encode(RequestRouteTasksPacket packet, FriendlyByteBuf buf) { buf.writeUUID(packet.query); }
    public static RequestRouteTasksPacket decode(FriendlyByteBuf buf) { return new RequestRouteTasksPacket(buf.readUUID()); }
    public static void handle(RequestRouteTasksPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var player = ctx.get().getSender();
            if (player != null) ModNetwork.CHANNEL.send(player,
                    new SyncRouteTasksPacket(packet.query, RouteUiSessions.tasks(player)));
        });
        ctx.get().setPacketHandled(true);
    }
}
