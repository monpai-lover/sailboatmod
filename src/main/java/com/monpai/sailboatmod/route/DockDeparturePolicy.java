package com.monpai.sailboatmod.route;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;

/** Admission to an existing voyage is NOT a test for a straight unobstructed exit from a berth.
 * The moving controller must validate/avoid every actual local movement afterwards.
 */
public final class DockDeparturePolicy {
    private DockDeparturePolicy() { }
    public static boolean inside(AABB zone,AABB hull) { return zone.inflate(1e-4).intersects(hull); }
    public static RouteDefinition prepare(RouteDefinition route,Vec3 position,boolean inAssignedBerth) {
        if(!inAssignedBerth||route==null||route.waypoints().size()<2)return null;
        Vec3 entry=route.waypoints().getFirst();
        if(!Double.isFinite(position.x)||!Double.isFinite(position.y)||!Double.isFinite(position.z))return null;
        // Do not inherit the floating body's fractional Y as the water surface, and do not
        // silently skip an old route corner or move the vessel to the saved start point.
        var points=new ArrayList<Vec3>();points.add(new Vec3(position.x,entry.y,position.z));
        for(Vec3 p:route.waypoints())if(points.getLast().distanceToSqr(p)>1e-12)points.add(p);
        return new RouteDefinition(route.name(),points,route.authorName(),route.authorUuid(),route.createdAtEpochMillis(),
                com.monpai.sailboatmod.route.water.KnownWaterRouteGraph.length(points),route.startDockName(),route.endDockName());
    }
}
