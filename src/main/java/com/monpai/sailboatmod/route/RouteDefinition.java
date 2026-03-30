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
        String endDockName
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
    }

    public RouteDefinition(String name, List<Vec3> waypoints) {
        this(name, waypoints, "", "", 0L, 0.0D, "", "");
    }

    public RouteDefinition(String name, List<Vec3> waypoints, String authorName, String authorUuid, long createdAtEpochMillis, double routeLengthMeters) {
        this(name, waypoints, authorName, authorUuid, createdAtEpochMillis, routeLengthMeters, "", "");
    }

    public RouteDefinition copy() {
        return new RouteDefinition(name, new ArrayList<>(waypoints), authorName, authorUuid, createdAtEpochMillis, routeLengthMeters, startDockName, endDockName);
    }
}
