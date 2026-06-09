package com.monpai.sailboatmod.dock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public record PostStationScreenData(
        BlockPos stationPos,
        String stationName,
        String townId,
        String townName,
        boolean canManage,
        List<ReachableTownEntry> reachableTowns,
        int selectedTownIndex,
        List<VehicleEntry> vehicles,
        int selectedVehicleIndex,
        boolean autoReturnOnDispatch,
        RouteSummary selectedRouteSummary,
        List<Vec3> selectedRouteWaypoints,
        DockScreenData advancedData
) {
    public PostStationScreenData {
        stationPos = stationPos == null ? BlockPos.ZERO : stationPos.immutable();
        stationName = clean(stationName);
        townId = clean(townId);
        townName = clean(townName);
        reachableTowns = reachableTowns == null ? List.of() : List.copyOf(reachableTowns);
        vehicles = vehicles == null ? List.of() : List.copyOf(vehicles);
        selectedTownIndex = reachableTowns.isEmpty() ? 0 : Math.max(0, Math.min(selectedTownIndex, reachableTowns.size() - 1));
        selectedVehicleIndex = vehicles.isEmpty() ? 0 : Math.max(0, Math.min(selectedVehicleIndex, vehicles.size() - 1));
        selectedRouteSummary = selectedRouteSummary == null ? RouteSummary.empty() : selectedRouteSummary;
        selectedRouteWaypoints = selectedRouteWaypoints == null ? List.of() : List.copyOf(selectedRouteWaypoints);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public record ReachableTownEntry(String townId,
                                     String townName,
                                     BlockPos stationPos,
                                     String stationName,
                                     int distanceMeters,
                                     int etaSeconds,
                                     String routeSource,
                                     List<String> passThroughTownNames,
                                     String routeName) {
        public ReachableTownEntry {
            townId = clean(townId);
            townName = clean(townName);
            stationPos = stationPos == null ? BlockPos.ZERO : stationPos.immutable();
            stationName = clean(stationName);
            distanceMeters = Math.max(0, distanceMeters);
            etaSeconds = Math.max(0, etaSeconds);
            routeSource = clean(routeSource);
            passThroughTownNames = passThroughTownNames == null ? List.of() : List.copyOf(passThroughTownNames);
            routeName = clean(routeName);
        }
    }

    public record VehicleEntry(int entityId,
                               String name,
                               Vec3 position,
                               String state,
                               boolean ownedOrRentable,
                               boolean recallable,
                               String dockedTownName) {
        public VehicleEntry {
            name = clean(name);
            position = position == null ? Vec3.ZERO : position;
            state = clean(state);
            dockedTownName = clean(dockedTownName);
        }
    }

    public record RouteSummary(String routeName,
                               int distanceMeters,
                               int etaSeconds,
                               List<String> passThroughTownNames) {
        public RouteSummary {
            routeName = clean(routeName);
            distanceMeters = Math.max(0, distanceMeters);
            etaSeconds = Math.max(0, etaSeconds);
            passThroughTownNames = passThroughTownNames == null ? List.of() : List.copyOf(passThroughTownNames);
        }

        public static RouteSummary empty() {
            return new RouteSummary("", 0, 0, List.of());
        }
    }
}
