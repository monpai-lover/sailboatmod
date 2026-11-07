package com.monpai.sailboatmod.transport.air;

import java.util.List;

/** Offroad 1.3.2 suspension geometry; called only at its actual force application site. */
public final class UnloadedWheelContactPolicy {
    public record RayHit(boolean hit, boolean staticWorld, boolean upward) { }
    private UnloadedWheelContactPolicy() { }
    public static boolean staticFloorHits(List<RayHit> rays) {
        if (rays == null || rays.size() != 3) return false;
        boolean floor = false;
        for (RayHit ray : rays) {
            if (ray == null) return false;
            if (!ray.hit()) continue;
            if (!ray.staticWorld() || !ray.upward()) return false;
            floor = true;
        }
        return floor;
    }
    public static boolean ignore(double extension, double radius, double verticalImpulse,
                                 double upright, double dt) {
        if (!Double.isFinite(extension) || !Double.isFinite(radius) || radius <= 0
                || !Double.isFinite(verticalImpulse) || !Double.isFinite(upright) || upright < .5 || upright > 1.000001
                || !Double.isFinite(dt) || dt <= 0 || dt > .1 || extension == .65)
            return false;
        // Beyond this bound native code returns before applying anything. Its .65
        // sentinel retains old force fields and must never be treated as fresh force.
        if (extension <= 0 || extension > .65 + radius + .25) return false;
        double springLength = Math.max(0, Math.min(.65, extension + .10833333333333334 - radius));
        return springLength >= .65 && verticalImpulse <= 0;
    }
}
