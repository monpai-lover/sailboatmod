package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.SailboatEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ToggleSailPacket {
    public static void encode(ToggleSailPacket packet, FriendlyByteBuf buffer) {
    }

    public static ToggleSailPacket decode(FriendlyByteBuf buffer) {
        return new ToggleSailPacket();
    }

    public static void handle(ToggleSailPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && player.getVehicle() instanceof SailboatEntity sailboat) {
                sailboat.toggleSail(player);
            }
        });
        context.setPacketHandled(true);
    }
}
