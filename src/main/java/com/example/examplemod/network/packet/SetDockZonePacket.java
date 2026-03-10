package com.example.examplemod.network.packet;

import com.example.examplemod.block.entity.DockBlockEntity;
import com.example.examplemod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class SetDockZonePacket {
    private final BlockPos dockPos;
    private final int minX;
    private final int maxX;
    private final int minZ;
    private final int maxZ;

    public SetDockZonePacket(BlockPos dockPos, int minX, int maxX, int minZ, int maxZ) {
        this.dockPos = dockPos;
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    public static void encode(SetDockZonePacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.dockPos);
        buffer.writeVarInt(packet.minX);
        buffer.writeVarInt(packet.maxX);
        buffer.writeVarInt(packet.minZ);
        buffer.writeVarInt(packet.maxZ);
    }

    public static SetDockZonePacket decode(FriendlyByteBuf buffer) {
        return new SetDockZonePacket(
                buffer.readBlockPos(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readVarInt()
        );
    }

    public static void handle(SetDockZonePacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.level().getBlockEntity(packet.dockPos) instanceof DockBlockEntity dock)) {
                return;
            }
            if (dock.canManageDock(player)) {
                dock.setDockZone(packet.minX, packet.maxX, packet.minZ, packet.maxZ);
            }
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new OpenDockScreenPacket(dock.buildScreenData(player)));
        });
        context.setPacketHandled(true);
    }
}
