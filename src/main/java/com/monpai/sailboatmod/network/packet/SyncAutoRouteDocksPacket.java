package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.DockClientHooks;
import com.monpai.sailboatmod.dock.AvailableDockEntry;
import com.monpai.sailboatmod.market.TransportTerminalKind;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import com.monpai.sailboatmod.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SyncAutoRouteDocksPacket {
    private final BlockPos sourceDockPos;
    private final java.util.UUID requestId;
    private final TransportTerminalKind terminalKind;
    private final List<AvailableDockEntry> docks;

    public SyncAutoRouteDocksPacket(BlockPos sourceDockPos, TransportTerminalKind terminalKind, List<AvailableDockEntry> docks) {
        this(sourceDockPos,terminalKind,docks,new java.util.UUID(0,0));
    }
    public SyncAutoRouteDocksPacket(BlockPos sourceDockPos,TransportTerminalKind terminalKind,List<AvailableDockEntry> docks,java.util.UUID requestId) {
        this.requestId=requestId;
        this.sourceDockPos = sourceDockPos;
        this.terminalKind = terminalKind;
        this.docks = docks;
    }

    public static void encode(SyncAutoRouteDocksPacket msg, FriendlyByteBuf buf) {
        buf.writeBlockPos(msg.sourceDockPos);
        buf.writeUUID(msg.requestId);
        buf.writeEnum(msg.terminalKind);
        buf.writeVarInt(msg.docks.size());
        for (AvailableDockEntry dock : msg.docks) {
            buf.writeBlockPos(dock.pos());
            buf.writeUtf(dock.dockName(), 64);
            PacketStringCodec.writeUtfSafe(buf,dock.ownerName(),64);
            PacketStringCodec.writeUtfSafe(buf,dock.nationName(),64);
            buf.writeVarInt(dock.distance());
        }
    }

    public static SyncAutoRouteDocksPacket decode(FriendlyByteBuf buf) {
        BlockPos sourceDockPos = buf.readBlockPos();
        java.util.UUID requestId=buf.readUUID();
        TransportTerminalKind terminalKind = buf.readEnum(TransportTerminalKind.class);
        int size = buf.readVarInt();
        if(size<0||size>4096)throw new IllegalArgumentException("Dock list size");
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
        return new SyncAutoRouteDocksPacket(sourceDockPos, terminalKind, docks,requestId);
    }

    public static void handle(SyncAutoRouteDocksPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
            DockClientHooks.openAutoRouteDockSelection(msg.sourceDockPos, msg.terminalKind, msg.docks,msg.requestId)
        ));
        ctx.get().setPacketHandled(true);
    }
}
