package com.monpai.sailboatmod.map.route;

/** A display coordinate; doubles avoid the coordinate limits of packed block positions. */
public record MapRoutePoint(double x, double z) {
    public MapRoutePoint {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Route coordinates must be finite");
        }
    }
}
