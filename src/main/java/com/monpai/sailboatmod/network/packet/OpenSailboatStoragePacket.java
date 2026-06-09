package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.TransportEntity;
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
            if (player != null && player.getVehicle() instanceof TransportEntity transport) {
                transport.openStorage(player);
            }
        });
        context.setPacketHandled(true);
    }
}
