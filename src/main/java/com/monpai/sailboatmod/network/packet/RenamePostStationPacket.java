package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class RenamePostStationPacket {
    private final BlockPos stationPos;
    private final String stationName;

    public RenamePostStationPacket(BlockPos stationPos, String stationName) {
        this.stationPos = stationPos;
        this.stationName = stationName == null ? "" : stationName;
    }

    public static void encode(RenamePostStationPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.stationPos);
        PacketStringCodec.writeUtfSafe(buffer, packet.stationName, 64);
    }

    public static RenamePostStationPacket decode(FriendlyByteBuf buffer) {
        return new RenamePostStationPacket(buffer.readBlockPos(), buffer.readUtf(64));
    }

    public static void handle(RenamePostStationPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.stationPos) instanceof PostStationBlockEntity station)) {
                return;
            }
            if (station.canManageDock(player)) {
                station.setDockName(packet.stationName);
            }
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenPostStationScreenPacket(station.buildPostStationScreenData(player))
            );
        });
        context.setPacketHandled(true);
    }
}
