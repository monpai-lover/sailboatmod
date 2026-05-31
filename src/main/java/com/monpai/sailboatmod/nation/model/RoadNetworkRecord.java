package com.monpai.sailboatmod.nation.model;

import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record RoadNetworkRecord(
        String roadId,
        String nationId,
        String townId,
        String dimensionId,
        String structureAId,
        String structureBId,
        List<BlockPos> path,
        List<BlockPos> displayPath,
        List<RoadPlannerSharedRoadSpan> sharedSpans,
        long updatedAt,
        long createdAt,
        String creatorUuid,
        String creatorName,
        String sourceType,
        String routeSourceName,
        String routeTargetName
) {
    public static final String SOURCE_TYPE_AUTO = "AUTO";
    public static final String SOURCE_TYPE_MANUAL = "MANUAL";

    public RoadNetworkRecord {
        roadId = roadId == null ? "" : roadId.trim().toLowerCase(Locale.ROOT);
        nationId = nationId == null ? "" : nationId.trim().toLowerCase(Locale.ROOT);
        townId = townId == null ? "" : townId.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        structureAId = structureAId == null ? "" : structureAId.trim();
        structureBId = structureBId == null ? "" : structureBId.trim();
        path = normalizePath(path);
        displayPath = normalizeDisplayPath(path, displayPath);
        sharedSpans = normalizeSharedSpans(sharedSpans);
        createdAt = createdAt <= 0L ? updatedAt : createdAt;
        creatorUuid = creatorUuid == null ? "" : creatorUuid.trim();
        creatorName = creatorName == null ? "" : creatorName.trim();
        sourceType = sourceType == null || sourceType.isBlank()
                ? SOURCE_TYPE_AUTO
                : sourceType.trim().toUpperCase(Locale.ROOT);
        routeSourceName = routeSourceName == null ? "" : routeSourceName.trim();
        routeTargetName = routeTargetName == null ? "" : routeTargetName.trim();
    }

    public RoadNetworkRecord(String roadId,
                             String nationId,
                             String townId,
                             String dimensionId,
                             String structureAId,
                             String structureBId,
                             List<BlockPos> path,
                             long updatedAt,
                             long createdAt,
                             String creatorUuid,
                             String creatorName,
                             String sourceType) {
        this(roadId, nationId, townId, dimensionId, structureAId, structureBId, path,
                null, List.of(), updatedAt, createdAt, creatorUuid, creatorName, sourceType, "", "");
    }

    public RoadNetworkRecord(String roadId,
                             String nationId,
                             String townId,
                             String dimensionId,
                             String structureAId,
                             String structureBId,
                             List<BlockPos> path,
                             long updatedAt,
                             long createdAt,
                             String creatorUuid,
                             String creatorName,
                             String sourceType,
                             String routeSourceName,
                             String routeTargetName) {
        this(roadId, nationId, townId, dimensionId, structureAId, structureBId, path,
                null, List.of(), updatedAt, createdAt, creatorUuid, creatorName, sourceType, routeSourceName, routeTargetName);
    }

    public RoadNetworkRecord(String roadId,
                             String nationId,
                             String townId,
                             String dimensionId,
                             String structureAId,
                             String structureBId,
                             List<BlockPos> path,
                             long updatedAt,
                             String sourceType) {
        this(roadId, nationId, townId, dimensionId, structureAId, structureBId, path,
                null, List.of(), updatedAt, updatedAt, "", "", sourceType, "", "");
    }

    public static String edgeKey(String leftStructureId, String rightStructureId) {
        String left = leftStructureId == null ? "" : leftStructureId.trim().toLowerCase(Locale.ROOT);
        String right = rightStructureId == null ? "" : rightStructureId.trim().toLowerCase(Locale.ROOT);
        if (left.isBlank() || right.isBlank() || left.equals(right)) {
            return "";
        }
        return left.compareTo(right) <= 0 ? left + "|" + right : right + "|" + left;
    }

    public boolean connects(String structureId) {
        if (structureId == null || structureId.isBlank()) {
            return false;
        }
        return structureAId.equalsIgnoreCase(structureId) || structureBId.equalsIgnoreCase(structureId);
    }

    public boolean sameScope(String scopeTownId, String scopeNationId, String scopeDimensionId) {
        if (scopeDimensionId == null || !dimensionId.equals(scopeDimensionId)) {
            return false;
        }
        if (!townId.isBlank() && scopeTownId != null && !scopeTownId.isBlank()) {
            return townId.equals(scopeTownId);
        }
        return scopeNationId != null && !scopeNationId.isBlank() && nationId.equalsIgnoreCase(scopeNationId);
    }

    public boolean isAutoManaged() {
        return SOURCE_TYPE_AUTO.equals(sourceType);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", roadId);
        tag.putString("NationId", nationId);
        tag.putString("TownId", townId);
        tag.putString("Dim", dimensionId);
        tag.putString("A", structureAId);
        tag.putString("B", structureBId);
        tag.putLong("UpdatedAt", updatedAt);
        tag.putLong("CreatedAt", createdAt);
        tag.putString("CreatorUuid", creatorUuid);
        tag.putString("CreatorName", creatorName);
        tag.putString("SourceType", sourceType);
        tag.putString("RouteSourceName", routeSourceName);
        tag.putString("RouteTargetName", routeTargetName);
        tag.put("Path", writePath(path));
        tag.put("DisplayPath", writePath(displayPath));
        tag.put("SharedSpans", writeSharedSpans(sharedSpans));
        return tag;
    }

    public static RoadNetworkRecord load(CompoundTag tag) {
        List<BlockPos> path = readPath(tag, "Path");
        List<BlockPos> displayPath = tag.contains("DisplayPath", Tag.TAG_LIST)
                ? readPath(tag, "DisplayPath")
                : List.of();
        List<RoadPlannerSharedRoadSpan> sharedSpans = readSharedSpans(tag);
        String structureA = tag.getString("A");
        String structureB = tag.getString("B");
        String roadId = tag.contains("Id") ? tag.getString("Id") : edgeKey(structureA, structureB);
        long updatedAt = tag.getLong("UpdatedAt");
        long createdAt = tag.contains("CreatedAt") ? tag.getLong("CreatedAt") : updatedAt;
        return new RoadNetworkRecord(
                roadId,
                tag.getString("NationId"),
                tag.getString("TownId"),
                tag.getString("Dim"),
                structureA,
                structureB,
                path,
                displayPath,
                sharedSpans,
                updatedAt,
                createdAt,
                tag.contains("CreatorUuid") ? tag.getString("CreatorUuid") : "",
                tag.contains("CreatorName") ? tag.getString("CreatorName") : "",
                tag.contains("SourceType") ? tag.getString("SourceType") : SOURCE_TYPE_AUTO,
                tag.contains("RouteSourceName") ? tag.getString("RouteSourceName") : "",
                tag.contains("RouteTargetName") ? tag.getString("RouteTargetName") : ""
        );
    }

    private static List<BlockPos> normalizePath(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        return path.stream()
                .filter(java.util.Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    private static List<BlockPos> normalizeDisplayPath(List<BlockPos> path, List<BlockPos> displayPath) {
        List<BlockPos> normalizedDisplayPath = normalizePath(displayPath);
        if (!normalizedDisplayPath.isEmpty()) {
            return normalizedDisplayPath;
        }
        return simplifyDisplayPath(path);
    }

    private static List<RoadPlannerSharedRoadSpan> normalizeSharedSpans(List<RoadPlannerSharedRoadSpan> spans) {
        if (spans == null || spans.isEmpty()) {
            return List.of();
        }
        return spans.stream()
                .filter(java.util.Objects::nonNull)
                .filter(RoadPlannerSharedRoadSpan::present)
                .toList();
    }

    private static List<BlockPos> simplifyDisplayPath(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        if (path.size() <= 2) {
            return List.copyOf(path);
        }
        List<BlockPos> simplified = new ArrayList<>();
        simplified.add(path.get(0).immutable());
        for (int index = 1; index < path.size() - 1; index++) {
            BlockPos previous = path.get(index - 1);
            BlockPos current = path.get(index);
            BlockPos next = path.get(index + 1);
            if (previous == null || current == null || next == null) {
                continue;
            }
            boolean heightChanges = current.getY() != previous.getY() || current.getY() != next.getY();
            int previousDx = Integer.compare(current.getX() - previous.getX(), 0);
            int previousDz = Integer.compare(current.getZ() - previous.getZ(), 0);
            int nextDx = Integer.compare(next.getX() - current.getX(), 0);
            int nextDz = Integer.compare(next.getZ() - current.getZ(), 0);
            boolean directionChanges = previousDx != nextDx || previousDz != nextDz;
            if (heightChanges || directionChanges) {
                simplified.add(current.immutable());
            }
        }
        simplified.add(path.get(path.size() - 1).immutable());
        return List.copyOf(simplified);
    }

    private static ListTag writePath(List<BlockPos> path) {
        ListTag pathTag = new ListTag();
        if (path == null) {
            return pathTag;
        }
        for (BlockPos pos : path) {
            if (pos == null) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putLong("Pos", pos.asLong());
            pathTag.add(entry);
        }
        return pathTag;
    }

    private static List<BlockPos> readPath(CompoundTag tag, String key) {
        List<BlockPos> path = new ArrayList<>();
        ListTag pathTag = tag.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < pathTag.size(); i++) {
            path.add(BlockPos.of(pathTag.getCompound(i).getLong("Pos")));
        }
        return List.copyOf(path);
    }

    private static ListTag writeSharedSpans(List<RoadPlannerSharedRoadSpan> spans) {
        ListTag spanTag = new ListTag();
        if (spans == null) {
            return spanTag;
        }
        for (RoadPlannerSharedRoadSpan span : spans) {
            if (span == null || !span.present()) {
                continue;
            }
            CompoundTag entry = new CompoundTag();
            entry.putString("RoadId", span.roadId());
            entry.putInt("FromIndex", span.fromPathIndex());
            entry.putInt("ToIndex", span.toPathIndex());
            entry.putLong("FromPos", span.fromPos().asLong());
            entry.putLong("ToPos", span.toPos().asLong());
            entry.putString("Scope", span.scope().name());
            entry.putString("Role", span.role().name());
            spanTag.add(entry);
        }
        return spanTag;
    }

    private static List<RoadPlannerSharedRoadSpan> readSharedSpans(CompoundTag tag) {
        ListTag spanTag = tag.getList("SharedSpans", Tag.TAG_COMPOUND);
        if (spanTag.isEmpty()) {
            return List.of();
        }
        List<RoadPlannerSharedRoadSpan> spans = new ArrayList<>();
        for (int i = 0; i < spanTag.size(); i++) {
            CompoundTag entry = spanTag.getCompound(i);
            RoadPlannerMergeScope scope = parseEnum(
                    RoadPlannerMergeScope.class,
                    entry.getString("Scope"),
                    RoadPlannerMergeScope.DISABLED);
            RoadPlannerSharedRoadSpan.Role role = parseEnum(
                    RoadPlannerSharedRoadSpan.Role.class,
                    entry.getString("Role"),
                    RoadPlannerSharedRoadSpan.Role.END_MERGE);
            RoadPlannerSharedRoadSpan span = new RoadPlannerSharedRoadSpan(
                    entry.getString("RoadId"),
                    entry.getInt("FromIndex"),
                    entry.getInt("ToIndex"),
                    BlockPos.of(entry.getLong("FromPos")),
                    BlockPos.of(entry.getLong("ToPos")),
                    scope,
                    role);
            if (span.present()) {
                spans.add(span);
            }
        }
        return List.copyOf(spans);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String value, E fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
