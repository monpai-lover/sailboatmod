package com.monpai.sailboatmod.nation.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConstructionRuntimeSavedData extends SavedData {
    private static final String DATA_NAME = "sailboatmod_construction_runtime";

    private final Map<String, StructureJobState> structureJobs = new LinkedHashMap<>();
    private final Map<String, RoadJobState> roadJobs = new LinkedHashMap<>();

    public static ConstructionRuntimeSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new ConstructionRuntimeSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(
                ConstructionRuntimeSavedData::load,
                ConstructionRuntimeSavedData::new,
                DATA_NAME
        );
    }

    public static ConstructionRuntimeSavedData load(CompoundTag tag) {
        ConstructionRuntimeSavedData data = new ConstructionRuntimeSavedData();

        ListTag structures = tag.getList("StructureJobs", Tag.TAG_COMPOUND);
        for (Tag raw : structures) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            StructureJobState state = StructureJobState.load(compound);
            if (state != null && !state.jobId().isBlank()) {
                data.structureJobs.put(state.jobId(), state);
            }
        }

        ListTag roads = tag.getList("RoadJobs", Tag.TAG_COMPOUND);
        for (Tag raw : roads) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            RoadJobState state = RoadJobState.load(compound);
            if (state != null && !state.roadId().isBlank()) {
                data.roadJobs.put(state.roadId(), state);
            }
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag structures = new ListTag();
        for (StructureJobState state : structureJobs.values()) {
            structures.add(state.save());
        }
        tag.put("StructureJobs", structures);

        ListTag roads = new ListTag();
        for (RoadJobState state : roadJobs.values()) {
            roads.add(state.save());
        }
        tag.put("RoadJobs", roads);
        return tag;
    }

    public Collection<StructureJobState> getStructureJobs() {
        return structureJobs.values();
    }

    public Collection<RoadJobState> getRoadJobs() {
        return roadJobs.values();
    }

    public void putStructureJob(StructureJobState state) {
        if (state == null || state.jobId().isBlank()) {
            return;
        }
        structureJobs.put(state.jobId(), state);
        setDirty();
    }

    public void removeStructureJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        if (structureJobs.remove(jobId) != null) {
            setDirty();
        }
    }

    public void putRoadJob(RoadJobState state) {
        if (state == null || state.roadId().isBlank()) {
            return;
        }
        roadJobs.put(state.roadId(), state);
        setDirty();
    }

    public void removeRoadJob(String roadId) {
        if (roadId == null || roadId.isBlank()) {
            return;
        }
        if (roadJobs.remove(roadId) != null) {
            setDirty();
        }
    }

    public record StructureJobState(String jobId,
                                    String dimensionId,
                                    String ownerUuid,
                                    String typeId,
                                    long origin,
                                    int rotation,
                                    String projectId,
                                    List<Long> scaffoldPositions) {
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("JobId", jobId);
            tag.putString("DimensionId", dimensionId == null ? "" : dimensionId);
            tag.putString("OwnerUuid", ownerUuid == null ? "" : ownerUuid);
            tag.putString("TypeId", typeId == null ? "" : typeId);
            tag.putLong("Origin", origin);
            tag.putInt("Rotation", rotation);
            tag.putString("ProjectId", projectId == null ? "" : projectId);
            tag.putLongArray("Scaffolds", scaffoldPositions == null
                    ? new long[0]
                    : scaffoldPositions.stream().mapToLong(Long::longValue).toArray());
            return tag;
        }

        public static StructureJobState load(CompoundTag tag) {
            if (tag == null) {
                return null;
            }
            return new StructureJobState(
                    tag.getString("JobId"),
                    tag.getString("DimensionId"),
                    tag.getString("OwnerUuid"),
                    tag.getString("TypeId"),
                    tag.getLong("Origin"),
                    tag.getInt("Rotation"),
                    tag.getString("ProjectId"),
                    toLongList(tag.getLongArray("Scaffolds"))
            );
        }
    }

    public record RoadJobState(String roadId,
                               String dimensionId,
                               String ownerUuid,
                               List<Long> centerPath,
                               List<RoadGhostBlockState> ghostBlocks,
                               List<RoadBuildStepState> buildSteps,
                               List<RoadRestorableBlockState> rollbackStates,
                               List<Long> ownedBlocks,
                               int placedStepCount,
                               boolean legacyPathOnly,
                               boolean rollbackActive,
                               int rollbackActionIndex,
                               boolean removeRoadNetworkOnComplete,
                               List<Long> attemptedStepPositions) {
        private static final int FORMAT_VERSION = 6;

        public RoadJobState {
            roadId = roadId == null ? "" : roadId;
            dimensionId = dimensionId == null ? "" : dimensionId;
            ownerUuid = ownerUuid == null ? "" : ownerUuid;
            centerPath = centerPath == null ? List.of() : List.copyOf(centerPath);
            ghostBlocks = ghostBlocks == null ? List.of() : List.copyOf(ghostBlocks);
            buildSteps = buildSteps == null ? List.of() : List.copyOf(buildSteps);
            rollbackStates = rollbackStates == null ? List.of() : List.copyOf(rollbackStates);
            ownedBlocks = ownedBlocks == null ? List.of() : List.copyOf(ownedBlocks);
            attemptedStepPositions = attemptedStepPositions == null ? List.of() : List.copyOf(attemptedStepPositions);
            placedStepCount = Math.max(0, placedStepCount);
            rollbackActionIndex = Math.max(0, rollbackActionIndex);
        }

        public RoadJobState(String roadId,
                            String dimensionId,
                            String ownerUuid,
                            List<Long> centerPath,
                            List<RoadGhostBlockState> ghostBlocks,
                            List<RoadBuildStepState> buildSteps,
                            List<Long> ownedBlocks,
                            int placedStepCount,
                            boolean legacyPathOnly) {
            this(roadId, dimensionId, ownerUuid, centerPath, ghostBlocks, buildSteps, List.of(), ownedBlocks, placedStepCount, legacyPathOnly, false, 0, false, List.of());
        }

        public RoadJobState(String roadId,
                            String dimensionId,
                            String ownerUuid,
                            List<Long> centerPath,
                            List<RoadGhostBlockState> ghostBlocks,
                            List<RoadBuildStepState> buildSteps,
                            int placedStepCount,
                            boolean legacyPathOnly) {
            this(roadId, dimensionId, ownerUuid, centerPath, ghostBlocks, buildSteps, List.of(), List.of(), placedStepCount, legacyPathOnly, false, 0, false, List.of());
        }

        public RoadJobState(String roadId,
                            String dimensionId,
                            String ownerUuid,
                            List<Long> centerPath,
                            List<RoadGhostBlockState> ghostBlocks,
                            List<RoadBuildStepState> buildSteps,
                            List<RoadRestorableBlockState> rollbackStates,
                            List<Long> ownedBlocks,
                            int placedStepCount,
                            boolean legacyPathOnly,
                            int rollbackActionIndex,
                            boolean removeRoadNetworkOnComplete) {
            this(roadId, dimensionId, ownerUuid, centerPath, ghostBlocks, buildSteps, rollbackStates, ownedBlocks, placedStepCount, legacyPathOnly, true, rollbackActionIndex, removeRoadNetworkOnComplete, List.of());
        }

        public List<Long> path() {
            return centerPath;
        }

        public boolean isLegacyPathOnly() {
            return legacyPathOnly;
        }

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("FormatVersion", FORMAT_VERSION);
            tag.putString("RoadId", roadId);
            tag.putString("DimensionId", dimensionId == null ? "" : dimensionId);
            tag.putString("OwnerUuid", ownerUuid == null ? "" : ownerUuid);
            long[] centerPathArray = centerPath == null ? new long[0] : centerPath.stream().mapToLong(Long::longValue).toArray();
            tag.putLongArray("CenterPath", centerPathArray);
            tag.putLongArray("Path", centerPathArray);
            long[] ownedBlocksArray = ownedBlocks == null ? new long[0] : ownedBlocks.stream().mapToLong(Long::longValue).toArray();
            tag.putLongArray("OwnedBlocks", ownedBlocksArray);
            tag.putInt("PlacedStepCount", placedStepCount);
            tag.putBoolean("RollbackActive", rollbackActive);
            tag.putInt("RollbackActionIndex", rollbackActionIndex);
            tag.putBoolean("RemoveRoadNetworkOnComplete", removeRoadNetworkOnComplete);
            long[] attemptedArray = attemptedStepPositions == null ? new long[0] : attemptedStepPositions.stream().mapToLong(Long::longValue).toArray();
            tag.putLongArray("AttemptedStepPositions", attemptedArray);

            ListTag ghostBlockTags = new ListTag();
            for (RoadGhostBlockState ghostBlock : ghostBlocks) {
                ghostBlockTags.add(ghostBlock.save());
            }
            tag.put("GhostBlocks", ghostBlockTags);

            ListTag buildStepTags = new ListTag();
            for (RoadBuildStepState buildStep : buildSteps) {
                buildStepTags.add(buildStep.save());
            }
            tag.put("BuildSteps", buildStepTags);

            ListTag rollbackStateTags = new ListTag();
            for (RoadRestorableBlockState rollbackState : rollbackStates) {
                rollbackStateTags.add(rollbackState.save());
            }
            tag.put("RollbackStates", rollbackStateTags);
            return tag;
        }

        public static RoadJobState load(CompoundTag tag) {
            if (tag == null) {
                return null;
            }
            boolean legacyPathOnly = !tag.contains("FormatVersion", Tag.TAG_INT)
                    && !tag.contains("CenterPath", Tag.TAG_LONG_ARRAY)
                    && !tag.contains("GhostBlocks", Tag.TAG_LIST)
                    && !tag.contains("BuildSteps", Tag.TAG_LIST)
                    && !tag.contains("PlacedStepCount", Tag.TAG_INT);
            String pathKey = tag.contains("CenterPath", Tag.TAG_LONG_ARRAY) ? "CenterPath" : "Path";
            return new RoadJobState(
                    tag.getString("RoadId"),
                    tag.getString("DimensionId"),
                    tag.getString("OwnerUuid"),
                    toLongList(tag.getLongArray(pathKey)),
                    toRoadGhostBlockList(tag.getList("GhostBlocks", Tag.TAG_COMPOUND)),
                    toRoadBuildStepList(tag.getList("BuildSteps", Tag.TAG_COMPOUND)),
                    toRoadRollbackStateList(tag.getList("RollbackStates", Tag.TAG_COMPOUND)),
                    toLongList(tag.getLongArray("OwnedBlocks")),
                    legacyPathOnly ? 0 : tag.getInt("PlacedStepCount"),
                    legacyPathOnly,
                    tag.contains("RollbackActive", Tag.TAG_BYTE) && tag.getBoolean("RollbackActive"),
                    tag.contains("RollbackActionIndex", Tag.TAG_INT) ? tag.getInt("RollbackActionIndex") : 0,
                    tag.contains("RemoveRoadNetworkOnComplete", Tag.TAG_BYTE) && tag.getBoolean("RemoveRoadNetworkOnComplete"),
                    toLongList(tag.getLongArray("AttemptedStepPositions"))
            );
        }

        public record RoadGhostBlockState(long pos, CompoundTag statePayload) {
            public RoadGhostBlockState {
                statePayload = copyPayload(statePayload);
            }

            public CompoundTag save() {
                CompoundTag tag = new CompoundTag();
                tag.putLong("Pos", pos);
                tag.put("State", copyPayload(statePayload));
                return tag;
            }

            public static RoadGhostBlockState load(CompoundTag tag) {
                if (tag == null) {
                    return null;
                }
                return new RoadGhostBlockState(
                        tag.getLong("Pos"),
                        tag.contains("State", Tag.TAG_COMPOUND) ? tag.getCompound("State") : new CompoundTag()
                );
            }
        }

        public record RoadBuildStepState(int order, long pos, CompoundTag statePayload) {
            public RoadBuildStepState {
                order = Math.max(0, order);
                statePayload = copyPayload(statePayload);
            }

            public CompoundTag save() {
                CompoundTag tag = new CompoundTag();
                tag.putInt("Order", order);
                tag.putLong("Pos", pos);
                tag.put("State", copyPayload(statePayload));
                return tag;
            }

            public static RoadBuildStepState load(CompoundTag tag) {
                if (tag == null) {
                    return null;
                }
                return new RoadBuildStepState(
                        tag.getInt("Order"),
                        tag.getLong("Pos"),
                        tag.contains("State", Tag.TAG_COMPOUND) ? tag.getCompound("State") : new CompoundTag()
                );
            }
        }

        public record RoadRestorableBlockState(long pos, CompoundTag statePayload) {
            public RoadRestorableBlockState {
                statePayload = copyPayload(statePayload);
            }

            public CompoundTag save() {
                CompoundTag tag = new CompoundTag();
                tag.putLong("Pos", pos);
                tag.put("State", copyPayload(statePayload));
                return tag;
            }

            public static RoadRestorableBlockState load(CompoundTag tag) {
                if (tag == null) {
                    return null;
                }
                return new RoadRestorableBlockState(
                        tag.getLong("Pos"),
                        tag.contains("State", Tag.TAG_COMPOUND) ? tag.getCompound("State") : new CompoundTag()
                );
            }
        }

        private static CompoundTag copyPayload(CompoundTag statePayload) {
            return statePayload == null ? new CompoundTag() : statePayload.copy();
        }
    }

    private static List<Long> toLongList(long[] values) {
        java.util.ArrayList<Long> result = new java.util.ArrayList<>(values.length);
        for (long value : values) {
            result.add(value);
        }
        return List.copyOf(result);
    }

    private static List<RoadJobState.RoadGhostBlockState> toRoadGhostBlockList(ListTag tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<RoadJobState.RoadGhostBlockState> result = new java.util.ArrayList<>(tags.size());
        for (Tag raw : tags) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            RoadJobState.RoadGhostBlockState state = RoadJobState.RoadGhostBlockState.load(compound);
            if (state != null) {
                result.add(state);
            }
        }
        return List.copyOf(result);
    }

    private static List<RoadJobState.RoadBuildStepState> toRoadBuildStepList(ListTag tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<RoadJobState.RoadBuildStepState> result = new java.util.ArrayList<>(tags.size());
        for (Tag raw : tags) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            RoadJobState.RoadBuildStepState state = RoadJobState.RoadBuildStepState.load(compound);
            if (state != null) {
                result.add(state);
            }
        }
        return List.copyOf(result);
    }

    private static List<RoadJobState.RoadRestorableBlockState> toRoadRollbackStateList(ListTag tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        java.util.ArrayList<RoadJobState.RoadRestorableBlockState> result = new java.util.ArrayList<>(tags.size());
        for (Tag raw : tags) {
            if (!(raw instanceof CompoundTag compound)) {
                continue;
            }
            RoadJobState.RoadRestorableBlockState state = RoadJobState.RoadRestorableBlockState.load(compound);
            if (state != null) {
                result.add(state);
            }
        }
        return List.copyOf(result);
    }
}
