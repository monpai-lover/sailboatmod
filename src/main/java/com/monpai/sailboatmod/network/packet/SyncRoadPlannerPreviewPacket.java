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

public class SyncRoadPlannerPreviewPacket {
    private final String sourceTownName;
    private final String targetTownName;
    private final List<BlockPos> path;
    private final boolean awaitingConfirmation;

    public SyncRoadPlannerPreviewPacket(String sourceTownName,
                                        String targetTownName,
                                        List<BlockPos> path,
                                        boolean awaitingConfirmation) {
        this.sourceTownName = sourceTownName == null ? "" : sourceTownName;
        this.targetTownName = targetTownName == null ? "" : targetTownName;
        this.path = path == null ? List.of() : List.copyOf(path);
        this.awaitingConfirmation = awaitingConfirmation;
    }

    public static void encode(SyncRoadPlannerPreviewPacket msg, FriendlyByteBuf buf) {
        buf.writeUtf(msg.sourceTownName, 64);
        buf.writeUtf(msg.targetTownName, 64);
        buf.writeVarInt(msg.path.size());
        for (BlockPos pos : msg.path) {
            buf.writeBlockPos(pos);
        }
        buf.writeBoolean(msg.awaitingConfirmation);
    }

    public static SyncRoadPlannerPreviewPacket decode(FriendlyByteBuf buf) {
        String sourceTownName = buf.readUtf(64);
        String targetTownName = buf.readUtf(64);
        int size = buf.readVarInt();
        List<BlockPos> path = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            path.add(buf.readBlockPos());
        }
        boolean awaitingConfirmation = buf.readBoolean();
        return new SyncRoadPlannerPreviewPacket(sourceTownName, targetTownName, path, awaitingConfirmation);
    }

    public static void handle(SyncRoadPlannerPreviewPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg)));
        ctx.get().setPacketHandled(true);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncRoadPlannerPreviewPacket msg) {
        if (msg.path.isEmpty()) {
            RoadPlannerClientHooks.clearPreview();
            return;
        }
        RoadPlannerClientHooks.updatePreview(new RoadPlannerClientHooks.PreviewState(
                msg.sourceTownName,
                msg.targetTownName,
                msg.path,
                msg.awaitingConfirmation
        ));
    }
}
