package com.monpai.sailboatmod.network.packet.roadplanner;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.nation.service.RoadPlannerRoadMergeService;
import com.monpai.sailboatmod.network.ModNetwork;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import com.monpai.sailboatmod.roadplanner.service.RoadChunkWarmup;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerChunkTickets;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerPreviewJobService;
import com.monpai.sailboatmod.roadplanner.structure.RoadCenterlinePoint;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeExpansionResult;
import com.monpai.sailboatmod.roadplanner.structure.RoadNodeStructureExpander;
import com.monpai.sailboatmod.roadplanner.structure.RoadPreviewBlock;
import com.monpai.sailboatmod.roadplanner.structure.RoadSpan;
import com.monpai.sailboatmod.roadplanner.structure.RoadSpanType;
import com.monpai.sailboatmod.roadplanner.structure.RoadStructureMode;
import com.monpai.sailboatmod.roadplanner.structure.RoadTerrainSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.monpai.sailboatmod.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public record RoadPlannerPreviewRequestPacket(String startTownName,
                                              String destinationTownName,
                                              List<BlockPos> nodes,
                                              List<RoadPlannerSegmentType> segmentTypes,
                                              RoadPlannerBuildSettings settings,
                                              RoadPlannerMergeSelection mergeSelection,
                                              List<BlockPos> logicalNodes,
                                              List<RoadPlannerSharedRoadSpan> sharedSpans) {
    /** About 30,000 blocks of straight road; the chunks are all held loaded while the preview is computed. */
    public static final int MAX_PREVIEW_CHUNKS = 6000;
    /** Bounds interpolation work as well as distinct chunks: travelling over the same road again still costs work. */
    private static final int MAX_PREVIEW_PATH_SAMPLES = 32_000;

    public RoadPlannerPreviewRequestPacket {
        startTownName = startTownName == null ? "" : startTownName;
        destinationTownName = destinationTownName == null ? "" : destinationTownName;
        nodes = nodes == null ? List.of() : nodes.stream().map(BlockPos::immutable).toList();
        segmentTypes = normalizeSegments(segmentTypes, nodes.size());
        settings = settings == null ? RoadPlannerBuildSettings.DEFAULTS : settings;
        mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
        logicalNodes = logicalNodes == null || logicalNodes.isEmpty()
                ? nodes
                : logicalNodes.stream().map(BlockPos::immutable).toList();
        sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                .filter(java.util.Objects::nonNull)
                .filter(RoadPlannerSharedRoadSpan::present)
                .toList();
    }

    public RoadPlannerPreviewRequestPacket(String startTownName,
                                           String destinationTownName,
                                           List<BlockPos> nodes,
                                           List<RoadPlannerSegmentType> segmentTypes,
                                           RoadPlannerBuildSettings settings,
                                           RoadPlannerMergeSelection mergeSelection) {
        this(startTownName, destinationTownName, nodes, segmentTypes, settings, mergeSelection, nodes, List.of());
    }

    public RoadPlannerPreviewRequestPacket(String startTownName,
                                           String destinationTownName,
                                           List<BlockPos> nodes,
                                           List<RoadPlannerSegmentType> segmentTypes,
                                           RoadPlannerBuildSettings settings) {
        this(startTownName, destinationTownName, nodes, segmentTypes, settings, RoadPlannerMergeSelection.none());
    }

    public RoadPlannerPreviewRequestPacket(String startTownName,
                                           String destinationTownName,
                                           List<BlockPos> nodes,
                                           List<RoadPlannerSegmentType> segmentTypes) {
        this(startTownName, destinationTownName, nodes, segmentTypes, RoadPlannerBuildSettings.DEFAULTS);
    }

    public static void encode(RoadPlannerPreviewRequestPacket packet, FriendlyByteBuf buffer) {
        RoadPlannerPacketCodec.writeString(buffer, packet.startTownName(), 64);
        RoadPlannerPacketCodec.writeString(buffer, packet.destinationTownName(), 64);
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.nodes());
        buffer.writeVarInt(packet.segmentTypes().size());
        for (RoadPlannerSegmentType type : packet.segmentTypes()) {
            buffer.writeEnum(type == null ? RoadPlannerSegmentType.ROAD : type);
        }
        buffer.writeVarInt(packet.settings().width());
        RoadPlannerPacketCodec.writeString(buffer, packet.settings().materialPreset(), 32);
        buffer.writeBoolean(packet.settings().streetlightsEnabled());
        buffer.writeBoolean(packet.mergeSelection().present());
        if (packet.mergeSelection().present()) {
            RoadPlannerPacketCodec.writeString(buffer, packet.mergeSelection().roadId(), 128);
            buffer.writeVarInt(packet.mergeSelection().pathIndex());
            buffer.writeBlockPos(packet.mergeSelection().anchorPos());
            buffer.writeEnum(packet.mergeSelection().scope());
        }
        RoadPlannerPacketCodec.writeBlockPosList(buffer, packet.logicalNodes());
        buffer.writeVarInt(packet.sharedSpans().size());
        for (RoadPlannerSharedRoadSpan span : packet.sharedSpans()) {
            RoadPlannerPacketCodec.writeString(buffer, span.roadId(), 128);
            buffer.writeVarInt(span.fromPathIndex());
            buffer.writeVarInt(span.toPathIndex());
            buffer.writeBlockPos(span.fromPos());
            buffer.writeBlockPos(span.toPos());
            buffer.writeEnum(span.scope());
            buffer.writeEnum(span.role());
        }
    }

    public static RoadPlannerPreviewRequestPacket decode(FriendlyByteBuf buffer) {
        String startTownName = buffer.readUtf(64);
        String destinationTownName = buffer.readUtf(64);
        List<BlockPos> nodes = RoadPlannerPacketCodec.readBlockPosList(buffer);
        int segmentCount = buffer.readVarInt();
        List<RoadPlannerSegmentType> segmentTypes = new ArrayList<>(segmentCount);
        for (int index = 0; index < segmentCount; index++) {
            segmentTypes.add(buffer.readEnum(RoadPlannerSegmentType.class));
        }
        RoadPlannerBuildSettings settings = buffer.isReadable()
                ? new RoadPlannerBuildSettings(buffer.readVarInt(), buffer.readUtf(32), buffer.readBoolean())
                : RoadPlannerBuildSettings.DEFAULTS;
        RoadPlannerMergeSelection mergeSelection = RoadPlannerMergeSelection.none();
        if (buffer.isReadable() && buffer.readBoolean()) {
            mergeSelection = new RoadPlannerMergeSelection(
                    buffer.readUtf(128),
                    buffer.readVarInt(),
                    buffer.readBlockPos(),
                    buffer.readEnum(RoadPlannerMergeScope.class)
            );
        }
        List<BlockPos> logicalNodes = buffer.isReadable() ? RoadPlannerPacketCodec.readBlockPosList(buffer) : nodes;
        List<RoadPlannerSharedRoadSpan> sharedSpans = List.of();
        if (buffer.isReadable()) {
            int count = buffer.readVarInt();
            if (count < 0 || count > 32) {
                throw new IllegalArgumentException("Shared road span count out of bounds: " + count);
            }
            List<RoadPlannerSharedRoadSpan> spans = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                spans.add(new RoadPlannerSharedRoadSpan(
                        buffer.readUtf(128),
                        buffer.readVarInt(),
                        buffer.readVarInt(),
                        buffer.readBlockPos(),
                        buffer.readBlockPos(),
                        buffer.readEnum(RoadPlannerMergeScope.class),
                        buffer.readEnum(RoadPlannerSharedRoadSpan.Role.class)));
            }
            sharedSpans = List.copyOf(spans);
        }
        return new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, settings, mergeSelection, logicalNodes, sharedSpans);
    }

    public static void handle(RoadPlannerPreviewRequestPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            RoadPlannerPreviewRequestPacket safePacket = packet.withServerValidatedMerge(player);
            PreviewBounds bounds = safePacket.previewBounds();
            if (bounds.tooLong()) {
                String detail = bounds.overWorkBudget()
                        ? "道路过长或节点过多：累计路程上限约 " + MAX_PREVIEW_PATH_SAMPLES + " 格，重复路段也计入。"
                        : "道路过长：沿线需要加载超过 " + MAX_PREVIEW_CHUNKS + " 个区块（约 3 万格直线）。";
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        detail + "请分段规划。"));
                return;
            }
            ServerLevel level = player.serverLevel();
            // The old preview goes now, so it cannot be confirmed while the new one loads.
            RoadPlannerBuildControlService.global().cancelPreview(player.getUUID(), new UUID(0L, 0L));
            // The chunks load over several ticks; the preview is computed once they are all there.
            RoadPlannerPreviewJobService.global().submit(
                    player.getUUID(),
                    level.dimension(),
                    new RoadChunkWarmup(bounds.chunks(), new RoadPlannerChunkTickets(level),
                            RoadPlannerChunkTickets.REQUESTS_PER_TICK, RoadPlannerChunkTickets.MAX_IN_FLIGHT),
                    () -> safePacket.completePreview(player, level),
                    message -> player.displayClientMessage(net.minecraft.network.chat.Component.literal(message), true));
        });
        context.setPacketHandled(true);
    }

    private void completePreview(ServerPlayer player, ServerLevel level) {
        if (player.isRemoved() || player.serverLevel() != level) {
            return; // left, died or changed dimension while loading: the steps would be built in the wrong place
        }
        PreviewComputation computed = computePreview(RoadTerrainSampler.fromLevel(level));
        UUID previewId = RoadPlannerBuildControlService.global().startPreview(
                player.getUUID(),
                startTownName,
                destinationTownName,
                nodes,
                segmentTypes,
                settings,
                mergeSelection,
                logicalNodes,
                sharedSpans);
        RoadPlannerBuildControlService.global().attachCompiledSteps(previewId, computed.buildSteps());
        ModNetwork.CHANNEL.send(player, computed.packet());
    }

    RoadPlannerPreviewRequestPacket withServerValidatedMerge(ServerPlayer player) {
        if (!mergeSelection.present()) {
            return this;
        }
        if (player == null || nodes.isEmpty()) {
            return new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, settings, RoadPlannerMergeSelection.none(), logicalNodes, sharedSpans);
        }
        return withValidatedMerge((probe, radius, selection, finalSegmentType) -> RoadPlannerRoadMergeService.validateSelection(
                player,
                probe,
                radius,
                selection,
                finalSegmentType));
    }

    RoadPlannerPreviewRequestPacket withValidatedMerge(MergeSelectionValidator validator) {
        if (!mergeSelection.present()) {
            return this;
        }
        if (nodes.isEmpty() || validator == null) {
            return new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, settings, RoadPlannerMergeSelection.none(), logicalNodes, sharedSpans);
        }
        RoadPlannerSegmentType finalSegmentType = segmentTypes.isEmpty()
                ? RoadPlannerSegmentType.ROAD
                : segmentTypes.get(segmentTypes.size() - 1);
        BlockPos submittedEnd = nodes.get(nodes.size() - 1);
        return validator.validate(
                        submittedEnd,
                        RoadPlannerMergeCandidateRequestPacket.MAX_RADIUS,
                        mergeSelection,
                        finalSegmentType)
                .map(candidate -> {
                    List<BlockPos> snappedNodes = new ArrayList<>(nodes);
                    snappedNodes.set(snappedNodes.size() - 1, candidate.anchorPos());
                    RoadPlannerMergeSelection canonicalSelection = new RoadPlannerMergeSelection(
                            candidate.roadId(),
                            candidate.pathIndex(),
                            candidate.anchorPos(),
                            mergeSelection.scope()
                    );
                    List<BlockPos> snappedLogicalNodes = snapLogicalAnchor(logicalNodes, mergeSelection.anchorPos(), candidate.anchorPos());
                    List<RoadPlannerSharedRoadSpan> snappedSpans = snapSharedSpanAnchor(sharedSpans, mergeSelection.anchorPos(), candidate.anchorPos(), candidate.roadId(), candidate.pathIndex());
                    return new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, snappedNodes, segmentTypes, settings, canonicalSelection, snappedLogicalNodes, snappedSpans);
                })
                .orElseGet(() -> new RoadPlannerPreviewRequestPacket(startTownName, destinationTownName, nodes, segmentTypes, settings, RoadPlannerMergeSelection.none(), logicalNodes, sharedSpansWithoutEndMerge()));
    }

    private List<RoadPlannerSharedRoadSpan> sharedSpansWithoutEndMerge() {
        if (sharedSpans.isEmpty()) {
            return List.of();
        }
        return sharedSpans.stream()
                .filter(span -> span.role() != RoadPlannerSharedRoadSpan.Role.END_MERGE)
                .toList();
    }

    private static List<BlockPos> snapLogicalAnchor(List<BlockPos> logicalNodes, BlockPos submittedAnchor, BlockPos canonicalAnchor) {
        if (logicalNodes == null || logicalNodes.isEmpty() || submittedAnchor == null || canonicalAnchor == null || submittedAnchor.equals(canonicalAnchor)) {
            return logicalNodes == null ? List.of() : logicalNodes;
        }
        List<BlockPos> snapped = new ArrayList<>(logicalNodes);
        for (int index = 0; index < snapped.size(); index++) {
            if (submittedAnchor.equals(snapped.get(index))) {
                snapped.set(index, canonicalAnchor.immutable());
                break;
            }
        }
        return List.copyOf(snapped);
    }

    private static List<RoadPlannerSharedRoadSpan> snapSharedSpanAnchor(List<RoadPlannerSharedRoadSpan> spans,
                                                                        BlockPos submittedAnchor,
                                                                        BlockPos canonicalAnchor,
                                                                        String canonicalRoadId,
                                                                        int canonicalPathIndex) {
        if (spans == null || spans.isEmpty() || submittedAnchor == null || canonicalAnchor == null) {
            return spans == null ? List.of() : spans;
        }
        List<RoadPlannerSharedRoadSpan> snapped = new ArrayList<>(spans.size());
        for (RoadPlannerSharedRoadSpan span : spans) {
            if (span.role() == RoadPlannerSharedRoadSpan.Role.END_MERGE && submittedAnchor.equals(span.fromPos())) {
                snapped.add(new RoadPlannerSharedRoadSpan(
                        canonicalRoadId,
                        canonicalPathIndex,
                        span.toPathIndex(),
                        canonicalAnchor,
                        span.toPos(),
                        span.scope(),
                        span.role()));
            } else {
                snapped.add(span);
            }
        }
        return List.copyOf(snapped);
    }

    @FunctionalInterface
    interface MergeSelectionValidator {
        Optional<RoadPlannerRoadMergeService.Candidate> validate(BlockPos probe,
                                                                 int radius,
                                                                 RoadPlannerMergeSelection selection,
                                                                 RoadPlannerSegmentType finalSegmentType);
    }

    public SyncRoadPlannerPreviewPacket toPreviewPacketForTest() {
        return toSafePreview((RoadTerrainSampler) null);
    }

    public SyncRoadPlannerPreviewPacket toPreviewPacketForTest(RoadTerrainSampler terrainSampler) {
        return toSafePreview(terrainSampler);
    }

    /**
     * Chunks the preview samples: every chunk along the node lines plus its neighbours.
     * Unloaded chunks report the world bottom as their height, which would bury the road, so all of them
     * must be loaded first (see {@link RoadChunkWarmup}). Rejected requests return an empty corridor or at most
     * one chunk beyond the cap, rather than allocating the complete corridor just to refuse it.
     */
    Set<Long> pathChunks() {
        return previewBounds().chunks();
    }

    private PreviewBounds previewBounds() {
        if (exceedsWorkBudget()) {
            return new PreviewBounds(Set.of(), true);
        }
        Set<Long> chunks = new LinkedHashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            BlockPos a = nodes.get(i);
            if (!collectChunkAround(chunks, a.getX() >> 4, a.getZ() >> 4)
                    || (i + 1 < nodes.size() && !collectLineChunks(chunks, a, nodes.get(i + 1)))) {
                break;
            }
        }
        return new PreviewBounds(chunks, false);
    }

    /** Roads exceeding either the loaded-chunk budget or the cumulative interpolation budget must be split. */
    boolean tooLongForPreview() {
        return previewBounds().tooLong();
    }

    private boolean exceedsWorkBudget() {
        if (nodes.size() > MAX_PREVIEW_PATH_SAMPLES) {
            return true;
        }
        long remaining = MAX_PREVIEW_PATH_SAMPLES - 1L;
        for (int i = 1; i < nodes.size(); i++) {
            BlockPos a = nodes.get(i - 1);
            BlockPos b = nodes.get(i);
            // Match the centerline's Euclidean interpolation count without overflowing integer coordinate math.
            double dx = (double) b.getX() - a.getX();
            double dz = (double) b.getZ() - a.getZ();
            long samples = Math.max(1L, (long) Math.ceil(Math.hypot(dx, dz)));
            if (samples > remaining) {
                return true;
            }
            remaining -= samples;
        }
        return false;
    }

    private record PreviewBounds(Set<Long> chunks, boolean overWorkBudget) {
        boolean tooLong() {
            return overWorkBudget || chunks.size() > MAX_PREVIEW_CHUNKS;
        }
    }

    private static boolean collectChunkAround(Set<Long> out, int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                out.add(net.minecraft.world.level.ChunkPos.asLong(chunkX + dx, chunkZ + dz));
                if (out.size() > MAX_PREVIEW_CHUNKS) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean collectLineChunks(Set<Long> out, BlockPos a, BlockPos b) {
        int x0 = a.getX();
        int z0 = a.getZ();
        int x1 = b.getX();
        int z1 = b.getZ();
        long dx = (long) x1 - x0;
        long dz = (long) z1 - z0;
        long steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps <= 0) {
            return collectChunkAround(out, x0 >> 4, z0 >> 4);
        }
        // 每 8 格采一点（< 16 即可覆盖每个区块），按区块去重。
        for (long s = 0; s <= steps; s += 8) {
            double t = (double) s / steps;
            int x = (int) Math.round(x0 + dx * t);
            int z = (int) Math.round(z0 + dz * t);
            if (!collectChunkAround(out, x >> 4, z >> 4)) {
                return false;
            }
        }
        return collectChunkAround(out, x1 >> 4, z1 >> 4);
    }

    private SyncRoadPlannerPreviewPacket toSafePreview(RoadTerrainSampler terrainSampler) {
        return computePreview(terrainSampler).packet();
    }

    private record PreviewComputation(SyncRoadPlannerPreviewPacket packet, List<com.monpai.sailboatmod.road.model.BuildStep> buildSteps) {
    }

    private PreviewComputation computePreview(RoadTerrainSampler terrainSampler) {
        if (nodes.size() < 2) {
            return new PreviewComputation(new SyncRoadPlannerPreviewPacket(startTownName, destinationTownName, List.of(), List.of(), 0, null, null, null, false, List.of(), "", List.of()), List.of());
        }
        RoadNodeExpansionResult expansion = RoadNodeStructureExpander.expand(
                nodes,
                segmentTypes,
                settings,
                terrainSampler,
                RoadStructureMode.PREVIEW
        );
        List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks = ghostBlocksFromBuildSteps(expansion);
        SampledPreviewPath previewPath = previewPathNodes(expansion);
        List<BlockPos> previewPathNodes = previewPath.nodes();
        BlockPos startHighlight = previewPathNodes.isEmpty() ? nodes.get(0) : previewPathNodes.get(0);
        BlockPos endHighlight = previewPathNodes.isEmpty() ? nodes.get(nodes.size() - 1) : previewPathNodes.get(previewPathNodes.size() - 1);
        return new PreviewComputation(new SyncRoadPlannerPreviewPacket(
                startTownName,
                destinationTownName,
                ghostBlocks,
                previewPathNodes,
                expansion.centerline().isEmpty() ? nodes.size() : expansion.centerline().size(),
                startHighlight,
                endHighlight,
                endHighlight,
                true,
                List.of(),
                "",
                bridgeRangesFromSpans(expansion, previewPath)
        ), expansion.buildSteps());
    }

    private List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocksFromBuildSteps(RoadNodeExpansionResult expansion) {
        List<SyncRoadPlannerPreviewPacket.GhostBlock> ghostBlocks = new ArrayList<>();
        for (RoadPreviewBlock block : expansion.previewBlocks()) {
            ghostBlocks.add(new SyncRoadPlannerPreviewPacket.GhostBlock(block.pos(), block.state()));
        }
        return List.copyOf(ghostBlocks);
    }

    private static SampledPreviewPath previewPathNodes(RoadNodeExpansionResult expansion) {
        if (expansion == null || expansion.centerline().isEmpty()) {
            return new SampledPreviewPath(List.of(), List.of());
        }
        List<RoadCenterlinePoint> centerline = expansion.centerline();
        if (centerline.size() <= 64) {
            List<BlockPos> nodes = new ArrayList<>(centerline.size());
            List<Integer> indexes = new ArrayList<>(centerline.size());
            for (int index = 0; index < centerline.size(); index++) {
                nodes.add(targetPos(centerline.get(index)));
                indexes.add(index);
            }
            return new SampledPreviewPath(List.copyOf(nodes), List.copyOf(indexes));
        }
        List<BlockPos> sampled = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        sampled.add(targetPos(centerline.get(0)));
        indexes.add(0);
        int step = Math.max(1, centerline.size() / 62);
        for (int index = step; index < centerline.size() - 1; index += step) {
            sampled.add(targetPos(centerline.get(index)));
            indexes.add(index);
        }
        sampled.add(targetPos(centerline.get(centerline.size() - 1)));
        indexes.add(centerline.size() - 1);
        return new SampledPreviewPath(List.copyOf(sampled), List.copyOf(indexes));
    }

    private static BlockPos targetPos(RoadCenterlinePoint point) {
        return new BlockPos(point.pos().getX(), point.targetY(), point.pos().getZ());
    }

    private static List<SyncRoadPlannerPreviewPacket.BridgeRange> bridgeRangesFromSpans(RoadNodeExpansionResult expansion,
                                                                                        SampledPreviewPath previewPath) {
        if (expansion == null || expansion.spans().isEmpty() || previewPath == null || previewPath.nodes().size() < 2) {
            return List.of();
        }
        List<SyncRoadPlannerPreviewPacket.BridgeRange> ranges = new ArrayList<>();
        int rangeStart = -1;
        for (int segmentIndex = 0; segmentIndex < previewPath.nodes().size() - 1; segmentIndex++) {
            int centerStart = previewPath.centerlineIndexes().get(segmentIndex);
            int centerEnd = previewPath.centerlineIndexes().get(segmentIndex + 1);
            boolean bridge = expansion.spans().stream()
                    .filter(span -> span.type() == RoadSpanType.BRIDGE)
                    .anyMatch(span -> overlaps(centerStart, centerEnd, span));
            if (bridge && rangeStart < 0) {
                rangeStart = segmentIndex;
            } else if (!bridge && rangeStart >= 0) {
                ranges.add(new SyncRoadPlannerPreviewPacket.BridgeRange(rangeStart, segmentIndex - 1));
                rangeStart = -1;
            }
        }
        if (rangeStart >= 0) {
            ranges.add(new SyncRoadPlannerPreviewPacket.BridgeRange(rangeStart, previewPath.nodes().size() - 2));
        }
        return List.copyOf(ranges);
    }

    private static boolean overlaps(int centerStart, int centerEnd, RoadSpan span) {
        int min = Math.min(centerStart, centerEnd);
        int max = Math.max(centerStart, centerEnd);
        return min <= span.endIndex() && max >= span.startIndex();
    }

    private record SampledPreviewPath(List<BlockPos> nodes, List<Integer> centerlineIndexes) {
    }

    private static List<RoadPlannerSegmentType> normalizeSegments(List<RoadPlannerSegmentType> segmentTypes, int nodeCount) {
        int expected = Math.max(0, nodeCount - 1);
        List<RoadPlannerSegmentType> normalized = new ArrayList<>(expected);
        if (segmentTypes != null) {
            for (int index = 0; index < Math.min(expected, segmentTypes.size()); index++) {
                RoadPlannerSegmentType type = segmentTypes.get(index);
                normalized.add(type == null ? RoadPlannerSegmentType.ROAD : type);
            }
        }
        while (normalized.size() < expected) {
            normalized.add(RoadPlannerSegmentType.ROAD);
        }
        return List.copyOf(normalized);
    }
}
