package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadDemolitionSelectionPacket;
import com.monpai.sailboatmod.roadplanner.edit.RoadEditPermissionService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Comparator;
import java.util.List;

public final class RoadPlannerRoadDemolitionService {
    private RoadPlannerRoadDemolitionService() {
    }

    public static List<OpenRoadDemolitionSelectionPacket.Entry> listDemolishableRoads(ServerPlayer player) {
        if (player == null || !(player.level() instanceof ServerLevel level)) {
            return List.of();
        }
        NationSavedData data = NationSavedData.get(level);
        String dimensionId = level.dimension().location().toString();
        return data.getRoadNetworks().stream()
                .filter(road -> road != null && dimensionId.equalsIgnoreCase(road.dimensionId()))
                .filter(road -> RoadEditPermissionService.canManageRoad(level, player, data, road))
                .map(road -> toEntry(data, road))
                .sorted(Comparator.comparing(OpenRoadDemolitionSelectionPacket.Entry::sourceName)
                        .thenComparing(OpenRoadDemolitionSelectionPacket.Entry::targetName)
                        .thenComparing(OpenRoadDemolitionSelectionPacket.Entry::roadId))
                .toList();
    }

    public static Result demolishSelectedRoad(ServerPlayer player, String roadId) {
        if (player == null || !(player.level() instanceof ServerLevel level) || roadId == null || roadId.isBlank()) {
            return new Result(false, Component.literal("Invalid road demolition request"));
        }
        NationSavedData data = NationSavedData.get(level);
        RoadNetworkRecord road = data.getRoadNetwork(roadId);
        if (road == null) {
            return new Result(false, Component.literal("Road not found"));
        }
        if (!level.dimension().location().toString().equalsIgnoreCase(road.dimensionId())) {
            return new Result(false, Component.literal("Road is in another dimension"));
        }
        if (!RoadEditPermissionService.canManageRoad(level, player, data, road)) {
            return new Result(false, Component.literal("No permission to demolish this road"));
        }
        boolean started = RoadLifecycleService.demolishPersistedRoad(level, road.roadId());
        return new Result(started, started
                ? Component.literal("Road demolition queued")
                : Component.literal("Road demolition failed"));
    }

    private static OpenRoadDemolitionSelectionPacket.Entry toEntry(NationSavedData data, RoadNetworkRecord road) {
        String sourceName = road.routeSourceName().isBlank()
                ? structureName(data, road.structureAId())
                : road.routeSourceName();
        String targetName = road.routeTargetName().isBlank()
                ? structureName(data, road.structureBId())
                : road.routeTargetName();
        return new OpenRoadDemolitionSelectionPacket.Entry(
                road.roadId(),
                sourceName,
                targetName,
                road.sourceType(),
                road.path().size(),
                pathLength(road.path()));
    }

    private static String structureName(NationSavedData data, String structureId) {
        if (structureId == null || structureId.isBlank()) {
            return "-";
        }
        String prefix = "town:";
        if (structureId.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String townId = structureId.substring(prefix.length());
            TownRecord town = data == null ? null : data.getTown(townId);
            return town == null || town.name().isBlank() ? townId : town.name();
        }
        String plannerPrefix = "planner:";
        if (structureId.regionMatches(true, 0, plannerPrefix, 0, plannerPrefix.length())) {
            return plannerAnchorName(structureId.substring(plannerPrefix.length()));
        }
        return structureId;
    }

    private static String plannerAnchorName(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        int separator = value.indexOf(':');
        String kind = separator < 0 ? value : value.substring(0, separator);
        String coords = separator < 0 ? "" : value.substring(separator + 1);
        String label = "end".equalsIgnoreCase(kind) ? "End" : "Start";
        return coords.isBlank() ? label : label + " " + coords;
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

    public record Result(boolean success, Component message) {
    }
}
