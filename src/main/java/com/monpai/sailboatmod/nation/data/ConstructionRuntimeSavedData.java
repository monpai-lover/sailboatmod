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

    public record RoadJobState(String roadId, String dimensionId, String ownerUuid, List<Long> path) {
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("RoadId", roadId);
            tag.putString("DimensionId", dimensionId == null ? "" : dimensionId);
            tag.putString("OwnerUuid", ownerUuid == null ? "" : ownerUuid);
            tag.putLongArray("Path", path == null ? new long[0] : path.stream().mapToLong(Long::longValue).toArray());
            return tag;
        }

        public static RoadJobState load(CompoundTag tag) {
            if (tag == null) {
                return null;
            }
            return new RoadJobState(
                    tag.getString("RoadId"),
                    tag.getString("DimensionId"),
                    tag.getString("OwnerUuid"),
                    toLongList(tag.getLongArray("Path"))
            );
        }
    }

    private static List<Long> toLongList(long[] values) {
        java.util.ArrayList<Long> result = new java.util.ArrayList<>(values.length);
        for (long value : values) {
            result.add(value);
        }
        return List.copyOf(result);
    }
}
