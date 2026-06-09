package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.CarriageDriveInput;
import com.monpai.sailboatmod.entity.CarriageEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record CarriageControlInputPacket(CarriageDriveInput.AccelerationDirection acceleration,
                                         CarriageDriveInput.TurnDirection turn,
                                         float targetTurnAngle,
                                         float power) {
    public CarriageControlInputPacket {
        acceleration = acceleration == null ? CarriageDriveInput.AccelerationDirection.NONE : acceleration;
        turn = turn == null ? CarriageDriveInput.TurnDirection.FORWARD : turn;
    }

    public static void encode(CarriageControlInputPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.acceleration);
        buffer.writeEnum(packet.turn);
        buffer.writeFloat(packet.targetTurnAngle);
        buffer.writeFloat(packet.power);
    }

    public static CarriageControlInputPacket decode(FriendlyByteBuf buffer) {
        return new CarriageControlInputPacket(
                buffer.readEnum(CarriageDriveInput.AccelerationDirection.class),
                buffer.readEnum(CarriageDriveInput.TurnDirection.class),
                buffer.readFloat(),
                buffer.readFloat()
        );
    }

    public static void handle(CarriageControlInputPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null && sender.getVehicle() instanceof CarriageEntity carriage) {
                carriage.applyManualControlInput(sender, new CarriageDriveInput(
                        packet.acceleration,
                        packet.turn,
                        packet.targetTurnAngle,
                        packet.power
                ));
            }
        });
        context.setPacketHandled(true);
    }
}
