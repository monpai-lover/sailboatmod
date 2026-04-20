package com.monpai.sailboatmod.network.packet;

import com.monpai.sailboatmod.client.RoadPlannerClientHooks;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
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

    public record PreviewOption(String optionId, String label, int pathNodeCount, boolean bridgeBacked) {
        public PreviewOption {
            optionId = optionId == null ? "" : optionId;
            label = label == null ? "" : label;
            pathNodeCount = Math.max(0, pathNodeCount);
        }
    }

    public record BridgeRange(int startIndex, int endIndex) {
        public BridgeRange {
            startIndex = Math.max(0, startIndex);
            endIndex = Math.max(startIndex, endIndex);
        }
    }

    private final String sourceTownName;
    private final String targetTownName;
    private final List<GhostBlock> ghostBlocks;
    private final List<BlockPos> pathNodes;
    private final int pathNodeCount;
    private final BlockPos startHighlightPos;
    private final BlockPos endHighlightPos;
    private final BlockPos focusPos;
    private final boolean awaitingConfirmation;
    private final List<PreviewOption> options;
    private final String selectedOptionId;
    private final List<BridgeRange> bridgeRanges;

    public SyncRoadPlannerPreviewPacket(String sourceTownName,
                                        String targetTownName,
                                        List<GhostBlock> ghostBlocks,
                                        List<BlockPos> pathNodes,
                                        int pathNodeCount,
                                        BlockPos startHighlightPos,
                                        BlockPos endHighlightPos,
                                        BlockPos focusPos,
                                        boolean awaitingConfirmation,
                                        List<PreviewOption> options,
                                        String selectedOptionId,
                                        List<BridgeRange> bridgeRanges) {
        this.sourceTownName = sourceTownName == null ? "" : sourceTownName;
        this.targetTownName = targetTownName == null ? "" : targetTownName;
        this.ghostBlocks = ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
        this.pathNodes = immutablePositions(pathNodes);
        this.pathNodeCount = Math.max(0, pathNodeCount);
        this.startHighlightPos = immutable(startHighlightPos);
        this.endHighlightPos = immutable(endHighlightPos);
        this.focusPos = immutable(focusPos);
        this.awaitingConfirmation = awaitingConfirmation;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.selectedOptionId = selectedOptionId == null ? "" : selectedOptionId;
        this.bridgeRanges = bridgeRanges == null ? List.of() : List.copyOf(bridgeRanges);
    }

    public static SyncRoadPlannerPreviewPacket fromPlan(String sourceTownName,
                                                        String targetTownName,
                                                        Object plan,
                                                        boolean awaitingConfirmation) {
        if (!(plan instanceof RoadPlacementPlan rpp) || rpp.centerPath() == null || rpp.centerPath().size() < 2) {
            return new SyncRoadPlannerPreviewPacket(
                    sourceTownName,
                    targetTownName,
                    List.of(),
                    List.of(),
                    0,
                    null,
                    null,
                    null,
                    awaitingConfirmation,
                    List.of(),
                    "",
                    List.of()
            );
        }
        List<GhostBlock> ghostBlocks = new ArrayList<>();
        if (rpp.ghostBlocks() != null) {
            for (RoadGeometryPlanner.GhostRoadBlock ghost : rpp.ghostBlocks()) {
                if (ghost.pos() != null && ghost.state() != null) {
                    ghostBlocks.add(new GhostBlock(ghost.pos(), ghost.state()));
                }
            }
        }
        List<BlockPos> pathNodes = structuredPreviewPathNodes(plan);
        List<BridgeRange> bridgeRanges = new ArrayList<>();
        if (rpp.bridgeRanges() != null) {
            for (RoadPlacementPlan.BridgeRange br : rpp.bridgeRanges()) {
                bridgeRanges.add(new BridgeRange(br.startIndex(), br.endIndex()));
            }
        }
        return new SyncRoadPlannerPreviewPacket(
                sourceTownName,
                targetTownName,
                ghostBlocks,
                pathNodes,
                rpp.centerPath().size(),
                rpp.startHighlightPos(),
                rpp.endHighlightPos(),
                rpp.focusPos(),
                awaitingConfirmation,
                List.of(),
                "",
                bridgeRanges
        );
    }

    private static List<BlockPos> structuredPreviewPathNodes(Object plan) {
        if (!(plan instanceof RoadPlacementPlan rpp) || rpp.centerPath() == null) {
            return List.of();
        }
        List<BlockPos> path = rpp.centerPath();
        if (path.size() <= 64) {
            return List.copyOf(path);
        }
        List<BlockPos> sampled = new ArrayList<>();
        sampled.add(path.get(0));
        int step = Math.max(1, path.size() / 62);
        for (int i = step; i < path.size() - 1; i += step) {
            sampled.add(path.get(i));
        }
        sampled.add(path.get(path.size() - 1));
        return List.copyOf(sampled);
    }

    public SyncRoadPlannerPreviewPacket withOptions(List<PreviewOption> options, String selectedOptionId) {
        return new SyncRoadPlannerPreviewPacket(
                sourceTownName,
                targetTownName,
                ghostBlocks,
                pathNodes,
                pathNodeCount,
                startHighlightPos,
                endHighlightPos,
                focusPos,
                awaitingConfirmation,
                options,
                selectedOptionId,
                bridgeRanges
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

    public List<BlockPos> pathNodes() {
        return pathNodes;
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

    public List<PreviewOption> options() {
        return options;
    }

    public String selectedOptionId() {
        return selectedOptionId;
    }

    public List<BridgeRange> bridgeRanges() {
        return bridgeRanges;
    }

    public static void encode(SyncRoadPlannerPreviewPacket msg, FriendlyByteBuf buf) {
        PacketStringCodec.writeUtfSafe(buf, msg.sourceTownName, 64);
        PacketStringCodec.writeUtfSafe(buf, msg.targetTownName, 64);
        writeGhostBlocks(buf, msg.ghostBlocks);
        writePathNodes(buf, msg.pathNodes);
        buf.writeVarInt(msg.pathNodeCount);
        writeOptionalPos(buf, msg.startHighlightPos);
        writeOptionalPos(buf, msg.endHighlightPos);
        writeOptionalPos(buf, msg.focusPos);
        buf.writeBoolean(msg.awaitingConfirmation);
        buf.writeVarInt(msg.options.size());
        for (PreviewOption option : msg.options) {
            PacketStringCodec.writeUtfSafe(buf, option.optionId(), 32);
            PacketStringCodec.writeUtfSafe(buf, option.label(), 64);
            buf.writeVarInt(option.pathNodeCount());
            buf.writeBoolean(option.bridgeBacked());
        }
        PacketStringCodec.writeUtfSafe(buf, msg.selectedOptionId, 32);
        buf.writeVarInt(msg.bridgeRanges.size());
        for (BridgeRange range : msg.bridgeRanges) {
            buf.writeVarInt(range.startIndex());
            buf.writeVarInt(range.endIndex());
        }
    }

    public static SyncRoadPlannerPreviewPacket decode(FriendlyByteBuf buf) {
        String sourceTownName = buf.readUtf(64);
        String targetTownName = buf.readUtf(64);
        List<GhostBlock> ghostBlocks = readGhostBlocks(buf);
        List<BlockPos> pathNodes = readPathNodes(buf);
        int pathNodeCount = buf.readVarInt();
        BlockPos startHighlightPos = readOptionalPos(buf);
        BlockPos endHighlightPos = readOptionalPos(buf);
        BlockPos focusPos = readOptionalPos(buf);
        boolean awaitingConfirmation = buf.readBoolean();
        int optionCount = buf.readVarInt();
        List<PreviewOption> options = new ArrayList<>(optionCount);
        for (int i = 0; i < optionCount; i++) {
            options.add(new PreviewOption(buf.readUtf(32), buf.readUtf(64), buf.readVarInt(), buf.readBoolean()));
        }
        String selectedOptionId = buf.readUtf(32);
        int bridgeRangeCount = buf.readVarInt();
        List<BridgeRange> bridgeRanges = new ArrayList<>(bridgeRangeCount);
        for (int i = 0; i < bridgeRangeCount; i++) {
            bridgeRanges.add(new BridgeRange(buf.readVarInt(), buf.readVarInt()));
        }
        return new SyncRoadPlannerPreviewPacket(
                sourceTownName,
                targetTownName,
                ghostBlocks,
                pathNodes,
                pathNodeCount,
                startHighlightPos,
                endHighlightPos,
                focusPos,
                awaitingConfirmation,
                options,
                selectedOptionId,
                bridgeRanges
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
                msg.pathNodes,
                msg.pathNodeCount,
                msg.startHighlightPos,
                msg.endHighlightPos,
                msg.focusPos,
                msg.awaitingConfirmation,
                msg.options.stream()
                        .map(option -> new RoadPlannerClientHooks.PreviewOption(option.optionId(), option.label(), option.pathNodeCount(), option.bridgeBacked()))
                        .toList(),
                msg.selectedOptionId,
                msg.bridgeRanges.stream()
                        .map(br -> new RoadPlannerClientHooks.BridgeRange(br.startIndex(), br.endIndex()))
                        .toList()
        ));
        if (msg.options.size() >= 2) {
            RoadPlannerClientHooks.openPreviewOptionSelection(
                    msg.sourceTownName,
                    msg.targetTownName,
                    msg.options.stream()
                            .map(option -> new RoadPlannerClientHooks.PreviewOption(option.optionId(), option.label(), option.pathNodeCount(), option.bridgeBacked()))
                            .toList(),
                    msg.selectedOptionId
            );
        }
    }

    private static void writeGhostBlocks(FriendlyByteBuf buf, List<GhostBlock> blocks) {
        List<GhostBlock> safeBlocks = blocks == null ? List.of() : blocks;
        buf.writeVarInt(safeBlocks.size());
        for (GhostBlock block : safeBlocks) {
            buf.writeBlockPos(block.pos());
            buf.writeVarInt(Block.getId(block.state()));
        }
    }

    private static void writePathNodes(FriendlyByteBuf buf, List<BlockPos> pathNodes) {
        List<BlockPos> safeNodes = pathNodes == null ? List.of() : pathNodes;
        buf.writeVarInt(safeNodes.size());
        for (BlockPos pos : safeNodes) {
            buf.writeBlockPos(pos);
        }
    }

    private static List<BlockPos> readPathNodes(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<BlockPos> pathNodes = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            pathNodes.add(buf.readBlockPos());
        }
        return List.copyOf(pathNodes);
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

    private static List<BlockPos> immutablePositions(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        List<BlockPos> copied = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (pos != null) {
                copied.add(pos.immutable());
            }
        }
        return copied.isEmpty() ? List.of() : List.copyOf(copied);
    }
}
