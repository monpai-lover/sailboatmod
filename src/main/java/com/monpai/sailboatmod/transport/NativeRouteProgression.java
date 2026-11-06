package com.monpai.sailboatmod.transport;

import com.monpai.sailboatmod.transport.control.SweptTerrainProbe;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Selects an onward waypoint without changing the physical vehicle or its route. */
public final class NativeRouteProgression {
    public record Selection(int index, Vec3 target, boolean terrainReady) { }

    public static final class TerrainPaths {
        private final SweptTerrainProbe onward = new SweptTerrainProbe();
        private final SweptTerrainProbe terminal = new SweptTerrainProbe();

        public SweptTerrainProbe.Result scan(boolean terminalCandidate, AABB area, int budget,
                                             Predicate<BlockPos> loaded, Predicate<BlockPos> collision) {
            // Alternative swept bounds must not reset one another's incremental scan cursor.
            return (terminalCandidate ? terminal : onward).scan(area, budget, loaded, collision);
        }
    }

    private NativeRouteProgression() { }

    public static Selection selectLoaded(List<Vec3> route, int index, Vec3 position, Vec3 velocity,
                                         double radius, Predicate<Vec3> clearPath,
                                         Predicate<Vec3> insideTerminal, Predicate<Vec3> loadPath) {
        if (loadPath == null) return null;
        Selection selected = select(route, index, position, velocity, radius, clearPath, insideTerminal);
        if (selected == null) return null;
        // Alternative terrain probes are read-only. Only the selected path may replace chunk leases.
        boolean loaded = loadPath.test(selected.target());
        boolean ready = selected.terrainReady() && loaded;
        return new Selection(ready ? selected.index() : index, selected.target(), ready);
    }

    public static boolean canFinishInsideTerminal(List<Vec3> route, int index, Vec3 position,
                                                  double maxTailLength, Predicate<Vec3> insideTerminal) {
        if (route == null || index < 0 || index >= route.size() || !finite(position)
                || !Double.isFinite(maxTailLength) || maxTailLength <= 0 || insideTerminal == null
                || route.stream().anyMatch(point -> !finite(point)) || !insideTerminal.test(position)) return false;
        double length = 0;
        Vec3 previous = position;
        for (int point = index; point < route.size(); point++) {
            Vec3 next = route.get(point);
            if (!insideTerminal.test(next)) return false;
            length += previous.distanceTo(next);
            if (length > maxTailLength) return false;
            previous = next;
        }
        return true;
    }

    public static Selection select(List<Vec3> route, int index, Vec3 position, Vec3 velocity,
                                   double radius, Predicate<Vec3> clearPath, Predicate<Vec3> insideTerminal) {
        if (canFinishInsideTerminal(route, index, position, radius * 2, insideTerminal)
                && index < route.size() - 1) {
            Selection terminal = select(route, route.size() - 1, position, velocity, radius, clearPath);
            if (terminal == null || terminal.terrainReady()) return terminal;
            // A short in-zone tail permits parking, but never requires driving through a blocked shortcut.
        }
        return select(route, index, position, velocity, radius, clearPath);
    }

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
        if (corner && onOutgoingRun(route, index, position, radius, outgoing)) return true;
        return crossedPoint(route, index, position, radius);
    }

    private static boolean onOutgoingRun(List<Vec3> route, int index, Vec3 position,
                                         double radius, Vec3 outgoing) {
        Vec3 along = outgoing.normalize();
        Vec3 origin = route.get(index), offset = position.subtract(origin);
        double progress = offset.x * along.x + offset.z * along.z;
        double corridor = Math.min(radius, 0.75);
        if (progress <= 0 || Math.abs(offset.x * along.z - offset.z * along.x) > corridor) return false;
        // Only the immediately adjacent straight run proves this corner has already been traversed.
        int end = index;
        double length = 0;
        while (end + 1 < route.size()) {
            Vec3 next = route.get(end + 1).subtract(origin);
            double nextProgress = next.x * along.x + next.z * along.z;
            if (nextProgress < length - 1.0e-6
                    || Math.abs(next.x * along.z - next.z * along.x) > 1.0e-6) break;
            length = nextProgress;
            end++;
        }
        if (progress > length) return false;
        // At a crossing/nearby return leg, position alone cannot identify which segment was travelled.
        for (int point = 1; point < route.size(); point++) {
            if (point > index && point <= end) continue;
            if (distanceToSegment(position, route.get(point - 1), route.get(point)) <= corridor) return false;
        }
        return true;
    }

    private static double distanceToSegment(Vec3 position, Vec3 start, Vec3 end) {
        Vec3 segment = end.subtract(start).multiply(1, 0, 1);
        double lengthSquared = segment.lengthSqr();
        if (lengthSquared <= 1.0e-8) return horizontalDistance(position, start);
        Vec3 offset = position.subtract(start);
        double fraction = Math.max(0, Math.min(1, (offset.x * segment.x + offset.z * segment.z) / lengthSquared));
        return horizontalDistance(position, start.add(segment.scale(fraction)));
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
