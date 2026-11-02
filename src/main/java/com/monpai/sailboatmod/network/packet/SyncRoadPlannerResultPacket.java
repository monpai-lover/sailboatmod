package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record SyncRoadPlannerResultPacket(String sourceTownName,
                                          String targetTownName,
                                          List<OptionEntry> options,
                                          String selectedOptionId) {
    public record OptionEntry(String optionId, String label, int pathNodeCount, boolean bridgeBacked) {
        public OptionEntry {
            optionId = optionId == null ? "" : optionId;
            label = label == null ? "" : label;
            pathNodeCount = Math.max(0, pathNodeCount);
        }
    }

    public SyncRoadPlannerResultPacket {
        sourceTownName = sourceTownName == null ? "" : sourceTownName;
        targetTownName = targetTownName == null ? "" : targetTownName;
        options = options == null ? List.of() : List.copyOf(options);
        selectedOptionId = selectedOptionId == null ? "" : selectedOptionId;
    }

    public static void encode(SyncRoadPlannerResultPacket msg, FriendlyByteBuf buf) {
        PacketStringCodec.writeUtfSafe(buf, msg.sourceTownName(), 64);
        PacketStringCodec.writeUtfSafe(buf, msg.targetTownName(), 64);
        buf.writeVarInt(msg.options().size());
        for (OptionEntry option : msg.options()) {
            PacketStringCodec.writeUtfSafe(buf, option.optionId(), 32);
            PacketStringCodec.writeUtfSafe(buf, option.label(), 64);
            buf.writeVarInt(option.pathNodeCount());
            buf.writeBoolean(option.bridgeBacked());
        }
        PacketStringCodec.writeUtfSafe(buf, msg.selectedOptionId(), 32);
    }

    public static SyncRoadPlannerResultPacket decode(FriendlyByteBuf buf) {
        String sourceTownName = buf.readUtf(64);
        String targetTownName = buf.readUtf(64);
        int optionCount = buf.readVarInt();
        List<OptionEntry> options = new ArrayList<>(optionCount);
        for (int i = 0; i < optionCount; i++) {
            options.add(new OptionEntry(buf.readUtf(32), buf.readUtf(64), buf.readVarInt(), buf.readBoolean()));
        }
        String selectedOptionId = buf.readUtf(32);
        return new SyncRoadPlannerResultPacket(sourceTownName, targetTownName, options, selectedOptionId);
    }

    public static void handle(SyncRoadPlannerResultPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg)));
        ctx.get().setPacketHandled(true);
    }

    public static void handleClientForTest(SyncRoadPlannerResultPacket msg) {
        RoadPlannerClientHooks.applyPlanningResult(
                msg.sourceTownName(),
                msg.targetTownName(),
                msg.options().stream()
                        .map(option -> new RoadPlannerClientHooks.PreviewOption(
                                option.optionId(),
                                option.label(),
                                option.pathNodeCount(),
                                option.bridgeBacked()
                        ))
                        .toList(),
                msg.selectedOptionId()
        );
    }

    @net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncRoadPlannerResultPacket msg) {
        RoadPlannerClientHooks.showPlanningResult(
                msg.sourceTownName(),
                msg.targetTownName(),
                msg.options().stream()
                        .map(option -> new RoadPlannerClientHooks.PreviewOption(
                                option.optionId(),
                                option.label(),
                                option.pathNodeCount(),
                                option.bridgeBacked()
                        ))
                        .toList(),
                msg.selectedOptionId()
        );
    }
}
