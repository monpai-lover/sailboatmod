package com.monpai.sailboatmod.map.route;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

public final class MapRouteGeometry {
    private static final int LEFT = 1;
    private static final int RIGHT = 2;
    private static final int BELOW = 4;
    private static final int ABOVE = 8;
    private static final int MAX_SIMPLIFICATION_DISTANCE_TESTS = 1_000_000;

    private MapRouteGeometry() {}

    /** Clips one connected source path into separate visible fragments. */
    public static List<List<MapRoutePoint>> clip(List<MapRoutePoint> path, MapRouteViewport view) {
        Objects.requireNonNull(view, "view");
        path = List.copyOf(path);
        ArrayList<List<MapRoutePoint>> fragments = new ArrayList<>();
        ArrayList<MapRoutePoint> current = new ArrayList<>();
        for (int index = 1; index < path.size(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Route clipping was superseded");
            }
            MapRoutePoint start = path.get(index - 1);
            MapRoutePoint end = path.get(index);
            MapRoutePoint[] segment = clipSegment(start, end, view);
            if (segment == null) {
                finishFragment(current, fragments);
                continue;
            }
            if (samePosition(segment[0], segment[1])) {
                // A corner touch has no visible line length. A duplicate interior vertex
                // does not interrupt the connected path around it.
                if (outcode(start, view) != 0 || outcode(end, view) != 0) {
                    finishFragment(current, fragments);
                }
                continue;
            }
            if (!current.isEmpty() && (outcode(start, view) != 0
                    || !samePosition(current.getLast(), segment[0]))) {
                finishFragment(current, fragments);
            }
            if (current.isEmpty()) {
                current.add(segment[0]);
            }
            current.add(segment[1]);
        }
        finishFragment(current, fragments);
        return List.copyOf(fragments);
    }

    /**
     * Simplifies a single connected fragment with a maximum world-space deviation.
     * Call independently for every clipped fragment; the supplied tolerance is
     * normally the permitted screen error divided by the viewport pixels per block.
     * If work is cancelled or the bounded simplification budget is exhausted,
     * the immutable original fragment is returned with zero display error.
     */
    public static List<MapRoutePoint> simplify(List<MapRoutePoint> path, double toleranceBlocks) {
        if (!Double.isFinite(toleranceBlocks) || toleranceBlocks < 0) {
            throw new IllegalArgumentException("Route simplification tolerance must be finite and non-negative");
        }
        path = List.copyOf(path);
        if (path.size() <= 2 || toleranceBlocks == 0) {
            return path;
        }
        boolean[] keep = new boolean[path.size()];
        keep[0] = true;
        keep[path.size() - 1] = true;
        ArrayDeque<int[]> pending = new ArrayDeque<>();
        pending.push(new int[]{0, path.size() - 1});
        int distanceTests = 0;
        while (!pending.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) {
                return path;
            }
            int[] range = pending.pop();
            double largestDistance = toleranceBlocks;
            int furthest = -1;
            for (int index = range[0] + 1; index < range[1]; index++) {
                if (++distanceTests > MAX_SIMPLIFICATION_DISTANCE_TESTS || Thread.currentThread().isInterrupted()) {
                    return path;
                }
                double distance = segmentDistance(path.get(index), path.get(range[0]), path.get(range[1]));
                if (distance > largestDistance) {
                    largestDistance = distance;
                    furthest = index;
                }
            }
            if (furthest >= 0) {
                keep[furthest] = true;
                pending.push(new int[]{range[0], furthest});
                pending.push(new int[]{furthest, range[1]});
            }
        }
        ArrayList<MapRoutePoint> simplified = new ArrayList<>();
        for (int index = 0; index < path.size(); index++) {
            if (keep[index]) {
                simplified.add(path.get(index));
            }
        }
        return List.copyOf(simplified);
    }

    private static void finishFragment(ArrayList<MapRoutePoint> current, List<List<MapRoutePoint>> fragments) {
        if (current.size() >= 2) {
            fragments.add(List.copyOf(current));
        }
        current.clear();
    }

    private static MapRoutePoint[] clipSegment(MapRoutePoint start, MapRoutePoint end, MapRouteViewport view) {
        int startCode = outcode(start, view);
        int endCode = outcode(end, view);
        while (true) {
            if ((startCode | endCode) == 0) {
                return new MapRoutePoint[]{start, end};
            }
            if ((startCode & endCode) != 0) {
                return null;
            }
            int code = startCode != 0 ? startCode : endCode;
            MapRoutePoint intersection;
            if ((code & ABOVE) != 0) {
                double fraction = fractionAt(view.maxZ(), start.z(), end.z());
                intersection = new MapRoutePoint(interpolate(start.x(), end.x(), fraction), view.maxZ());
            } else if ((code & BELOW) != 0) {
                double fraction = fractionAt(view.minZ(), start.z(), end.z());
                intersection = new MapRoutePoint(interpolate(start.x(), end.x(), fraction), view.minZ());
            } else if ((code & RIGHT) != 0) {
                double fraction = fractionAt(view.maxX(), start.x(), end.x());
                intersection = new MapRoutePoint(view.maxX(), interpolate(start.z(), end.z(), fraction));
            } else {
                double fraction = fractionAt(view.minX(), start.x(), end.x());
                intersection = new MapRoutePoint(view.minX(), interpolate(start.z(), end.z(), fraction));
            }
            if (code == startCode) {
                start = intersection;
                startCode = outcode(start, view);
            } else {
                end = intersection;
                endCode = outcode(end, view);
            }
        }
    }

    private static int outcode(MapRoutePoint point, MapRouteViewport view) {
        int code = point.x() < view.minX() ? LEFT : point.x() > view.maxX() ? RIGHT : 0;
        return code | (point.z() < view.minZ() ? BELOW : point.z() > view.maxZ() ? ABOVE : 0);
    }

    private static double fractionAt(double boundary, double start, double end) {
        double numerator = boundary - start;
        double denominator = end - start;
        if (!Double.isFinite(numerator) || !Double.isFinite(denominator)) {
            numerator = boundary * 0.5 - start * 0.5;
            denominator = end * 0.5 - start * 0.5;
        }
        return Math.clamp(numerator / denominator, 0, 1);
    }

    private static double interpolate(double start, double end, double fraction) {
        if (fraction == 0) {
            return start;
        }
        if (fraction == 1) {
            return end;
        }
        double difference = end - start;
        return Double.isFinite(difference) ? start + fraction * difference
                : start * (1 - fraction) + end * fraction;
    }

    private static boolean samePosition(MapRoutePoint first, MapRoutePoint second) {
        return first.x() == second.x() && first.z() == second.z();
    }

    private static double segmentDistance(MapRoutePoint point, MapRoutePoint start, MapRoutePoint end) {
        double dx = end.x() - start.x();
        double dz = end.z() - start.z();
        double px = point.x() - start.x();
        double pz = point.z() - start.z();
        double scale = Math.max(Math.max(Math.abs(dx), Math.abs(dz)), Math.max(Math.abs(px), Math.abs(pz)));
        if (scale == 0) {
            return 0;
        }
        if (!Double.isFinite(scale)) {
            // Subtracting opposite finite extremes can overflow. Normalize the
            // original coordinates first in that uncommon case.
            scale = Math.max(Math.max(Math.abs(point.x()), Math.abs(point.z())),
                    Math.max(Math.max(Math.abs(start.x()), Math.abs(start.z())),
                            Math.max(Math.abs(end.x()), Math.abs(end.z()))));
            dx = end.x() / scale - start.x() / scale;
            dz = end.z() / scale - start.z() / scale;
            px = point.x() / scale - start.x() / scale;
            pz = point.z() / scale - start.z() / scale;
        } else {
            dx /= scale;
            dz /= scale;
            px /= scale;
            pz /= scale;
        }
        double lengthSquared = dx * dx + dz * dz;
        double fraction = lengthSquared == 0 ? 0 : Math.clamp((px * dx + pz * dz) / lengthSquared, 0, 1);
        return Math.hypot(px - fraction * dx, pz - fraction * dz) * scale;
    }
}
