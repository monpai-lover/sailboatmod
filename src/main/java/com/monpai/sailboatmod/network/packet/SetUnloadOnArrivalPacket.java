package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.TransportEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SetUnloadOnArrivalPacket {
    private final int entityId;
    private final boolean unloadOnArrival;

    public SetUnloadOnArrivalPacket(int entityId, boolean unloadOnArrival) {
        this.entityId = entityId;
        this.unloadOnArrival = unloadOnArrival;
    }

    public static void encode(SetUnloadOnArrivalPacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.entityId);
        buffer.writeBoolean(packet.unloadOnArrival);
    }

    public static SetUnloadOnArrivalPacket decode(FriendlyByteBuf buffer) {
        return new SetUnloadOnArrivalPacket(buffer.readInt(), buffer.readBoolean());
    }

    public static void handle(SetUnloadOnArrivalPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            if (sender.level().getEntity(packet.entityId) instanceof TransportEntity vehicle) {
                vehicle.setAllowNonOrderAutoUnload(packet.unloadOnArrival);
            }
        });
        context.setPacketHandled(true);
    }
}
