package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.SailboatEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SelectSailboatSeatPacket {
    private final int sailboatId;
    private final int seat;

    public SelectSailboatSeatPacket(int sailboatId, int seat) {
        this.sailboatId = sailboatId;
        this.seat = seat;
    }

    public static void encode(SelectSailboatSeatPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.sailboatId);
        buffer.writeVarInt(packet.seat);
    }

    public static SelectSailboatSeatPacket decode(FriendlyByteBuf buffer) {
        return new SelectSailboatSeatPacket(buffer.readVarInt(), buffer.readVarInt());
    }

    public static void handle(SelectSailboatSeatPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            if (!(player.level().getEntity(packet.sailboatId) instanceof SailboatEntity sailboat)) {
                return;
            }

            if (player.getVehicle() == sailboat) {
                sailboat.requestSeat(player, packet.seat);
            }
        });
        context.setPacketHandled(true);
    }
}
