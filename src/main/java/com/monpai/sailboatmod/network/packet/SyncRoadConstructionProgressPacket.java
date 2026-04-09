package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class SyncRoadConstructionProgressPacket {
    public record Entry(String roadId,
                        String sourceTownName,
                        String targetTownName,
                        BlockPos focusPos,
                        int progressPercent,
                        int activeWorkers) {
    }

    private final List<Entry> entries;

    public SyncRoadConstructionProgressPacket(List<Entry> entries) {
        this.entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static void encode(SyncRoadConstructionProgressPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entries.size());
        for (Entry entry : msg.entries) {
            buf.writeUtf(entry.roadId(), ConstructionPacketStringLimits.MAX_ROAD_ID_LENGTH);
            buf.writeUtf(entry.sourceTownName(), 64);
            buf.writeUtf(entry.targetTownName(), 64);
            buf.writeBlockPos(entry.focusPos());
            buf.writeVarInt(entry.progressPercent());
            buf.writeVarInt(entry.activeWorkers());
        }
    }

    public static SyncRoadConstructionProgressPacket decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new Entry(
                    buf.readUtf(ConstructionPacketStringLimits.MAX_ROAD_ID_LENGTH),
                    buf.readUtf(64),
                    buf.readUtf(64),
                    buf.readBlockPos(),
                    buf.readVarInt(),
                    buf.readVarInt()
            ));
        }
        return new SyncRoadConstructionProgressPacket(entries);
    }

    public static void handle(SyncRoadConstructionProgressPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg)));
        ctx.get().setPacketHandled(true);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncRoadConstructionProgressPacket msg) {
        List<RoadPlannerClientHooks.ProgressState> progress = msg.entries.stream()
                .map(entry -> new RoadPlannerClientHooks.ProgressState(
                        entry.roadId(),
                        entry.sourceTownName(),
                        entry.targetTownName(),
                        entry.focusPos(),
                        entry.progressPercent(),
                        entry.activeWorkers()))
                .toList();
        RoadPlannerClientHooks.updateProgress(progress);
    }
}
