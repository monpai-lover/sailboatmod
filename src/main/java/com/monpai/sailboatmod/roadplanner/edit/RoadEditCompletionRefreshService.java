package com.monpai.sailboatmod.roadplanner.edit;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSegmentPlacement;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraphSavedData;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuiltRoadMapRefresh;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class RoadEditCompletionRefreshService {
    private RoadEditCompletionRefreshService() {
    }

    public static RefreshResult refresh(ServerLevel level,
                                        RoadEditableRecord targetRecord,
                                        RoadEditDiff diff,
                                        long timestamp) {
        if (level == null) {
            return RefreshResult.empty();
        }
        RefreshResult result = refreshForTest(
                NationSavedData.get(level),
                RoadNetworkGraphSavedData.get(level),
                targetRecord,
                diff,
                timestamp);
        RoadPlannerBuiltRoadMapRefresh.enqueueChunks(level, result.affectedChunks());
        return result;
    }

    static RefreshResult refreshForTest(NationSavedData nationData,
                                        RoadNetworkGraphSavedData graphData,
                                        RoadEditableRecord targetRecord,
                                        RoadEditDiff diff,
                                        long timestamp) {
        if (targetRecord == null || targetRecord.roadId().isBlank()) {
            return RefreshResult.empty();
        }
        LinkedHashSet<ChunkPos> affectedChunks = new LinkedHashSet<>();
        RoadNetworkRecord previousRoad = nationData == null ? null : nationData.getRoadNetwork(targetRecord.roadId());
        addRoadChunks(affectedChunks, previousRoad);

        RoadGraphEdgeRecord previousEdge = graphData == null
                ? null
                : resolveExistingEdge(graphData, targetRecord).orElse(null);
        addGraphChunks(affectedChunks, previousEdge);
        addDiffChunks(affectedChunks, diff);

        List<BlockPos> centerline = centerline(targetRecord);
        List<BlockPos> displayPath = displayPath(targetRecord, centerline);
        List<BlockPos> ownedBlocks = ownedBlockPositions(targetRecord);
        addPositions(affectedChunks, centerline);
        addPositions(affectedChunks, displayPath);
        addPositions(affectedChunks, ownedBlocks);

        if (nationData != null) {
            nationData.putRoadNetwork(toPublicRoad(previousRoad, targetRecord, centerline, displayPath, timestamp));
        }
        if (graphData != null && centerline.size() >= 2) {
            refreshGraphEdge(graphData, previousEdge, targetRecord, centerline, displayPath, ownedBlocks, timestamp);
        }
        return new RefreshResult(true, Set.copyOf(affectedChunks));
    }

    static UUID edgeIdForRoadIdForTest(String roadId) {
        return edgeIdForRoadId(roadId);
    }

    private static RoadNetworkRecord toPublicRoad(RoadNetworkRecord previous,
                                                  RoadEditableRecord target,
                                                  List<BlockPos> centerline,
                                                  List<BlockPos> displayPath,
                                                  long timestamp) {
        String structureA = previous == null ? plannerAnchorId("start", first(centerline)) : previous.structureAId();
        String structureB = previous == null ? plannerAnchorId("end", last(centerline)) : previous.structureBId();
        long createdAt = previous == null ? target.createdAt() : previous.createdAt();
        String creatorUuid = previous == null ? target.creatorUuid() : previous.creatorUuid();
        String creatorName = previous == null ? target.creatorName() : previous.creatorName();
        String sourceType = previous == null ? RoadNetworkRecord.SOURCE_TYPE_MANUAL : previous.sourceType();
        String sourceName = previous == null || previous.routeSourceName().isBlank()
                ? target.sourceTownName()
                : previous.routeSourceName();
        String targetName = previous == null || previous.routeTargetName().isBlank()
                ? target.targetTownName()
                : previous.routeTargetName();
        String sourceTownId = previous == null || previous.routeSourceTownId().isBlank()
                ? target.sourceTownId()
                : previous.routeSourceTownId();
        String targetTownId = previous == null || previous.routeTargetTownId().isBlank()
                ? target.targetTownId()
                : previous.routeTargetTownId();
        return new RoadNetworkRecord(
                target.roadId(),
                previous == null ? target.ownerNationId() : previous.nationId(),
                previous == null ? target.ownerTownId() : previous.townId(),
                previous == null ? target.dimensionId() : previous.dimensionId(),
                structureA,
                structureB,
                centerline,
                displayPath,
                List.of(),
                Math.max(timestamp, target.updatedAt()),
                createdAt,
                creatorUuid,
                creatorName,
                sourceType,
                sourceName,
                targetName,
                sourceTownId,
                targetTownId);
    }

    private static void refreshGraphEdge(RoadNetworkGraphSavedData graphData,
                                         RoadGraphEdgeRecord previous,
                                         RoadEditableRecord target,
                                         List<BlockPos> centerline,
                                         List<BlockPos> displayPath,
                                         List<BlockPos> ownedBlocks,
                                         long timestamp) {
        UUID edgeId = resolveEdgeId(target);
        RoadGraphNodeRecord fromNode = previous == null
                ? newGraphNode(nodeIdForRoad(target.roadId(), "from"), target, centerline.get(0), timestamp)
                : updatedNode(graphData.getNode(previous.fromNodeId()).orElse(null), target, centerline.get(0), timestamp);
        RoadGraphNodeRecord toNode = previous == null
                ? newGraphNode(nodeIdForRoad(target.roadId(), "to"), target, centerline.get(centerline.size() - 1), timestamp)
                : updatedNode(graphData.getNode(previous.toNodeId()).orElse(null), target, centerline.get(centerline.size() - 1), timestamp);
        graphData.putNode(fromNode);
        graphData.putNode(toNode);
        graphData.putEdge(new RoadGraphEdgeRecord(
                previous == null ? edgeId : previous.edgeId(),
                fromNode.nodeId(),
                toNode.nodeId(),
                target.dimensionId(),
                previous == null ? target.ownerNationId() : previous.ownerNationId(),
                previous == null ? target.ownerTownId() : previous.ownerTownId(),
                previous == null ? target.creatorUuid() : previous.creatorUuid(),
                previous == null ? target.creatorName() : previous.creatorName(),
                previous == null ? target.sourceTownName() : previous.sourceTownName(),
                previous == null ? target.targetTownName() : previous.targetTownName(),
                previous == null || previous.roadName().isBlank() ? target.roadId() : previous.roadName(),
                graphWidth(target),
                graphSectionType(target),
                RoadGraphEdgeRecord.Status.BUILT,
                centerline,
                displayPath,
                graphPlacements(target),
                ownedBlocks,
                List.of(),
                previous == null ? target.createdAt() : previous.createdAt(),
                Math.max(timestamp, target.updatedAt())));
    }

    private static Optional<RoadGraphEdgeRecord> resolveExistingEdge(RoadNetworkGraphSavedData graphData,
                                                                     RoadEditableRecord target) {
        if (graphData == null || target == null) {
            return Optional.empty();
        }
        UUID explicit = parseUuid(target.edgeId());
        if (explicit != null) {
            Optional<RoadGraphEdgeRecord> edge = graphData.getEdge(explicit);
            if (edge.isPresent()) {
                return edge;
            }
        }
        UUID deterministic = edgeIdForRoadId(target.roadId());
        Optional<RoadGraphEdgeRecord> edge = graphData.getEdge(deterministic);
        if (edge.isPresent()) {
            return edge;
        }
        return graphData.edges().stream()
                .filter(candidate -> candidate != null && target.roadId().equalsIgnoreCase(candidate.roadName()))
                .findFirst();
    }

    private static RoadGraphNodeRecord updatedNode(RoadGraphNodeRecord previous,
                                                   RoadEditableRecord target,
                                                   BlockPos pos,
                                                   long timestamp) {
        if (previous == null) {
            return newGraphNode(UUID.randomUUID(), target, pos, timestamp);
        }
        return new RoadGraphNodeRecord(
                previous.nodeId(),
                target.dimensionId(),
                pos,
                previous.kind(),
                previous.ownerNationId(),
                previous.ownerTownId(),
                previous.structureId(),
                previous.createdAt(),
                Math.max(timestamp, previous.updatedAt()));
    }

    private static RoadGraphNodeRecord newGraphNode(UUID nodeId,
                                                    RoadEditableRecord target,
                                                    BlockPos pos,
                                                    long timestamp) {
        return new RoadGraphNodeRecord(
                nodeId,
                target.dimensionId(),
                pos,
                RoadGraphNodeRecord.Kind.NORMAL,
                target.ownerNationId(),
                target.ownerTownId(),
                "",
                target.createdAt(),
                Math.max(timestamp, target.updatedAt()));
    }

    private static List<RoadGraphSegmentPlacement> graphPlacements(RoadEditableRecord target) {
        if (target == null || target.segments().isEmpty()) {
            return List.of();
        }
        ArrayList<RoadGraphSegmentPlacement> placements = new ArrayList<>();
        for (RoadEditableSegment segment : target.segments()) {
            List<BlockPos> positions = positionsFromLongs(segment.blockPositions());
            if (positions.isEmpty()) {
                continue;
            }
            placements.add(new RoadGraphSegmentPlacement(middle(segment.centerline(), positions), positions));
        }
        return placements.isEmpty() ? List.of() : List.copyOf(placements);
    }

    private static List<BlockPos> centerline(RoadEditableRecord target) {
        if (target == null || target.segments().isEmpty()) {
            return nodePositions(target);
        }
        ArrayList<BlockPos> positions = new ArrayList<>();
        for (RoadEditableSegment segment : target.segments()) {
            appendPath(positions, segment.centerline());
        }
        return positions.isEmpty() ? nodePositions(target) : List.copyOf(positions);
    }

    private static List<BlockPos> displayPath(RoadEditableRecord target, List<BlockPos> centerline) {
        if (target != null && !target.segments().isEmpty()) {
            ArrayList<BlockPos> positions = new ArrayList<>();
            for (RoadEditableSegment segment : target.segments()) {
                appendPath(positions, segment.displayPath());
            }
            if (!positions.isEmpty()) {
                return List.copyOf(positions);
            }
        }
        List<BlockPos> nodes = nodePositions(target);
        return nodes.isEmpty() ? centerline : nodes;
    }

    private static List<BlockPos> nodePositions(RoadEditableRecord target) {
        if (target == null || target.nodes().isEmpty()) {
            return List.of();
        }
        return target.nodes().stream()
                .filter(java.util.Objects::nonNull)
                .map(RoadEditableNode::pos)
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    private static List<BlockPos> ownedBlockPositions(RoadEditableRecord target) {
        if (target == null || target.segments().isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> positions = new LinkedHashSet<>();
        for (RoadEditableSegment segment : target.segments()) {
            if (segment != null) {
                positions.addAll(segment.blockPositions());
            }
        }
        return positions.stream().map(BlockPos::of).toList();
    }

    private static void appendPath(List<BlockPos> output, List<BlockPos> path) {
        if (output == null || path == null || path.isEmpty()) {
            return;
        }
        for (BlockPos pos : path) {
            if (pos == null) {
                continue;
            }
            BlockPos immutable = pos.immutable();
            if (!output.isEmpty() && output.get(output.size() - 1).equals(immutable)) {
                continue;
            }
            output.add(immutable);
        }
    }

    private static int graphWidth(RoadEditableRecord target) {
        if (target == null || target.segments().isEmpty()) {
            return target == null ? 1 : target.width();
        }
        return target.segments().get(0).width();
    }

    private static CompiledRoadSectionType graphSectionType(RoadEditableRecord target) {
        if (target == null || target.segments().isEmpty()) {
            return CompiledRoadSectionType.ROAD;
        }
        String value = target.segments().get(0).sectionType();
        if (value == null || value.isBlank()) {
            return CompiledRoadSectionType.ROAD;
        }
        try {
            return CompiledRoadSectionType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return CompiledRoadSectionType.ROAD;
        }
    }

    private static UUID resolveEdgeId(RoadEditableRecord target) {
        UUID explicit = target == null ? null : parseUuid(target.edgeId());
        return explicit == null ? edgeIdForRoadId(target == null ? "" : target.roadId()) : explicit;
    }

    private static UUID edgeIdForRoadId(String roadId) {
        String normalized = roadId == null ? "" : roadId.trim().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(("road-graph-edge:" + normalized).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID nodeIdForRoad(String roadId, String suffix) {
        String normalized = roadId == null ? "" : roadId.trim().toLowerCase(Locale.ROOT);
        String safeSuffix = suffix == null ? "" : suffix.trim().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes(("road-graph-node:" + normalized + ":" + safeSuffix).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static BlockPos middle(List<BlockPos> centerline, List<BlockPos> fallback) {
        List<BlockPos> source = centerline == null || centerline.isEmpty() ? fallback : centerline;
        if (source == null || source.isEmpty()) {
            return BlockPos.ZERO;
        }
        return source.get(source.size() / 2);
    }

    private static List<BlockPos> positionsFromLongs(List<Long> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        return positions.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::of)
                .map(BlockPos::immutable)
                .toList();
    }

    private static void addRoadChunks(Set<ChunkPos> chunks, RoadNetworkRecord road) {
        if (road == null) {
            return;
        }
        addPositions(chunks, road.path());
        addPositions(chunks, road.displayPath());
    }

    private static void addGraphChunks(Set<ChunkPos> chunks, RoadGraphEdgeRecord edge) {
        if (edge == null) {
            return;
        }
        addPositions(chunks, edge.centerline());
        addPositions(chunks, edge.displayPath());
        addPositions(chunks, edge.ownedBlockPositions());
        for (RoadGraphSegmentPlacement placement : edge.placements()) {
            if (placement == null) {
                continue;
            }
            addChunk(chunks, placement.middlePos());
            addPositions(chunks, placement.positions());
        }
    }

    private static void addDiffChunks(Set<ChunkPos> chunks, RoadEditDiff diff) {
        if (diff == null) {
            return;
        }
        for (RoadEditDiff.RemovedBlock removed : diff.removedBlocks()) {
            if (removed != null) {
                addChunk(chunks, removed.pos());
            }
        }
        for (RoadEditDiff.KeptBlock kept : diff.keptBlocks()) {
            if (kept != null) {
                addChunk(chunks, kept.pos());
            }
        }
        for (RoadEditBlockPlacement added : diff.addedBlocks()) {
            if (added != null) {
                addChunk(chunks, added.pos());
            }
        }
    }

    private static void addPositions(Set<ChunkPos> chunks, List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        for (BlockPos pos : positions) {
            addChunk(chunks, pos);
        }
    }

    private static void addChunk(Set<ChunkPos> chunks, BlockPos pos) {
        if (chunks != null && pos != null) {
            chunks.add(new ChunkPos(pos));
        }
    }

    private static String plannerAnchorId(String kind, BlockPos pos) {
        String safeKind = kind == null || kind.isBlank() ? "node" : kind.trim();
        if (pos == null) {
            return "planner:" + safeKind;
        }
        return "planner:" + safeKind + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static BlockPos first(List<BlockPos> positions) {
        return positions == null || positions.isEmpty() ? BlockPos.ZERO : positions.get(0);
    }

    private static BlockPos last(List<BlockPos> positions) {
        return positions == null || positions.isEmpty() ? BlockPos.ZERO : positions.get(positions.size() - 1);
    }

    public record RefreshResult(boolean refreshed, Set<ChunkPos> affectedChunks) {
        public RefreshResult {
            affectedChunks = affectedChunks == null ? Set.of() : Set.copyOf(affectedChunks);
        }

        public static RefreshResult empty() {
            return new RefreshResult(false, Set.of());
        }
    }
}
