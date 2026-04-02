package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.DockClientHooks;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SyncAutoRouteDocksPacket {
    private final BlockPos sourceDockPos;
    private final List<AvailableDockEntry> docks;

    public SyncAutoRouteDocksPacket(BlockPos sourceDockPos, List<AvailableDockEntry> docks) {
        this.sourceDockPos = sourceDockPos;
        this.docks = docks;
    }

    public static void encode(SyncAutoRouteDocksPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.sourceDockPos);
        buf.writeVarInt(msg.docks.size());
        for (AvailableDockEntry dock : msg.docks) {
            buf.writeBlockPos(dock.pos());
            buf.writeUtf(dock.dockName(), 64);
            buf.writeUtf(dock.ownerName(), 64);
            buf.writeUtf(dock.nationName(), 64);
            buf.writeVarInt(dock.distance());
        }
    }

    public static SyncAutoRouteDocksPacket decode(FriendlyByteBuf buf) {
        BlockPos sourceDockPos = buf.readBlockPos();
        int size = buf.readVarInt();
        List<AvailableDockEntry> docks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            docks.add(new AvailableDockEntry(
                buf.readBlockPos(),
                buf.readUtf(64),
                buf.readUtf(64),
                buf.readUtf(64),
                buf.readVarInt()
            ));
        }
        return new SyncAutoRouteDocksPacket(sourceDockPos, docks);
    }

    public static void handle(SyncAutoRouteDocksPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            DockClientHooks.openAutoRouteDockSelection(msg.sourceDockPos, msg.docks)
        ));
        ctx.get().setPacketHandled(true);
    }
}
