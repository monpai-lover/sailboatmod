package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.SailboatControlInput;
import com.monpai.sailboatmod.entity.SailboatEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SailboatControlInputPacket(SailboatControlInput input) {
    public SailboatControlInputPacket {
        input = input == null ? SailboatControlInput.IDLE : input;
    }

    public static void encode(SailboatControlInputPacket packet, FriendlyByteBuf buffer) {
        SailboatControlInput input = packet.input;
        buffer.writeBoolean(input.forward());
        buffer.writeBoolean(input.back());
        buffer.writeBoolean(input.left());
        buffer.writeBoolean(input.right());
    }

    public static SailboatControlInputPacket decode(FriendlyByteBuf buffer) {
        return new SailboatControlInputPacket(new SailboatControlInput(
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readBoolean()
        ));
    }

    public static void handle(SailboatControlInputPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender != null && sender.getVehicle() instanceof SailboatEntity sailboat) {
                sailboat.applyManualControlInput(sender, packet.input);
            }
        });
        context.setPacketHandled(true);
    }
}
