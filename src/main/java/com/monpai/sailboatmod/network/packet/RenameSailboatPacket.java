package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.entity.SailboatEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class RenameSailboatPacket {
    private final int sailboatId;
    private final String name;

    public RenameSailboatPacket(int sailboatId, String name) {
        this.sailboatId = sailboatId;
        this.name = name;
    }

    public static void encode(RenameSailboatPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.sailboatId);
        PacketStringCodec.writeUtfSafe(buffer, packet.name, 64);
    }

    public static RenameSailboatPacket decode(FriendlyByteBuf buffer) {
        return new RenameSailboatPacket(buffer.readVarInt(), buffer.readUtf(64));
    }

    public static void handle(RenameSailboatPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }

            if (!(player.level().getEntity(packet.sailboatId) instanceof SailboatEntity sailboat)) {
                return;
            }

            if (player.getVehicle() != sailboat || !sailboat.isCaptain(player)) {
                return;
            }

                String trimmed = packet.name.trim();
                if (trimmed.isEmpty()) {
                    sailboat.setCustomName(null);
                } else {
                    sailboat.setCustomName(Component.literal(trimmed));
                }
        });
        context.setPacketHandled(true);
    }
}
