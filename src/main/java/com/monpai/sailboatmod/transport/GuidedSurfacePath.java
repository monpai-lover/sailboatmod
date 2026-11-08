package com.monpai.sailboatmod.transport;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** A bounded corner fillet over the published centerline. No new route is selected here. */
public final class GuidedSurfacePath {
    private static final double EPSILON = 1e-6;
    private static final int MAX_POINTS = 32_768;
    private static final int CORNER_SUBDIVISIONS = 12;
    private static final double CURVE_HEADING_RATE = Math.toRadians(4) * 20;
    private static final double BRAKING_ACCELERATION = 2.5;

    public record Sample(Vec3 position, Vec3 tangent, int routeIndex) {}
    private record Point(Vec3 position, double index) {}

    private final List<Point> points;
    private final double[] distances;
    private final double[] brakingSpeeds;

    private GuidedSurfacePath(List<Point> points) {
        this.points = points;
        distances = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            double step = points.get(i).position.subtract(points.get(i - 1).position).horizontalDistance();
            distances[i] = distances[i - 1] + step;
        }
        brakingSpeeds = new double[points.size()];
        for (int i = points.size() - 2; i >= 0; i--) {
            double following = distances[i + 1] - distances[i];
            double braking = Math.sqrt(brakingSpeeds[i + 1] * brakingSpeeds[i + 1]
                    + 2 * BRAKING_ACCELERATION * following);
            double curveLimit = Double.POSITIVE_INFINITY;
            if (i > 0) {
                Vec3 from = points.get(i).position.subtract(points.get(i - 1).position);
                Vec3 to = points.get(i + 1).position.subtract(points.get(i).position);
                double angle = Math.abs(Math.atan2(from.x * to.z - from.z * to.x,
                        from.x * to.x + from.z * to.z));
                if (angle > EPSILON) {
                    double preceding = distances[i] - distances[i - 1];
                    curveLimit = CURVE_HEADING_RATE * 0.75 * Math.min(preceding, following) / angle;
                }
            }
            brakingSpeeds[i] = Math.min(braking, curveLimit);
        }
    }

    public static GuidedSurfacePath compile(List<Vec3> authored, double cornerRadius) {
        if (authored.size() < 2 || authored.size() > MAX_POINTS
                || !Double.isFinite(cornerRadius) || cornerRadius < 0)
            throw new IllegalArgumentException("Invalid surface route");
        List<Point> centerline = new ArrayList<>();
        for (int i = 0; i < authored.size(); i++) {
            Vec3 position = authored.get(i);
            if (!finite(position)) throw new IllegalArgumentException("Invalid surface waypoint");
            var point = new Point(position, i);
            if (centerline.isEmpty()) {
                centerline.add(point);
                continue;
            }
            Vec3 previous = centerline.getLast().position;
            Vec3 step = position.subtract(previous);
            if (step.horizontalDistanceSqr() < EPSILON * EPSILON) {
                if (Math.abs(step.y) > EPSILON) throw new IllegalArgumentException("Vertical surface route");
                centerline.set(centerline.size() - 1, point);
                continue;
            }
            if (centerline.size() > 1) {
                Vec3 approach = previous.subtract(centerline.get(centerline.size() - 2).position);
                if (approach.normalize().dot(step.normalize()) >= 1 - 1e-10) {
                    centerline.set(centerline.size() - 1, point);
                    continue;
                }
            }
            centerline.add(point);
        }
        if (centerline.size() < 2) throw new IllegalArgumentException("Empty surface route");
        var rounded = new ArrayList<Point>();
        rounded.add(centerline.getFirst());
        for (int i = 1; i + 1 < centerline.size(); i++) {
            Point a = centerline.get(i - 1), b = centerline.get(i), c = centerline.get(i + 1);
            Vec3 in = b.position.subtract(a.position), out = c.position.subtract(b.position);
            double trim = Math.min(cornerRadius, 0.44 * Math.min(in.horizontalDistance(), out.horizontalDistance()));
            if (trim < EPSILON || in.normalize().dot(out.normalize()) < -0.98) {
                rounded.add(b);
                continue;
            }
            Point before = interpolate(b, a, trim / in.horizontalDistance());
            Point after = interpolate(b, c, trim / out.horizontalDistance());
            for (int j = 0; j <= CORNER_SUBDIVISIONS; j++) {
                double t = j / (double) CORNER_SUBDIVISIONS;
                rounded.add(interpolate(interpolate(before, b, t), interpolate(b, after, t), t));
            }
        }
        rounded.add(centerline.getLast());
        return new GuidedSurfacePath(List.copyOf(rounded));
    }

    public double length() {
        return distances[distances.length - 1];
    }

    /** Reserve enough distance to slow down before the next bend or terminal. */
    public double speedLimit(double distance) {
        double d = Math.clamp(distance, 0, length());
        int next = nextIndex(d);
        double untilNext = distances[next] - d;
        double after = brakingSpeeds[next];
        double from = brakingSpeeds[next - 1];
        return Math.min(Math.sqrt(after * after + 2 * BRAKING_ACCELERATION * untilNext),
                Math.sqrt(from * from + 2 * BRAKING_ACCELERATION * (d - distances[next - 1])));
    }

    public Sample sample(double distance) {
        double d = Math.clamp(distance, 0, length());
        Point at = pointAt(d);
        Vec3 tangent = pointAt(Math.min(length(), d + 0.25)).position
                .subtract(pointAt(Math.max(0, d - 0.25)).position);
        if (tangent.horizontalDistanceSqr() < EPSILON * EPSILON) {
            int next = nextIndex(d);
            tangent = points.get(next).position.subtract(points.get(next - 1).position);
        }
        return new Sample(at.position, tangent.normalize(), (int) Math.ceil(at.index));
    }

    /** Limit reacquisition to the local segment neighborhood, including where roads cross. */
    public double nearestDistance(Vec3 position, double minimum, double maximum) {
        double lower = Math.clamp(minimum, 0, length());
        double upper = Math.clamp(maximum, lower, length());
        double best = lower;
        double bestError = Double.POSITIVE_INFINITY;
        for (int i = nextIndex(lower); i <= nextIndex(upper); i++) {
            Point from = points.get(i - 1), to = points.get(i);
            Vec3 direction = to.position.subtract(from.position);
            Vec3 delta = position.subtract(from.position);
            double step = distances[i] - distances[i - 1];
            double projected = (delta.x * direction.x + delta.z * direction.z) / (step * step);
            double t = Math.clamp(projected, Math.max(0, (lower - distances[i - 1]) / step),
                    Math.min(1, (upper - distances[i - 1]) / step));
            double error = position.subtract(from.position.lerp(to.position, t)).horizontalDistanceSqr();
            if (error < bestError) {
                bestError = error;
                best = distances[i - 1] + t * step;
            }
        }
        return best;
    }

    /** Approximate distance on the compiled curve corresponding to a published waypoint index. */
    public double distanceAtRouteIndex(int index) {
        int lo = 1, hi = points.size() - 1;
        while (lo < hi) {
            int middle = (lo + hi) >>> 1;
            if (points.get(middle).index < index) lo = middle + 1;
            else hi = middle;
        }
        double from = points.get(lo - 1).index, to = points.get(lo).index;
        if (to <= from) return distances[lo];
        double t = Math.clamp((index - from) / (to - from), 0, 1);
        return distances[lo - 1] + t * (distances[lo] - distances[lo - 1]);
    }

    private Point pointAt(double distance) {
        int next = nextIndex(distance);
        double t = (distance - distances[next - 1]) / (distances[next] - distances[next - 1]);
        return interpolate(points.get(next - 1), points.get(next), t);
    }

    private int nextIndex(double distance) {
        int index = Arrays.binarySearch(distances, distance);
        return Math.clamp(index < 0 ? -index - 1 : index, 1, distances.length - 1);
    }

    private static Point interpolate(Point a, Point b, double t) {
        return new Point(a.position.lerp(b.position, t), a.index + (b.index - a.index) * t);
    }

    private static boolean finite(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
