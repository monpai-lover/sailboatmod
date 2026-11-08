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
            double braking = speedAfter(brakingSpeeds[i + 1], following);
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

    public int pointCount() { return distances.length; }

    /** Reserve enough distance to slow down before the next bend or terminal. */
    public double speedLimit(double distance) {
        double d = Math.clamp(distance, 0, length());
        int next = nextIndex(d);
        double untilNext = distances[next] - d;
        double after = brakingSpeeds[next];
        double from = brakingSpeeds[next - 1];
        return Math.min(speedAfter(after, untilNext), speedAfter(from, d - distances[next - 1]));
    }

    public Timing timing(double cruiseSpeed) {
        if (!Double.isFinite(cruiseSpeed) || cruiseSpeed <= 0)
            throw new IllegalArgumentException("Invalid surface cruise speed");
        return new Timing(cruiseSpeed);
    }

    /** Nominal travel time over the compiled path, using the route's braking and corner limits. */
    public final class Timing {
        private final double cruiseSpeed;
        private final double[] vertexSpeeds = new double[distances.length];
        private final double[] secondsToEnd = new double[distances.length];

        private Timing(double cruiseSpeed) {
            this.cruiseSpeed = cruiseSpeed;
            for (int i = 1; i < distances.length; i++) {
                double span = distances[i] - distances[i - 1];
                // Use the same acceleration bound in both directions for a conservative nominal profile.
                vertexSpeeds[i] = Math.min(Math.min(cruiseSpeed, brakingSpeeds[i]), speedAfter(vertexSpeeds[i - 1], span));
            }
            for (int i = distances.length - 2; i >= 0; i--)
                secondsToEnd[i] = secondsToEnd[i + 1] + remainingSegmentSeconds(i + 1, 0);
        }

        public double cruiseSpeed() { return cruiseSpeed; }

        public long remainingTicks(double distance) {
            if (!Double.isFinite(distance) || distance < 0 || distance >= length()) return 0;
            int next = nextIndex(distance);
            double seconds = remainingSegmentSeconds(next, distance - distances[next - 1]) + secondsToEnd[next];
            return (long) Math.ceil(seconds * 20);
        }

        private double remainingSegmentSeconds(int next, double offset) {
            double span = distances[next] - distances[next - 1];
            double entry = vertexSpeeds[next - 1], exit = vertexSpeeds[next];
            double twiceAcceleration = 2 * BRAKING_ACCELERATION;
            double entrySquared = entry * entry, exitSquared = exit * exit, cruiseSquared = cruiseSpeed * cruiseSpeed;
            double crossover = Math.clamp((span + (exitSquared - entrySquared) / twiceAcceleration) / 2, 0, span);
            double acceleratingUntil = Math.min(crossover, (cruiseSquared - entrySquared) / twiceAcceleration);
            double brakingFrom = Math.max(crossover, span - (cruiseSquared - exitSquared) / twiceAcceleration);

            double seconds = 0;
            if (offset < acceleratingUntil) {
                seconds += 2 * (acceleratingUntil - offset) / (speedAfter(entry, offset) + speedAfter(entry, acceleratingUntil));
            }
            seconds += Math.max(0, brakingFrom - Math.max(offset, acceleratingUntil)) / cruiseSpeed;
            double brakingDistance = span - Math.max(offset, brakingFrom);
            if (brakingDistance > 0) seconds += 2 * brakingDistance / (speedAfter(exit, brakingDistance) + exit);
            return seconds;
        }
    }

    private static double speedAfter(double initialSpeed, double distance) {
        return Math.sqrt(initialSpeed * initialSpeed + 2 * BRAKING_ACCELERATION * distance);
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
