package com.example.examplemod.network.packet;

import com.example.examplemod.block.entity.DockBlockEntity;
import com.example.examplemod.network.ModNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

public class RenameDockPacket {
    private final BlockPos dockPos;
    private final String dockName;

    public RenameDockPacket(BlockPos dockPos, String dockName) {
        this.dockPos = dockPos;
        this.dockName = dockName == null ? "" : dockName;
    }

    public static void encode(RenameDockPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBlockPos(packet.dockPos);
        buffer.writeUtf(packet.dockName, 64);
    }

    public static RenameDockPacket decode(FriendlyByteBuf buffer) {
        return new RenameDockPacket(buffer.readBlockPos(), buffer.readUtf(64));
    }

    public static void handle(RenameDockPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
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
                dock.setDockName(packet.dockName);
            }
            ModNetwork.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new OpenDockScreenPacket(dock.buildScreenData(player))
            );
        });
        context.setPacketHandled(true);
    }
}
