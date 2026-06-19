package com.monpai.sailboatmod.route;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public record RouteDefinition(
        String name,
        List<Vec3> waypoints,
        String authorName,
        String authorUuid,
        long createdAtEpochMillis,
        double routeLengthMeters,
        String startDockName,
        String endDockName,
        List<WaypointMeta> waypointMetas
) {
    public RouteDefinition {
        name = name == null ? "" : name;
        waypoints = waypoints == null ? List.of() : List.copyOf(waypoints);
        authorName = authorName == null ? "" : authorName;
        authorUuid = authorUuid == null ? "" : authorUuid;
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        routeLengthMeters = Math.max(0.0D, routeLengthMeters);
        startDockName = startDockName == null ? "" : startDockName;
        endDockName = endDockName == null ? "" : endDockName;
        // 只水路自动航线填 metas;其它航线为空。非空时不强制与 waypoints 等长(debug 工具按 index 容错取)。
        waypointMetas = waypointMetas == null ? List.of() : List.copyOf(waypointMetas);
    }

    public RouteDefinition(String name, List<Vec3> waypoints) {
        this(name, waypoints, "", "", 0L, 0.0D, "", "", List.of());
    }

    public RouteDefinition(String name, List<Vec3> waypoints, String authorName, String authorUuid, long createdAtEpochMillis, double routeLengthMeters) {
        this(name, waypoints, authorName, authorUuid, createdAtEpochMillis, routeLengthMeters, "", "", List.of());
    }

    public RouteDefinition(String name, List<Vec3> waypoints, String authorName, String authorUuid, long createdAtEpochMillis, double routeLengthMeters, String startDockName, String endDockName) {
        this(name, waypoints, authorName, authorUuid, createdAtEpochMillis, routeLengthMeters, startDockName, endDockName, List.of());
    }

    public RouteDefinition copy() {
        return new RouteDefinition(name, new ArrayList<>(waypoints), authorName, authorUuid, createdAtEpochMillis, routeLengthMeters, startDockName, endDockName, new ArrayList<>(waypointMetas));
    }
}
