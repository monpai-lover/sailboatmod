package com.example.examplemod.route;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class RouteNbtUtil {
    private RouteNbtUtil() {
    }

    public static List<RouteDefinition> readRoutes(CompoundTag root, String key) {
        List<RouteDefinition> routes = new ArrayList<>();
        ListTag routesTag = root.getList(key, Tag.TAG_COMPOUND);
        for (Tag routeEntry : routesTag) {
            if (!(routeEntry instanceof CompoundTag routeTag)) {
                continue;
            }
            String routeName = routeTag.getString("Name");
            List<Vec3> waypoints = new ArrayList<>();
            ListTag pointsTag = routeTag.getList("Waypoints", Tag.TAG_COMPOUND);
            for (Tag pointEntry : pointsTag) {
                if (pointEntry instanceof CompoundTag pointTag) {
                    waypoints.add(new Vec3(
                            pointTag.getDouble("X"),
                            pointTag.getDouble("Y"),
                            pointTag.getDouble("Z")
                    ));
                }
            }
            routes.add(new RouteDefinition(routeName, waypoints));
            String authorName = routeTag.getString("AuthorName");
            String authorUuid = routeTag.getString("AuthorUuid");
            long createdAt = routeTag.getLong("CreatedAt");
            double routeLength = routeTag.contains("RouteLength") ? routeTag.getDouble("RouteLength") : 0.0D;
            String startDock = routeTag.getString("StartDock");
            String endDock = routeTag.getString("EndDock");
            routes.set(routes.size() - 1, new RouteDefinition(routeName, waypoints, authorName, authorUuid, createdAt, routeLength, startDock, endDock));
        }
        return routes;
    }

    public static void writeRoutes(CompoundTag root, String key, List<RouteDefinition> routes) {
        ListTag routesTag = new ListTag();
        for (RouteDefinition route : routes) {
            CompoundTag routeTag = new CompoundTag();
            routeTag.putString("Name", route.name());
            routeTag.putString("AuthorName", route.authorName());
            routeTag.putString("AuthorUuid", route.authorUuid());
            routeTag.putLong("CreatedAt", route.createdAtEpochMillis());
            routeTag.putDouble("RouteLength", route.routeLengthMeters());
            routeTag.putString("StartDock", route.startDockName());
            routeTag.putString("EndDock", route.endDockName());
            ListTag pointsTag = new ListTag();
            for (Vec3 waypoint : route.waypoints()) {
                CompoundTag pointTag = new CompoundTag();
                pointTag.putDouble("X", waypoint.x);
                pointTag.putDouble("Y", waypoint.y);
                pointTag.putDouble("Z", waypoint.z);
                pointsTag.add(pointTag);
            }
            routeTag.put("Waypoints", pointsTag);
            routesTag.add(routeTag);
        }
        root.put(key, routesTag);
    }
}
