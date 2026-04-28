package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerClaimOverlay;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public class OpenRoadPlannerScreenPacket {
    private final boolean offhand;
    private final String sourceTownName;
    private final String selectedTownId;
    private final List<RoadPlannerClientHooks.TargetEntry> targets;
    private final UUID sessionId;
    private final String sourceTownId;
    private final BlockPos sourceAnchor;
    private final BlockPos destinationAnchor;
    private final List<RoadPlannerClaimOverlay> claimOverlays;

    public OpenRoadPlannerScreenPacket(boolean offhand,
                                       String sourceTownName,
                                       String selectedTownId,
                                       List<RoadPlannerClientHooks.TargetEntry> targets) {
        this(offhand, sourceTownName, selectedTownId, targets, UUID.randomUUID(), "", BlockPos.ZERO, new BlockPos(160, 64, 0));
    }

    public OpenRoadPlannerScreenPacket(boolean offhand,
                                       String sourceTownName,
                                       String selectedTownId,
                                       List<RoadPlannerClientHooks.TargetEntry> targets,
                                       UUID sessionId,
                                       String sourceTownId,
                                       BlockPos sourceAnchor,
                                       BlockPos destinationAnchor) {
        this(offhand, sourceTownName, selectedTownId, targets, sessionId, sourceTownId, sourceAnchor, destinationAnchor, List.of());
    }

    public OpenRoadPlannerScreenPacket(boolean offhand,
                                       String sourceTownName,
                                       String selectedTownId,
                                       List<RoadPlannerClientHooks.TargetEntry> targets,
                                       UUID sessionId,
                                       String sourceTownId,
                                       BlockPos sourceAnchor,
                                       BlockPos destinationAnchor,
                                       List<RoadPlannerClaimOverlay> claimOverlays) {
        this.offhand = offhand;
        this.sourceTownName = sourceTownName == null ? "" : sourceTownName;
        this.selectedTownId = selectedTownId == null ? "" : selectedTownId;
        this.targets = targets == null ? List.of() : List.copyOf(targets);
        this.sessionId = sessionId == null ? UUID.randomUUID() : sessionId;
        this.sourceTownId = sourceTownId == null ? "" : sourceTownId;
        this.sourceAnchor = sourceAnchor == null ? BlockPos.ZERO : sourceAnchor.immutable();
        this.destinationAnchor = destinationAnchor == null ? new BlockPos(160, 64, 0) : destinationAnchor.immutable();
        this.claimOverlays = claimOverlays == null ? List.of() : List.copyOf(claimOverlays);
    }

    public UUID sessionId() {
        return sessionId;
    }

    public String sourceTownId() {
        return sourceTownId;
    }

    public String selectedTownId() {
        return selectedTownId;
    }

    public BlockPos sourceAnchor() {
        return sourceAnchor;
    }

    public BlockPos destinationAnchor() {
        return destinationAnchor;
    }

    public List<RoadPlannerClaimOverlay> claimOverlays() {
        return claimOverlays;
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
        buf.writeUUID(msg.sessionId);
        buf.writeUtf(msg.sourceTownId, 40);
        buf.writeBlockPos(msg.sourceAnchor);
        buf.writeBlockPos(msg.destinationAnchor);
        buf.writeVarInt(msg.claimOverlays.size());
        for (RoadPlannerClaimOverlay overlay : msg.claimOverlays) {
            buf.writeVarInt(overlay.chunkX());
            buf.writeVarInt(overlay.chunkZ());
            buf.writeUtf(overlay.townId(), 40);
            buf.writeUtf(overlay.townName(), 64);
            buf.writeUtf(overlay.nationId(), 40);
            buf.writeUtf(overlay.nationName(), 64);
            buf.writeEnum(overlay.role());
            buf.writeVarInt(overlay.primaryColorRgb());
            buf.writeVarInt(overlay.secondaryColorRgb());
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
        UUID sessionId = buf.readUUID();
        String sourceTownId = buf.readUtf(40);
        BlockPos sourceAnchor = buf.readBlockPos();
        BlockPos destinationAnchor = buf.readBlockPos();
        int overlaySize = buf.readVarInt();
        List<RoadPlannerClaimOverlay> claimOverlays = new ArrayList<>(overlaySize);
        for (int i = 0; i < overlaySize; i++) {
            claimOverlays.add(new RoadPlannerClaimOverlay(
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readUtf(40),
                    buf.readUtf(64),
                    buf.readUtf(40),
                    buf.readUtf(64),
                    buf.readEnum(RoadPlannerClaimOverlay.Role.class),
                    buf.readVarInt(),
                    buf.readVarInt()
            ));
        }
        return new OpenRoadPlannerScreenPacket(offhand, sourceTownName, selectedTownId, targets,
                sessionId, sourceTownId, sourceAnchor, destinationAnchor, claimOverlays);
    }

    public static void handle(OpenRoadPlannerScreenPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                openClientPlanner(msg)));
        ctx.get().setPacketHandled(true);
    }

    private static void openClientPlanner(OpenRoadPlannerScreenPacket msg) {
        if (msg.sourceTownId.isBlank() || msg.selectedTownId.isBlank()
                || msg.sourceAnchor.equals(BlockPos.ZERO) || msg.destinationAnchor.equals(new BlockPos(160, 64, 0))) {
            RoadPlannerClientHooks.openTargetSelection(msg.offhand, msg.sourceTownName, msg.targets, msg.selectedTownId);
            return;
        }
        RoadPlannerClientHooks.openNewPlannerEntry(
                msg.sessionId,
                msg.sourceTownId,
                msg.sourceTownName,
                msg.sourceAnchor,
                msg.selectedTownId,
                targetName(msg.selectedTownId, msg.targets),
                msg.destinationAnchor,
                msg.claimOverlays);
    }

    private static String targetName(String selectedTownId, List<RoadPlannerClientHooks.TargetEntry> targets) {
        if (selectedTownId == null || targets == null) {
            return "";
        }
        for (RoadPlannerClientHooks.TargetEntry target : targets) {
            if (target.townId().equalsIgnoreCase(selectedTownId)) {
                return target.townName();
            }
        }
        return "";
    }
}
