package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class OpenRoadPlannerScreenPacket {
    private final boolean offhand;
    private final String sourceTownName;
    private final String selectedTownId;
    private final List<RoadPlannerClientHooks.TargetEntry> targets;

    public OpenRoadPlannerScreenPacket(boolean offhand,
                                       String sourceTownName,
                                       String selectedTownId,
                                       List<RoadPlannerClientHooks.TargetEntry> targets) {
        this.offhand = offhand;
        this.sourceTownName = sourceTownName == null ? "" : sourceTownName;
        this.selectedTownId = selectedTownId == null ? "" : selectedTownId;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
    }

    public static void encode(OpenRoadPlannerScreenPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.offhand);
        buf.writeUtf(msg.sourceTownName, 64);
        buf.writeUtf(msg.selectedTownId, 40);
        buf.writeVarInt(msg.targets.size());
        for (RoadPlannerClientHooks.TargetEntry entry : msg.targets) {
            buf.writeUtf(entry.townId(), 40);
            buf.writeUtf(entry.townName(), 64);
            buf.writeVarInt(entry.distanceBlocks());
        }
    }

    public static OpenRoadPlannerScreenPacket decode(FriendlyByteBuf buf) {
        boolean offhand = buf.readBoolean();
        String sourceTownName = buf.readUtf(64);
        String selectedTownId = buf.readUtf(40);
        int size = buf.readVarInt();
        List<RoadPlannerClientHooks.TargetEntry> targets = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            targets.add(new RoadPlannerClientHooks.TargetEntry(
                    buf.readUtf(40),
                    buf.readUtf(64),
                    buf.readVarInt()
            ));
        }
        return new OpenRoadPlannerScreenPacket(offhand, sourceTownName, selectedTownId, targets);
    }

    public static void handle(OpenRoadPlannerScreenPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                RoadPlannerClientHooks.openTargetSelection(msg.offhand, msg.sourceTownName, msg.targets, msg.selectedTownId)));
        ctx.get().setPacketHandled(true);
    }
}
