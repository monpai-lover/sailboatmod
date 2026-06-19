package com.monpai.sailboatmod.route;

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
            List<WaypointMeta> metas = new ArrayList<>();
            boolean allHaveMeta = true; // 只有每个 point 都带 Seg 标记才认 metas(旧航线无标记→留空降级)
            ListTag pointsTag = routeTag.getList("Waypoints", Tag.TAG_COMPOUND);
            for (Tag pointEntry : pointsTag) {
                if (pointEntry instanceof CompoundTag pointTag) {
                    waypoints.add(new Vec3(
                            pointTag.getDouble("X"),
                            pointTag.getDouble("Y"),
                            pointTag.getDouble("Z")
                    ));
                    if (pointTag.contains("Seg")) {
                        metas.add(new WaypointMeta(pointTag.getByte("Seg"), pointTag.getByte("Org")));
                    } else {
                        allHaveMeta = false;
                    }
                }
            }
            String authorName = routeTag.getString("AuthorName");
            String authorUuid = routeTag.getString("AuthorUuid");
            long createdAt = routeTag.getLong("CreatedAt");
            double routeLength = routeTag.contains("RouteLength") ? routeTag.getDouble("RouteLength") : 0.0D;
            String startDock = routeTag.getString("StartDock");
            String endDock = routeTag.getString("EndDock");
            List<WaypointMeta> finalMetas = allHaveMeta && metas.size() == waypoints.size() ? metas : List.of();
            routes.add(new RouteDefinition(routeName, waypoints, authorName, authorUuid, createdAt, routeLength, startDock, endDock, finalMetas));
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
            // metas 只在与 waypoints 等长时逐点写 Seg/Org(水路自动航线);其它航线 metas 空,不写标记。
            List<WaypointMeta> metas = route.waypointMetas();
            boolean hasMetas = metas != null && metas.size() == route.waypoints().size();
            ListTag pointsTag = new ListTag();
            for (int i = 0; i < route.waypoints().size(); i++) {
                Vec3 waypoint = route.waypoints().get(i);
                CompoundTag pointTag = new CompoundTag();
                pointTag.putDouble("X", waypoint.x);
                pointTag.putDouble("Y", waypoint.y);
                pointTag.putDouble("Z", waypoint.z);
                if (hasMetas) {
                    WaypointMeta m = metas.get(i);
                    pointTag.putByte("Seg", m.segment());
                    pointTag.putByte("Org", m.origin());
                }
                pointsTag.add(pointTag);
            }
            routeTag.put("Waypoints", pointsTag);
            routesTag.add(routeTag);
        }
        root.put(key, routesTag);
    }
}
