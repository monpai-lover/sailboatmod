package com.example.examplemod.network.packet;

import com.example.examplemod.entity.SailboatEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class OpenSailboatStoragePacket {
    public static void encode(OpenSailboatStoragePacket packet, FriendlyByteBuf buffer) {
    }

    public static OpenSailboatStoragePacket decode(FriendlyByteBuf buffer) {
        return new OpenSailboatStoragePacket();
    }

    public static void handle(OpenSailboatStoragePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player != null && player.getVehicle() instanceof SailboatEntity sailboat) {
                sailboat.openStorage(player);
            }
        });
        context.setPacketHandled(true);
    }
}
