package com.monpai.sailboatmod.network.packet.airport;

import com.monpai.sailboatmod.client.AirportClientHooks;
import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.transport.air.airport.AirportScreenData;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import java.util.function.Supplier;

public record AirportSnapshotPacket(AirportScreenData data, boolean repaint) {
    public static void encode(AirportSnapshotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeNbt(packet.data.save()); buffer.writeBoolean(packet.repaint);
    }
    public static AirportSnapshotPacket decode(FriendlyByteBuf buffer) {
        var tag = buffer.readNbt();
        if (tag == null) throw new IllegalArgumentException("Missing airport snapshot");
        return new AirportSnapshotPacket(AirportScreenData.load(tag),buffer.readBoolean());
    }
    public static void handle(AirportSnapshotPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> AirportClientHooks.openOrUpdate(packet.data,packet.repaint)));
        context.setPacketHandled(true);
    }
}
