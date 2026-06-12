package com.monpai.sailboatmod.roadplanner.edit;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadEditSelectionPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class RoadPlannerRoadEditSelectionService {
    private static final int LEGACY_DEFAULT_WIDTH = 3;

    private RoadPlannerRoadEditSelectionService() {
    }

    public static List<OpenRoadEditSelectionPacket.Entry> listEditableRoads(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return List.of();
        }
        NationSavedData data = NationSavedData.get(level);
        RoadEditableNetworkSavedData editableData = RoadEditableNetworkSavedData.get(level);
        String dimensionId = level.dimension().location().toString();
        return listEditableRoads(data, editableData, dimensionId,
                road -> RoadEditPermissionService.canManageRoad(level, player, data, road),
                List.of());
    }

    public static List<OpenRoadEditSelectionPacket.Entry> listEditableRoads(ServerPlayer player, List<String> roadIds) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return List.of();
        }
        NationSavedData data = NationSavedData.get(level);
        RoadEditableNetworkSavedData editableData = RoadEditableNetworkSavedData.get(level);
        String dimensionId = level.dimension().location().toString();
        return listEditableRoads(data, editableData, dimensionId,
                road -> RoadEditPermissionService.canManageRoad(level, player, data, road),
                roadIds);
    }

    static List<OpenRoadEditSelectionPacket.Entry> listEditableRoadsForTest(NationSavedData data,
                                                                            RoadEditableNetworkSavedData editableData,
                                                                            UUID playerUuid,
                                                                            boolean operator,
                                                                            String dimensionId) {
        return listEditableRoads(data, editableData, dimensionId,
                road -> RoadEditPermissionService.canManageRoadForTest(playerUuid, operator, data, road),
                List.of());
    }

    public static DuplicateRouteDecision duplicateRouteDecision(ServerLevel level,
                                                                ServerPlayer player,
                                                                NationSavedData data,
                                                                String sourceTownId,
                                                                String targetTownId) {
        if (level == null || player == null) {
            return DuplicateRouteDecision.none();
        }
        String dimensionId = level.dimension().location().toString();
        return duplicateRouteDecision(data, dimensionId, sourceTownId, targetTownId,
                road -> RoadEditPermissionService.canManageRoad(level, player, data, road));
    }

    static DuplicateRouteDecision duplicateRouteDecisionForTest(NationSavedData data,
                                                                UUID playerUuid,
                                                                boolean operator,
                                                                String dimensionId,
                                                                String sourceTownId,
                                                                String targetTownId) {
        return duplicateRouteDecision(data, dimensionId, sourceTownId, targetTownId,
                road -> RoadEditPermissionService.canManageRoadForTest(playerUuid, operator, data, road));
    }

    public static Result prepareSelectedRoadForEditing(ServerPlayer player, String roadId) {
        if (player == null || !(player.level() instanceof ServerLevel level) || roadId == null || roadId.isBlank()) {
            return new Result(false, Component.literal("Invalid road edit request"));
        }
        NationSavedData data = NationSavedData.get(level);
        RoadNetworkRecord road = data.getRoadNetwork(roadId.trim().toLowerCase(Locale.ROOT));
        if (road == null) {
            return new Result(false, Component.literal("Road not found"));
        }
        if (!level.dimension().location().toString().equalsIgnoreCase(road.dimensionId())) {
            return new Result(false, Component.literal("Road is in another dimension"));
        }
        if (!RoadEditPermissionService.canManageRoad(level, player, data, road)) {
            return new Result(false, Component.literal("No permission to edit this road"));
        }
        Optional<RoadEditableRecord> editable = RoadEditableNetworkSavedData.get(level).getRoad(road.roadId());
        if (editable.isEmpty()) {
            editable = RoadEditableMigrationService.ensureLegacyLedger(level, road);
        }
        if (editable.isEmpty()) {
            return new Result(false, Component.literal("Road edit ledger could not be prepared"));
        }
        return new Result(true, Component.literal("Road edit ledger prepared"));
    }

    private static List<OpenRoadEditSelectionPacket.Entry> listEditableRoads(NationSavedData data,
                                                                             RoadEditableNetworkSavedData editableData,
                                                                             String dimensionId,
                                                                             RoadPermissionPredicate permission,
                                                                             List<String> onlyRoadIds) {
        if (data == null) {
            return List.of();
        }
        java.util.Set<String> allowedIds = normalizeRoadIds(onlyRoadIds);
        return data.getRoadNetworks().stream()
                .filter(road -> road != null && road.dimensionId().equalsIgnoreCase(nullToBlank(dimensionId)))
                .filter(road -> allowedIds.isEmpty() || allowedIds.contains(road.roadId()))
                .filter(road -> permission == null || permission.canManage(road))
                .map(road -> toEntry(data, editableData, road))
                .sorted(Comparator.comparing(OpenRoadEditSelectionPacket.Entry::sourceName)
                        .thenComparing(OpenRoadEditSelectionPacket.Entry::targetName)
                        .thenComparing(OpenRoadEditSelectionPacket.Entry::roadId))
                .toList();
    }

    private static DuplicateRouteDecision duplicateRouteDecision(NationSavedData data,
                                                                 String dimensionId,
                                                                 String sourceTownId,
                                                                 String targetTownId,
                                                                 RoadPermissionPredicate permission) {
        if (data == null) {
            return DuplicateRouteDecision.none();
        }
        String wantedKey = RoadNetworkRecord.townConnectionKey(sourceTownId, targetTownId);
        if (wantedKey.isBlank()) {
            return DuplicateRouteDecision.none();
        }
        List<RoadNetworkRecord> matching = data.getRoadNetworks().stream()
                .filter(road -> road != null && road.dimensionId().equalsIgnoreCase(nullToBlank(dimensionId)))
                .filter(road -> wantedKey.equals(townConnectionKey(road)))
                .toList();
        if (matching.isEmpty()) {
            return DuplicateRouteDecision.none();
        }
        List<String> manageable = matching.stream()
                .filter(road -> permission != null && permission.canManage(road))
                .map(RoadNetworkRecord::roadId)
                .toList();
        if (!manageable.isEmpty()) {
            return new DuplicateRouteDecision(DuplicateRouteDecision.Action.OPEN_EDIT_SELECTION, manageable);
        }
        return new DuplicateRouteDecision(
                DuplicateRouteDecision.Action.DENY_DUPLICATE,
                matching.stream().map(RoadNetworkRecord::roadId).toList());
    }

    private static OpenRoadEditSelectionPacket.Entry toEntry(NationSavedData data,
                                                             RoadEditableNetworkSavedData editableData,
                                                             RoadNetworkRecord road) {
        RoadEditableRecord editable = editableData == null ? null : editableData.getRoad(road.roadId()).orElse(null);
        String sourceName = firstNonBlank(
                editable == null ? "" : editable.sourceTownName(),
                road.routeSourceName(),
                structureName(data, road.structureAId()));
        String targetName = firstNonBlank(
                editable == null ? "" : editable.targetTownName(),
                road.routeTargetName(),
                structureName(data, road.structureBId()));
        return new OpenRoadEditSelectionPacket.Entry(
                road.roadId(),
                sourceName,
                targetName,
                road.sourceType(),
                editable == null ? road.displayPath().size() : editable.nodes().size(),
                pathLength(road.path()),
                editable == null ? LEGACY_DEFAULT_WIDTH : editable.width(),
                editable == null || editable.legacyMigrated(),
                editable == null ? "LEGACY" : editable.status().name());
    }

    private static String townConnectionKey(RoadNetworkRecord road) {
        String key = road.routeTownConnectionKey();
        if (!key.isBlank()) {
            return key;
        }
        return RoadNetworkRecord.townConnectionKey(townIdFromStructure(road.structureAId()), townIdFromStructure(road.structureBId()));
    }

    private static String townIdFromStructure(String structureId) {
        if (structureId == null) {
            return "";
        }
        String prefix = "town:";
        return structureId.regionMatches(true, 0, prefix, 0, prefix.length())
                ? structureId.substring(prefix.length())
                : "";
    }

    private static String structureName(NationSavedData data, String structureId) {
        String townId = townIdFromStructure(structureId);
        if (!townId.isBlank()) {
            TownRecord town = data == null ? null : data.getTown(townId);
            return town == null || town.name().isBlank() ? townId : town.name();
        }
        if (structureId == null || structureId.isBlank()) {
            return "-";
        }
        return structureId;
    }

    private static int pathLength(List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return 0;
        }
        double total = 0.0D;
        for (int index = 1; index < path.size(); index++) {
            total += Math.sqrt(path.get(index - 1).distSqr(path.get(index)));
        }
        return (int) Math.round(total);
    }

    private static java.util.Set<String> normalizeRoadIds(List<String> roadIds) {
        if (roadIds == null || roadIds.isEmpty()) {
            return java.util.Set.of();
        }
        return roadIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(id -> id.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "-";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "-";
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    @FunctionalInterface
    private interface RoadPermissionPredicate {
        boolean canManage(RoadNetworkRecord road);
    }

    public record DuplicateRouteDecision(Action action, List<String> roadIds) {
        public DuplicateRouteDecision {
            action = action == null ? Action.NONE : action;
            roadIds = roadIds == null ? List.of() : roadIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .map(id -> id.trim().toLowerCase(Locale.ROOT))
                    .toList();
        }

        public static DuplicateRouteDecision none() {
            return new DuplicateRouteDecision(Action.NONE, List.of());
        }

        public enum Action {
            NONE,
            OPEN_EDIT_SELECTION,
            DENY_DUPLICATE
        }
    }

    public record Result(boolean success, Component message) {
    }
}
