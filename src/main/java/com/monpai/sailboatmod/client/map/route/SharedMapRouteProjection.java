package com.monpai.sailboatmod.client.map.route;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapLayout;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapView;
import com.monpai.sailboatmod.map.route.MapRouteEntry;
import com.monpai.sailboatmod.map.route.MapRouteGeometry;
import com.monpai.sailboatmod.map.route.MapRoutePoint;
import com.monpai.sailboatmod.map.route.MapRouteViewport;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Uses the terrain view's projection and clips every part independently before converting to pixels. */
public final class SharedMapRouteProjection {
    private SharedMapRouteProjection() { }

    public static MapRouteViewport viewport(RoadPlannerMapView view, RoadPlannerMapLayout.Rect rect) {
        double halfWidth = rect.width() / (2.0 * view.scale());
        double halfHeight = rect.height() / (2.0 * view.scale());
        return new MapRouteViewport(view.centerX() - halfWidth, view.centerZ() - halfHeight,
                view.centerX() + halfWidth, view.centerZ() + halfHeight, view.scale());
    }

    public static List<Segment> segments(MapRouteEntry route, RoadPlannerMapView view, RoadPlannerMapLayout.Rect rect) {
        MapRouteViewport viewport = viewport(view, rect);
        List<Segment> segments = new ArrayList<>();
        for (List<MapRoutePoint> part : route.paths()) {
            for (List<MapRoutePoint> clipped : MapRouteGeometry.clip(part, viewport)) {
                for (int i = 1; i < clipped.size(); i++) {
                    MapRoutePoint a = clipped.get(i - 1);
                    MapRoutePoint b = clipped.get(i);
                    segments.add(new Segment(view.worldToScreenX(a.x(), rect), view.worldToScreenZ(a.z(), rect),
                            view.worldToScreenX(b.x(), rect), view.worldToScreenZ(b.z(), rect)));
                }
            }
        }
        return List.copyOf(segments);
    }

    public static Optional<MapRouteEntry> hit(List<MapRouteEntry> routes, RoadPlannerMapView view,
                                              RoadPlannerMapLayout.Rect rect, double mouseX, double mouseY,
                                              double tolerancePixels) {
        if (mouseX < rect.x() || mouseX >= rect.right() || mouseY < rect.y() || mouseY >= rect.bottom()) return Optional.empty();
        double bestDistance = tolerancePixels * tolerancePixels;
        MapRouteEntry best = null;
        for (MapRouteEntry route : routes) {
            for (Segment segment : segments(route, view, rect)) {
                double distance = distanceSquared(segment, mouseX, mouseY);
                if (distance <= bestDistance && (distance < bestDistance || best == null || route.id().compareTo(best.id()) < 0)) {
                    bestDistance = distance;
                    best = route;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static double distanceSquared(Segment segment, double x, double y) {
        double dx = (double) segment.x2 - segment.x1;
        double dy = (double) segment.y2 - segment.y1;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0 : Math.clamp(((x - segment.x1) * dx + (y - segment.y1) * dy) / lengthSquared, 0.0, 1.0);
        double offsetX = x - (segment.x1 + dx * t);
        double offsetY = y - (segment.y1 + dy * t);
        return offsetX * offsetX + offsetY * offsetY;
    }

    public record Segment(int x1, int y1, int x2, int y2) { }
}
