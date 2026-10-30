package com.monpai.sailboatmod.map.route;

import java.util.List;

public record MapRouteSnapshot(List<MapRouteEntry> routes, boolean truncated) {
    public static final int MAX_ROUTES = 128;
    public static final int MAX_POINTS = 4096;
    public static final int MAX_PARTS = 256;
    public static final int MAX_STRING_LENGTH = 256;

    public MapRouteSnapshot {
        if (routes.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("Too many display routes");
        }
        routes = List.copyOf(routes);
        int parts = 0;
        int points = 0;
        for (MapRouteEntry route : routes) {
            if (route.paths().size() > MAX_PARTS - parts) {
                throw new IllegalArgumentException("Too many display route fragments");
            }
            parts += route.paths().size();
            for (List<MapRoutePoint> path : route.paths()) {
                if (path.size() > MAX_POINTS - points) {
                    throw new IllegalArgumentException("Too many display route points");
                }
                points += path.size();
            }
        }
    }
}
