package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.block.entity.DockBlockEntity;
import com.monpai.sailboatmod.block.entity.PostStationBlockEntity;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.dock.DockRegistry;
import com.monpai.sailboatmod.dock.PostStationRegistry;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.route.AutoRouteService;
import com.monpai.sailboatmod.route.RoadAutoRouteService;
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
            if (!(player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

            if (!(serverLevel.getBlockEntity(msg.sourceDockPos) instanceof DockBlockEntity sourceDock)) return;

            List<AvailableDockEntry> available = new ArrayList<>();
            boolean postStationMode = sourceDock instanceof PostStationBlockEntity;
            TransportTerminalKind terminalKind = postStationMode ? TransportTerminalKind.POST_STATION : TransportTerminalKind.PORT;
            Iterable<BlockPos> candidates = postStationMode ? PostStationRegistry.get(serverLevel) : DockRegistry.get(serverLevel);

            for (BlockPos dockPos : candidates) {
                if (dockPos.equals(msg.sourceDockPos)) continue;

                // Force-load chunk to access dock block entity
                serverLevel.getChunkSource().getChunk(dockPos.getX() >> 4, dockPos.getZ() >> 4, net.minecraft.world.level.chunk.ChunkStatus.FULL, true);
                if (!(serverLevel.getBlockEntity(dockPos) instanceof DockBlockEntity targetDock)) continue;

                boolean canCreate = postStationMode
                        ? RoadAutoRouteService.canResolveAutoRoute(serverLevel, sourceDock, targetDock)
                        : AutoRouteService.canCreateAutoRoute(serverLevel, sourceDock, targetDock);
                if (!canCreate) continue;

                int distance = (int) Math.sqrt(msg.sourceDockPos.distSqr(dockPos));
                available.add(new AvailableDockEntry(
                    dockPos,
                    targetDock.getDockName(),
                    targetDock.getOwnerName(),
                    getNationName(serverLevel, targetDock),
                    distance
                ));
            }

            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new SyncAutoRouteDocksPacket(msg.sourceDockPos, terminalKind, available));
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
