package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.SailboatEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ControlAutopilotPacket {
    private final int sailboatId;
    private final SailboatEntity.AutopilotControlAction action;

    public ControlAutopilotPacket(int sailboatId, SailboatEntity.AutopilotControlAction action) {
        this.sailboatId = sailboatId;
        this.action = action;
    }

    public static void encode(ControlAutopilotPacket packet, FriendlyByteBuf buffer) {
        buffer.writeInt(packet.sailboatId);
        buffer.writeEnum(packet.action);
    }

    public static ControlAutopilotPacket decode(FriendlyByteBuf buffer) {
        int sailboatId = buffer.readInt();
        SailboatEntity.AutopilotControlAction action = buffer.readEnum(SailboatEntity.AutopilotControlAction.class);
        return new ControlAutopilotPacket(sailboatId, action);
    }

    public static void handle(ControlAutopilotPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) {
                return;
            }
            if (sender.level().getEntity(packet.sailboatId) instanceof SailboatEntity sailboat) {
                sailboat.controlAutopilot(sender, packet.action);
            }
        });
        context.setPacketHandled(true);
    }
}
