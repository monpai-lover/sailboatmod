package com.monpai.sailboatmod.network.packet.rail;

import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import java.util.Objects;
import java.util.function.Supplier;

public record RailTerminalSnapshotPacket(CompoundTag data, boolean open) {
    public RailTerminalSnapshotPacket { data = Objects.requireNonNull(data).copy(); }
    public static void encode(RailTerminalSnapshotPacket p, FriendlyByteBuf b) { b.writeNbt(p.data); b.writeBoolean(p.open); }
    public static RailTerminalSnapshotPacket decode(FriendlyByteBuf b) { return new RailTerminalSnapshotPacket(b.readNbt(), b.readBoolean()); }
    public static void handle(RailTerminalSnapshotPacket p, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.monpai.sailboatmod.client.screen.RailFreightTerminalScreen.openOrUpdate(p.data, p.open)));
        context.setPacketHandled(true);
    }
}
