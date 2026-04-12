package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class SyncRoadPlannerPreviewPacket {
    public record GhostBlock(BlockPos pos, BlockState state) {
        public GhostBlock {
            pos = Objects.requireNonNull(pos, "pos").immutable();
            state = Objects.requireNonNull(state, "state");
        }
    }

    private final String sourceTownName;
    private final String targetTownName;
    private final List<GhostBlock> ghostBlocks;
    private final int pathNodeCount;
    private final BlockPos startHighlightPos;
    private final BlockPos endHighlightPos;
    private final BlockPos focusPos;
    private final boolean awaitingConfirmation;

    public SyncRoadPlannerPreviewPacket(String sourceTownName,
                                        String targetTownName,
                                        List<GhostBlock> ghostBlocks,
                                        int pathNodeCount,
                                        BlockPos startHighlightPos,
                                        BlockPos endHighlightPos,
                                        BlockPos focusPos,
                                        boolean awaitingConfirmation) {
        this.sourceTownName = sourceTownName == null ? "" : sourceTownName;
        this.targetTownName = targetTownName == null ? "" : targetTownName;
        this.ghostBlocks = ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
        this.pathNodeCount = Math.max(0, pathNodeCount);
        this.startHighlightPos = immutable(startHighlightPos);
        this.endHighlightPos = immutable(endHighlightPos);
        this.focusPos = immutable(focusPos);
        this.awaitingConfirmation = awaitingConfirmation;
    }

    public static SyncRoadPlannerPreviewPacket fromPlan(String sourceTownName,
                                                        String targetTownName,
                                                        RoadPlacementPlan plan,
                                                        boolean awaitingConfirmation) {
        return new SyncRoadPlannerPreviewPacket(
                sourceTownName,
                targetTownName,
                plan == null ? List.of() : plan.ghostBlocks().stream()
                        .map(block -> new GhostBlock(block.pos(), block.state()))
                        .toList(),
                plan == null ? 0 : plan.centerPath().size(),
                plan == null ? null : plan.startHighlightPos(),
                plan == null ? null : plan.endHighlightPos(),
                plan == null ? null : plan.focusPos(),
                awaitingConfirmation
        );
    }

    public String sourceTownName() {
        return sourceTownName;
    }

    public String targetTownName() {
        return targetTownName;
    }

    public List<GhostBlock> ghostBlocks() {
        return ghostBlocks;
    }

    public int pathNodeCount() {
        return pathNodeCount;
    }

    public BlockPos startHighlightPos() {
        return startHighlightPos;
    }

    public BlockPos endHighlightPos() {
        return endHighlightPos;
    }

    public BlockPos focusPos() {
        return focusPos;
    }

    public boolean awaitingConfirmation() {
        return awaitingConfirmation;
    }

    public static void encode(SyncRoadPlannerPreviewPacket msg, FriendlyByteBuf buf) {
        PacketStringCodec.writeUtfSafe(buf, msg.sourceTownName, 64);
        PacketStringCodec.writeUtfSafe(buf, msg.targetTownName, 64);
        writeGhostBlocks(buf, msg.ghostBlocks);
        buf.writeVarInt(msg.pathNodeCount);
        writeOptionalPos(buf, msg.startHighlightPos);
        writeOptionalPos(buf, msg.endHighlightPos);
        writeOptionalPos(buf, msg.focusPos);
        buf.writeBoolean(msg.awaitingConfirmation);
    }

    public static SyncRoadPlannerPreviewPacket decode(FriendlyByteBuf buf) {
        String sourceTownName = buf.readUtf(64);
        String targetTownName = buf.readUtf(64);
        List<GhostBlock> ghostBlocks = readGhostBlocks(buf);
        int pathNodeCount = buf.readVarInt();
        BlockPos startHighlightPos = readOptionalPos(buf);
        BlockPos endHighlightPos = readOptionalPos(buf);
        BlockPos focusPos = readOptionalPos(buf);
        boolean awaitingConfirmation = buf.readBoolean();
        return new SyncRoadPlannerPreviewPacket(
                sourceTownName,
                targetTownName,
                ghostBlocks,
                pathNodeCount,
                startHighlightPos,
                endHighlightPos,
                focusPos,
                awaitingConfirmation
        );
    }

    public static void handle(SyncRoadPlannerPreviewPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> handleClient(msg)));
        ctx.get().setPacketHandled(true);
    }

    @net.minecraftforge.api.distmarker.OnlyIn(Dist.CLIENT)
    private static void handleClient(SyncRoadPlannerPreviewPacket msg) {
        if (msg.ghostBlocks.isEmpty()) {
            RoadPlannerClientHooks.clearPreview();
            return;
        }
        RoadPlannerClientHooks.updatePreview(new RoadPlannerClientHooks.PreviewState(
                msg.sourceTownName,
                msg.targetTownName,
                msg.ghostBlocks.stream()
                        .map(block -> new RoadPlannerClientHooks.PreviewGhostBlock(block.pos(), block.state()))
                        .toList(),
                msg.pathNodeCount,
                msg.startHighlightPos,
                msg.endHighlightPos,
                msg.focusPos,
                msg.awaitingConfirmation
        ));
    }

    private static void writeGhostBlocks(FriendlyByteBuf buf, List<GhostBlock> blocks) {
        List<GhostBlock> safeBlocks = blocks == null ? List.of() : blocks;
        buf.writeVarInt(safeBlocks.size());
        for (GhostBlock block : safeBlocks) {
            buf.writeBlockPos(block.pos());
            buf.writeVarInt(Block.getId(block.state()));
        }
    }

    private static List<GhostBlock> readGhostBlocks(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<GhostBlock> blocks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            blocks.add(new GhostBlock(buf.readBlockPos(), Block.stateById(buf.readVarInt())));
        }
        return List.copyOf(blocks);
    }

    private static void writeOptionalPos(FriendlyByteBuf buf, BlockPos pos) {
        buf.writeBoolean(pos != null);
        if (pos != null) {
            buf.writeBlockPos(pos);
        }
    }

    private static BlockPos readOptionalPos(FriendlyByteBuf buf) {
        return buf.readBoolean() ? buf.readBlockPos() : null;
    }

    private static BlockPos immutable(BlockPos pos) {
        return pos == null ? null : pos.immutable();
    }
}
