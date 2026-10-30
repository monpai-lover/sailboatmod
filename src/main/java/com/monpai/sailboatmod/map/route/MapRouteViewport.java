package com.monpai.sailboatmod.map.route;

public record MapRouteViewport(double minX, double minZ, double maxX, double maxZ, double pixelsPerBlock) {
    public MapRouteViewport {
        if (!Double.isFinite(minX) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxZ)
                || minX >= maxX || minZ >= maxZ) {
            throw new IllegalArgumentException("Route viewport must have finite, ordered bounds");
        }
        if (!Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0) {
            throw new IllegalArgumentException("Route viewport scale must be finite and positive");
        }
    }
}
