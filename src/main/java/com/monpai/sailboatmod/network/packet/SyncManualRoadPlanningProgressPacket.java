package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record SyncManualRoadPlanningProgressPacket(long requestId,
                                                   String sourceTownName,
                                                   String targetTownName,
                                                   String stageKey,
                                                   String stageLabel,
                                                   int overallPercent,
                                                   int stagePercent,
                                                   Status status) {
    public enum Status {
        RUNNING,
        SUCCESS,
        FAILED,
        CANCELLED
    }

    public SyncManualRoadPlanningProgressPacket {
        sourceTownName = sourceTownName == null ? "" : sourceTownName;
        targetTownName = targetTownName == null ? "" : targetTownName;
        stageKey = stageKey == null ? "" : stageKey;
        stageLabel = stageLabel == null ? "" : stageLabel;
        overallPercent = clampPercent(overallPercent);
        stagePercent = clampPercent(stagePercent);
        status = status == null ? Status.RUNNING : status;
    }

    public static void encode(SyncManualRoadPlanningProgressPacket msg, FriendlyByteBuf buf) {
        buf.writeVarLong(Math.max(0L, msg.requestId()));
        PacketStringCodec.writeUtfSafe(buf, msg.sourceTownName(), 64);
        PacketStringCodec.writeUtfSafe(buf, msg.targetTownName(), 64);
        PacketStringCodec.writeUtfSafe(buf, msg.stageKey(), 32);
        PacketStringCodec.writeUtfSafe(buf, msg.stageLabel(), 64);
        buf.writeVarInt(msg.overallPercent());
        buf.writeVarInt(msg.stagePercent());
        buf.writeEnum(msg.status());
    }

    public static SyncManualRoadPlanningProgressPacket decode(FriendlyByteBuf buf) {
        return new SyncManualRoadPlanningProgressPacket(
                buf.readVarLong(),
                buf.readUtf(64),
                buf.readUtf(64),
                buf.readUtf(32),
                buf.readUtf(64),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readEnum(Status.class)
        );
    }

    public static void handle(SyncManualRoadPlanningProgressPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg)));
        ctx.get().setPacketHandled(true);
    }

    public static void handleClientForTest(SyncManualRoadPlanningProgressPacket msg) {
        handleClient(msg);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncManualRoadPlanningProgressPacket msg) {
        RoadPlannerClientHooks.updatePlanningProgress(msg);
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
