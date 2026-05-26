package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.nation.data.ConstructionRuntimeSavedData;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationMemberRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.nation.service.TownService;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

final class RoadPlannerBuiltRoadRegistry {
    private RoadPlannerBuiltRoadRegistry() {
    }

    static void register(ServerLevel level, RoadPlannerBuildControlService.CompletedRoadBuild build) {
        if (level == null || build == null || build.roadId().isBlank() || build.centerPath().size() < 2 || build.buildSteps().isEmpty()) {
            return;
        }
        NationSavedData data = NationSavedData.get(level);
        RoadScope scope = resolveScope(data, build.ownerId());
        List<BlockPos> path = build.centerPath();
        long now = System.currentTimeMillis();
        String creatorUuid = build.ownerId() == null ? "" : build.ownerId().toString();
        String creatorName = creatorName(data, build.ownerId());
        String endAnchor = build.mergeSelection().present()
                ? "roadnode:" + build.mergeSelection().roadId() + ":" + build.mergeSelection().pathIndex()
                : plannerAnchorId("end", path.get(path.size() - 1));
        RoadNetworkRecord road = new RoadNetworkRecord(
                build.roadId(),
                scope.nationId(),
                scope.townId(),
                level.dimension().location().toString(),
                plannerAnchorId("start", path.get(0)),
                endAnchor,
                path,
                now,
                now,
                creatorUuid,
                creatorName,
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        );
        List<BuildStep> executedSteps = executedPlannerSteps(build.buildSteps(), build.rollbackEntries());
        if (executedSteps.isEmpty()) {
            return;
        }
        data.putRoadNetwork(road);
        ConstructionRuntimeSavedData.get(level).putRoadJob(new ConstructionRuntimeSavedData.RoadJobState(
                road.roadId(),
                level.dimension().location().toString(),
                build.ownerId() == null ? "" : build.ownerId().toString(),
                toLongList(road.path()),
                serializeGhostBlocks(executedSteps),
                serializeBuildSteps(executedSteps),
                rollbackStatesFromQueueEntries(build.rollbackEntries()),
                ownedBlocks(executedSteps),
                executedSteps.size(),
                executedSteps.size(),
                false,
                false,
                0,
                false,
                ownedBlocks(executedSteps)
        ));
    }

    private static List<BuildStep> executedPlannerSteps(List<BuildStep> plannerSteps,
                                                        List<ConstructionQueue.RollbackEntry> rollbackEntries) {
        if (plannerSteps == null || plannerSteps.isEmpty()) {
            return List.of();
        }
        if (rollbackEntries == null || rollbackEntries.isEmpty()) {
            return plannerSteps.stream()
                    .filter(step -> step != null && step.pos() != null)
                    .toList();
        }
        LinkedHashSet<Long> executedPositions = new LinkedHashSet<>();
        for (ConstructionQueue.RollbackEntry entry : rollbackEntries) {
            if (entry != null && entry.pos() != null) {
                executedPositions.add(entry.pos().asLong());
            }
        }
        if (executedPositions.isEmpty()) {
            return List.of();
        }
        return plannerSteps.stream()
                .filter(step -> step != null && step.pos() != null && executedPositions.contains(step.pos().asLong()))
                .toList();
    }

    private static List<ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState> serializeBuildSteps(List<BuildStep> buildSteps) {
        if (buildSteps == null || buildSteps.isEmpty()) {
            return List.of();
        }
        ArrayList<ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState> serialized = new ArrayList<>(buildSteps.size());
        for (BuildStep step : buildSteps) {
            if (step == null || step.pos() == null) {
                continue;
            }
            serialized.add(new ConstructionRuntimeSavedData.RoadJobState.RoadBuildStepState(
                    serialized.size(),
                    step.pos().asLong(),
                    blockStatePayload(step.state())
            ));
        }
        return serialized.isEmpty() ? List.of() : List.copyOf(serialized);
    }

    private static List<ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState> serializeGhostBlocks(List<BuildStep> buildSteps) {
        if (buildSteps == null || buildSteps.isEmpty()) {
            return List.of();
        }
        return buildSteps.stream()
                .filter(step -> step != null && step.pos() != null)
                .map(step -> new ConstructionRuntimeSavedData.RoadJobState.RoadGhostBlockState(
                        step.pos().asLong(),
                        blockStatePayload(step.state())))
                .toList();
    }

    private static List<ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> rollbackStatesFromQueueEntries(
            List<ConstructionQueue.RollbackEntry> rollbackEntries) {
        if (rollbackEntries == null || rollbackEntries.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<Long, ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState> states = new LinkedHashMap<>();
        for (ConstructionQueue.RollbackEntry entry : rollbackEntries) {
            if (entry == null || entry.pos() == null || entry.previousState() == null) {
                continue;
            }
            states.putIfAbsent(entry.pos().asLong(), new ConstructionRuntimeSavedData.RoadJobState.RoadRestorableBlockState(
                    entry.pos().asLong(),
                    NbtUtils.writeBlockState(entry.previousState())
            ));
        }
        return states.isEmpty() ? List.of() : List.copyOf(states.values());
    }

    private static List<Long> ownedBlocks(List<BuildStep> buildSteps) {
        if (buildSteps == null || buildSteps.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> positions = new LinkedHashSet<>();
        for (BuildStep step : buildSteps) {
            if (step != null && step.pos() != null) {
                positions.add(step.pos().asLong());
            }
        }
        return positions.isEmpty() ? List.of() : List.copyOf(positions);
    }

    private static List<Long> toLongList(List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return List.of();
        }
        return positions.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::asLong)
                .toList();
    }

    private static CompoundTag blockStatePayload(net.minecraft.world.level.block.state.BlockState state) {
        return state == null ? new CompoundTag() : NbtUtils.writeBlockState(state);
    }

    private static RoadScope resolveScope(NationSavedData data, UUID ownerId) {
        if (data == null || ownerId == null) {
            return new RoadScope("", "");
        }
        NationMemberRecord member = data.getMember(ownerId);
        TownRecord town = member == null
                ? data.getTownsForMayor(ownerId).stream().findFirst().orElse(null)
                : TownService.getTownForMember(data, member);
        String townId = town == null ? "" : town.townId();
        String nationId = town != null && !town.nationId().isBlank()
                ? town.nationId()
                : member == null ? "" : member.nationId();
        return new RoadScope(nationId, townId);
    }

    private static String creatorName(NationSavedData data, UUID ownerId) {
        if (data == null || ownerId == null) {
            return "";
        }
        NationMemberRecord member = data.getMember(ownerId);
        return member == null || member.lastKnownName() == null ? "" : member.lastKnownName();
    }

    private static String plannerAnchorId(String kind, BlockPos pos) {
        if (pos == null) {
            return "planner:" + kind;
        }
        return "planner:" + kind + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private record RoadScope(String nationId, String townId) {
        private RoadScope {
            nationId = nationId == null ? "" : nationId;
            townId = townId == null ? "" : townId;
        }
    }
}
