package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.network.NetworkEvent;
import com.monpai.sailboatmod.route.water.RouteTaskRegistry;
import com.monpai.sailboatmod.route.water.RouteTaskView;
import com.monpai.sailboatmod.util.ClientSideExecutor;
import net.minecraft.network.FriendlyByteBuf;
import net.neoforged.api.distmarker.Dist;
import java.util.*;
import java.util.function.Supplier;

public record SyncRouteTasksPacket(UUID query, List<RouteTaskView> tasks) {
    public SyncRouteTasksPacket {
        tasks = List.copyOf(tasks);
        if (tasks.size() > RouteTaskRegistry.MAX_HISTORY) throw new IllegalArgumentException("Too many route tasks");
    }
    public static void encode(SyncRouteTasksPacket packet, FriendlyByteBuf buf) {
        buf.writeUUID(packet.query);
        buf.writeVarInt(packet.tasks.size());
        for (RouteTaskView task : packet.tasks) {
            buf.writeUUID(task.id()); buf.writeUtf(task.dimension(), 128);
            buf.writeBlockPos(task.source()); buf.writeBlockPos(task.target());
            buf.writeVarInt(task.percent()); buf.writeVarInt(task.state()); buf.writeUtf(task.stage(), 128);
            buf.writeLong(task.startedMillis()); buf.writeLong(task.updatedMillis());
        }
    }
    public static SyncRouteTasksPacket decode(FriendlyByteBuf buf) {
        UUID query = buf.readUUID();
        int count = buf.readVarInt();
        if (count < 0 || count > RouteTaskRegistry.MAX_HISTORY) throw new IllegalArgumentException("Invalid route task count");
        List<RouteTaskView> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) tasks.add(new RouteTaskView(buf.readUUID(), buf.readUtf(128),
                buf.readBlockPos(), buf.readBlockPos(), buf.readVarInt(), buf.readVarInt(), buf.readUtf(128),
                buf.readLong(), buf.readLong()));
        return new SyncRouteTasksPacket(query, tasks);
    }
    public static void handle(SyncRouteTasksPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientSideExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                com.monpai.sailboatmod.client.DockClientHooks.routeTasks(packet)));
        ctx.get().setPacketHandled(true);
    }
}
