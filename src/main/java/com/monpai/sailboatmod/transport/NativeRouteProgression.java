package com.monpai.sailboatmod.transport;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.phys.Vec3;

/** Selects an onward waypoint without changing the physical vehicle or its route. */
public final class NativeRouteProgression {
    public record Selection(int index, Vec3 target, boolean terrainReady) { }
    private NativeRouteProgression() { }

    public static Selection select(List<Vec3> route, int index, Vec3 position, Vec3 velocity,
                                   double radius, Predicate<Vec3> clearPath) {
        if (route == null || index < 0 || index >= route.size() || !finite(position) || !finite(velocity)
                || !Double.isFinite(radius) || radius < 0 || clearPath == null
                || route.stream().anyMatch(point -> !finite(point))) return null;
        int next = index;
        while (next < route.size() - 1 && passedOrClose(route, next, position, radius)
                && followsRouteCorridor(route, index, next + 1, position, radius)) next++;
        Vec3 target = route.get(next);
        boolean ready = clearPath.test(target);
        return new Selection(ready ? next : index, target, ready);
    }

    private static boolean passedOrClose(List<Vec3> route, int index, Vec3 position, double radius) {
        Vec3 point = route.get(index);
        Vec3 outgoing = routeDirection(route, index, 1);
        Vec3 incoming = incomingDirection(route, index);
        boolean corner = incoming.lengthSqr() > 1.0e-8 && outgoing.lengthSqr() > 1.0e-8
                && incoming.normalize().dot(outgoing.normalize()) < Math.cos(Math.toRadians(30));
        // Hull-sized arrival circles must not erase a sharp corner or jump to a nearby return leg.
        double passingRadius = corner ? Math.min(radius, 0.75) : radius;
        if (horizontalDistance(position, point) <= passingRadius) return true;
        return crossedPoint(route, index, position, radius);
    }

    private static boolean crossedPoint(List<Vec3> route, int index, Vec3 position, double radius) {
        Vec3 incoming = incomingDirection(route, index);
        if (incoming.lengthSqr() <= 1.0e-8) return false;
        Vec3 along = incoming.normalize();
        Vec3 offset = position.subtract(route.get(index));
        double progress = offset.x * along.x + offset.z * along.z;
        double crossTrack = Math.abs(offset.x * along.z - offset.z * along.x);
        // Recover a point crossed between controller samples, but only in its incoming corridor.
        return progress >= 0 && crossTrack <= Math.min(radius, 1.5);
    }

    private static Vec3 incomingDirection(List<Vec3> route, int index) {
        Vec3 incoming = routeDirection(route, index, -1).scale(-1);
        return incoming.lengthSqr() <= 1.0e-8 ? routeDirection(route, index, 1) : incoming;
    }

    private static Vec3 routeDirection(List<Vec3> route, int index, int step) {
        for (int other = index + step; other >= 0 && other < route.size(); other += step) {
            Vec3 direction = route.get(other).subtract(route.get(index)).multiply(1, 0, 1);
            if (direction.lengthSqr() > 1.0e-8) return direction;
        }
        return Vec3.ZERO;
    }

    private static boolean followsRouteCorridor(List<Vec3> route, int first, int targetIndex, Vec3 position, double radius) {
        Vec3 direction = route.get(targetIndex).subtract(position).multiply(1, 0, 1);
        double distance = direction.length();
        if (distance <= 1.0e-4) return true;
        Vec3 along = direction.scale(1 / distance);
        for (int skipped = first; skipped < targetIndex; skipped++) {
            if (crossedPoint(route, skipped, position, radius)) continue;
            Vec3 offset = route.get(skipped).subtract(position);
            double progress = offset.x * along.x + offset.z * along.z;
            // Points already behind the vehicle must not pull it backward after an overshoot.
            if (progress <= 0 || progress >= distance) continue;
            double crossTrack = Math.abs(offset.x * along.z - offset.z * along.x);
            if (crossTrack > 0.75) return false;
        }
        return true;
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) { return Math.hypot(a.x - b.x, a.z - b.z); }
    private static boolean finite(Vec3 v) {
        return v != null && Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }
}
