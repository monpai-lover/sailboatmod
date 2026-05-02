package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerScreen;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlan;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerMapPreloadProgressPacket(UUID sessionId,
                                                  long requestId,
                                                  RoadPlannerMapPreloadRequestPacket.Purpose purpose,
                                                  String worldId,
                                                  String dimensionId,
                                                  RoadMapRoutePreloadPlan.CoverageMode coverageMode,
                                                  int completedTiles,
                                                  int totalTiles,
                                                  State state,
                                                  String message) {
    public RoadPlannerMapPreloadProgressPacket {
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        purpose = purpose == null ? RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD : purpose;
        worldId = worldId == null ? "" : worldId;
        dimensionId = dimensionId == null ? "" : dimensionId;
        coverageMode = coverageMode == null ? RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY : coverageMode;
        state = state == null ? State.QUEUED : state;
        message = message == null ? "" : message;
        completedTiles = Math.max(0, completedTiles);
        totalTiles = Math.max(0, totalTiles);
    }

    public static void encode(RoadPlannerMapPreloadProgressPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
        buffer.writeVarLong(packet.requestId());
        buffer.writeEnum(packet.purpose());
        RoadPlannerPacketCodec.writeString(buffer, packet.worldId(), 128);
        RoadPlannerPacketCodec.writeString(buffer, packet.dimensionId(), 128);
        buffer.writeEnum(packet.coverageMode());
        buffer.writeVarInt(packet.completedTiles());
        buffer.writeVarInt(packet.totalTiles());
        buffer.writeEnum(packet.state());
        RoadPlannerPacketCodec.writeString(buffer, packet.message(), 256);
    }

    public static RoadPlannerMapPreloadProgressPacket decode(FriendlyByteBuf buffer) {
        return new RoadPlannerMapPreloadProgressPacket(
                RoadPlannerPacketCodec.readUuid(buffer),
                buffer.readVarLong(),
                buffer.readEnum(RoadPlannerMapPreloadRequestPacket.Purpose.class),
                buffer.readUtf(128),
                buffer.readUtf(128),
                buffer.readEnum(RoadMapRoutePreloadPlan.CoverageMode.class),
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readEnum(State.class),
                buffer.readUtf(256));
    }

    public static void handle(RoadPlannerMapPreloadProgressPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleOnClient(packet)));
        contextSupplier.get().setPacketHandled(true);
    }

    private static void handleOnClient(RoadPlannerMapPreloadProgressPacket packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof RoadPlannerScreen screen) {
            screen.applyMapPreloadProgress(packet);
        }
    }

    public enum State {
        QUEUED,
        SAMPLING,
        DERIVING,
        DEGRADED_PATH_ONLY,
        COMPLETE,
        FAILED,
        CANCELLED
    }
}
