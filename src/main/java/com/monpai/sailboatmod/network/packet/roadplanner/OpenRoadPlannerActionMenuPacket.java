package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public record OpenRoadPlannerActionMenuPacket(RoadPlannerActionMenuMode mode, UUID sessionId) {
    public OpenRoadPlannerActionMenuPacket {
        mode = mode == null ? RoadPlannerActionMenuMode.MAIN : mode;
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
    }

    public static void encode(OpenRoadPlannerActionMenuPacket packet, FriendlyByteBuf buffer) {
        buffer.writeEnum(packet.mode());
        RoadPlannerPacketCodec.writeUuid(buffer, packet.sessionId());
    }

    public static OpenRoadPlannerActionMenuPacket decode(FriendlyByteBuf buffer) {
        return new OpenRoadPlannerActionMenuPacket(buffer.readEnum(RoadPlannerActionMenuMode.class), RoadPlannerPacketCodec.readUuid(buffer));
    }

    public static void handle(OpenRoadPlannerActionMenuPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        contextSupplier.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.openActionMenu(packet.mode(), packet.sessionId())));
        contextSupplier.get().setPacketHandled(true);
    }
}
