package com.monpai.sailboatmod.roadplanner.edit;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RoadEditableMigrationService {
    private static final int DEFAULT_WIDTH = 3;
    private static final String DEFAULT_MATERIAL = "minecraft:smooth_stone";

    private RoadEditableMigrationService() {
    }

    public static Optional<RoadEditableRecord> ensureLegacyLedger(ServerLevel level, RoadNetworkRecord road) {
        if (level == null) {
            return Optional.empty();
        }
        return ensureLegacyLedger(RoadEditableNetworkSavedData.get(level), road, level::getBlockState, System.currentTimeMillis());
    }

    public static Optional<RoadEditableRecord> ensureLegacyLedger(RoadEditableNetworkSavedData data,
                                                                  RoadNetworkRecord road,
                                                                  BlockStateLookup blockStateLookup,
                                                                  long timestamp) {
        if (data == null || road == null || road.roadId().isBlank() || road.path().size() < 2) {
            return Optional.empty();
        }
        Optional<RoadEditableRecord> existing = data.getRoad(road.roadId());
        if (existing.isPresent()) {
            return existing;
        }
        String segmentId = segmentId(road.roadId(), 0);
        List<Long> blockPositions = road.path().stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::asLong)
                .distinct()
                .toList();
        RoadEditableRecord record = recordFromRoad(road, DEFAULT_WIDTH, DEFAULT_MATERIAL, true, blockPositions, timestamp);
        data.putRoad(record);
        BlockStateLookup lookup = blockStateLookup == null ? pos -> Blocks.AIR.defaultBlockState() : blockStateLookup;
        for (Long posLong : blockPositions) {
            BlockPos pos = BlockPos.of(posLong);
            BlockState current = lookup.getBlockState(pos);
            data.putLedgerEntry(new RoadBlockLedgerEntry(
                    pos,
                    current,
                    current,
                    java.util.Set.of(segmentId),
                    road.roadId(),
                    timestamp));
        }
        return Optional.of(record);
    }

    public static Optional<RoadEditableRecord> registerCompletedRoad(RoadEditableNetworkSavedData data,
                                                                     RoadNetworkRecord road,
                                                                     List<BuildStep> buildSteps,
                                                                     List<ConstructionQueue.RollbackEntry> rollbackEntries,
                                                                     int width,
                                                                     String materialId,
                                                                     long timestamp) {
        if (data == null || road == null || road.roadId().isBlank() || buildSteps == null || buildSteps.isEmpty()) {
            return Optional.empty();
        }
        String segmentId = segmentId(road.roadId(), 0);
        List<BuildStep> safeSteps = buildSteps.stream()
                .filter(step -> step != null && step.pos() != null)
                .toList();
        if (safeSteps.isEmpty()) {
            return Optional.empty();
        }
        List<Long> blockPositions = safeSteps.stream()
                .map(step -> step.pos().asLong())
                .distinct()
                .toList();
        RoadEditableRecord record = recordFromRoad(road, width, materialId, false, blockPositions, timestamp);
        data.putRoad(record);

        Map<Long, BlockState> rollbackByPos = rollbackByPos(rollbackEntries);
        for (BuildStep step : safeSteps) {
            BlockPos pos = step.pos();
            BlockState previous = rollbackByPos.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
            RoadBlockLedgerEntry existing = data.ledgerAt(pos).orElse(null);
            RoadBlockLedgerEntry entry = existing == null
                    ? new RoadBlockLedgerEntry(pos, previous, step.state(), java.util.Set.of(segmentId), road.roadId(), timestamp)
                    : existing.withOwner(segmentId, timestamp);
            data.putLedgerEntry(entry);
        }
        return Optional.of(record);
    }

    private static RoadEditableRecord recordFromRoad(RoadNetworkRecord road,
                                                     int width,
                                                     String materialId,
                                                     boolean legacyMigrated,
                                                     List<Long> blockPositions,
                                                     long timestamp) {
        BlockPos source = road.displayPath().isEmpty() ? road.path().get(0) : road.displayPath().get(0);
        BlockPos target = road.displayPath().isEmpty()
                ? road.path().get(road.path().size() - 1)
                : road.displayPath().get(road.displayPath().size() - 1);
        RoadEditableNode sourceNode = new RoadEditableNode(nodeId(road.roadId(), "source"), source,
                RoadEditableNode.Kind.SOURCE_TOWN, road.routeSourceName());
        RoadEditableNode targetNode = new RoadEditableNode(nodeId(road.roadId(), "target"), target,
                RoadEditableNode.Kind.TARGET_TOWN, road.routeTargetName());
        RoadEditableSegment segment = new RoadEditableSegment(
                segmentId(road.roadId(), 0),
                sourceNode.nodeId(),
                targetNode.nodeId(),
                road.path(),
                road.displayPath(),
                width,
                "ROAD",
                materialId,
                blockPositions);
        return new RoadEditableRecord(
                road.roadId(),
                "",
                road.dimensionId(),
                road.nationId(),
                road.townId(),
                road.creatorUuid(),
                road.creatorName(),
                road.routeSourceTownId(),
                road.routeTargetTownId(),
                road.routeSourceName(),
                road.routeTargetName(),
                width,
                materialId,
                RoadEditableRecord.Status.BUILT,
                legacyMigrated,
                List.of(sourceNode, targetNode),
                List.of(segment),
                road.createdAt(),
                Math.max(timestamp, road.updatedAt()));
    }

    private static Map<Long, BlockState> rollbackByPos(List<ConstructionQueue.RollbackEntry> rollbackEntries) {
        LinkedHashMap<Long, BlockState> states = new LinkedHashMap<>();
        if (rollbackEntries == null) {
            return states;
        }
        for (ConstructionQueue.RollbackEntry entry : rollbackEntries) {
            if (entry != null && entry.pos() != null && entry.previousState() != null) {
                states.putIfAbsent(entry.pos().asLong(), entry.previousState());
            }
        }
        return states;
    }

    private static String nodeId(String roadId, String suffix) {
        return roadId + ":" + suffix;
    }

    private static String segmentId(String roadId, int index) {
        return roadId + ":segment:" + index;
    }

    @FunctionalInterface
    public interface BlockStateLookup {
        BlockState getBlockState(BlockPos pos);
    }
}
