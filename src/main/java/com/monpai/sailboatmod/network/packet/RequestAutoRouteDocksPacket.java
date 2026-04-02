package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.route.AutoRouteService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class RequestAutoRouteDocksPacket {
    private final BlockPos sourceDockPos;

    public RequestAutoRouteDocksPacket(BlockPos sourceDockPos) {
        this.sourceDockPos = sourceDockPos;
    }

    public static void encode(RequestAutoRouteDocksPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.sourceDockPos);
    }

    public static RequestAutoRouteDocksPacket decode(FriendlyByteBuf buf) {
        return new RequestAutoRouteDocksPacket(buf.readBlockPos());
    }

    public static void handle(RequestAutoRouteDocksPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            if (!(player.level().getBlockEntity(msg.sourceDockPos) instanceof DockBlockEntity sourceDock)) return;

            List<AvailableDockEntry> available = new ArrayList<>();

            for (BlockPos dockPos : DockRegistry.get(player.level())) {
                if (dockPos.equals(msg.sourceDockPos)) continue;
                if (!(player.level().getBlockEntity(dockPos) instanceof DockBlockEntity targetDock)) continue;

                if (!AutoRouteService.canCreateAutoRoute(player.level(), sourceDock, targetDock)) continue;

                int distance = (int) Math.sqrt(msg.sourceDockPos.distSqr(dockPos));
                available.add(new AvailableDockEntry(
                    dockPos,
                    targetDock.getDockName(),
                    targetDock.getOwnerName(),
                    getNationName(player.level(), targetDock),
                    distance
                ));
            }

            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncAutoRouteDocksPacket(msg.sourceDockPos, available));
        });
        ctx.get().setPacketHandled(true);
    }

    private static String getNationName(net.minecraft.world.level.Level level, DockBlockEntity dock) {
        if (dock.getNationId().isBlank()) return "-";
        NationSavedData data = NationSavedData.get(level);
        var nation = data.getNation(dock.getNationId());
        return nation == null ? "-" : nation.name();
    }
}
