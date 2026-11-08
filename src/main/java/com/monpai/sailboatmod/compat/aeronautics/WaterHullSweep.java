package com.monpai.sailboatmod.compat.aeronautics;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.Optional;

/** Conservative separation test after the guided water scan finds a world-box collision. */
public final class WaterHullSweep {
    private static final double NATIVE_BOUNDS_TOLERANCE = 0.001;
    private static final double MAX_START_DRIFT = 0.02;
    private static final double MAX_RADIUS = 1000;
    // Require a full block of clearance outside the native plot to keep clearance for protruding shapes.
    private static final double CLEARANCE = 1.0;

    private record Span(Vector3d axis, double min, double max) { }

    private final Vec3 origin;
    private final Span[] spans;

    private WaterHullSweep(Vec3 origin, Span[] spans) {
        this.origin = origin;
        this.spans = spans;
    }

    /** Offsets are scaled native plot bounds relative to the controller's local anchor. */
    public static Optional<WaterHullSweep> create(AABB offsets, Vec3 origin, Vec3 interpolationStart,
                                                  Quaterniondc from, Vec3 target, Quaterniondc to,
                                                  AABB nativeWorldBounds) {
        if (!finite(offsets) || !finite(nativeWorldBounds) || !RailAircraftMotionState.finite(origin)
                || !RailAircraftMotionState.finite(interpolationStart) || !RailAircraftMotionState.finite(target)
                || !unit(from) || !unit(to) || origin.distanceToSqr(interpolationStart) > MAX_START_DRIFT * MAX_START_DRIFT
                || interpolationStart.distanceToSqr(target)
                > RailAircraftMotionState.MAX_TARGET_STEP * RailAircraftMotionState.MAX_TARGET_STEP)
            return Optional.empty();

        Quaterniond start = new Quaterniond(from).normalize(), end = new Quaterniond(to).normalize();
        Vector3d[] axes = {start.transform(new Vector3d(1, 0, 0)),
                start.transform(new Vector3d(0, 1, 0)), start.transform(new Vector3d(0, 0, 1))};
        double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        AABB computedBounds = new AABB(origin, origin);
        double radius = 0;
        for (int i = 0; i < 8; i++) {
            var corner = new Vector3d((i & 1) == 0 ? offsets.minX : offsets.maxX,
                    (i & 2) == 0 ? offsets.minY : offsets.maxY, (i & 4) == 0 ? offsets.minZ : offsets.maxZ);
            radius = Math.max(radius, corner.length());
            var first = start.transform(corner, new Vector3d());
            var last = end.transform(corner, new Vector3d());
            Vec3 worldCorner = origin.add(first.x, first.y, first.z);
            AABB point = new AABB(worldCorner, worldCorner);
            computedBounds = i == 0 ? point : computedBounds.minmax(point);
            for (int axis = 0; axis < axes.length; axis++) {
                double a = axes[axis].dot(first), b = axes[axis].dot(last);
                min[axis] = Math.min(min[axis], Math.min(a, b));
                max[axis] = Math.max(max[axis], Math.max(a, b));
            }
        }
        if (!Double.isFinite(radius) || radius > MAX_RADIUS || !agrees(computedBounds, nativeWorldBounds))
            return Optional.empty();

        // A shortest-arc point stays within its endpoint chord plus its sagitta. Bound translation
        // independently, so even a differing rotation/translation interpolation fraction is covered.
        Quaterniond relative = end.mul(start.conjugate(new Quaterniond())).normalize();
        double sinHalfAngleSquared = relative.x * relative.x + relative.y * relative.y + relative.z * relative.z;
        double pad = radius * sinHalfAngleSquared / (1.0 + Math.abs(relative.w)) + CLEARANCE;
        // The native body readback and the motion lease can differ by a few milliblocks. Include
        // both positions as well as the target, while validating the world box at the readback.
        Vec3 startDelta = interpolationStart.subtract(origin), targetDelta = target.subtract(origin);
        Span[] spans = new Span[axes.length];
        for (int i = 0; i < axes.length; i++) {
            double fromTravel = axes[i].dot(startDelta.x, startDelta.y, startDelta.z);
            double toTravel = axes[i].dot(targetDelta.x, targetDelta.y, targetDelta.z);
            spans[i] = new Span(axes[i], min[i] + Math.min(0, Math.min(fromTravel, toTravel)) - pad,
                    max[i] + Math.max(0, Math.max(fromTravel, toTravel)) + pad);
        }
        return Optional.of(new WaterHullSweep(origin, spans));
    }

    public boolean definitelyDisjoint(AABB shape) {
        if (!finite(shape)) return false;
        Vec3 center = shape.getCenter().subtract(origin);
        for (Span span : spans) {
            Vector3d axis = span.axis();
            double projected = axis.dot(center.x, center.y, center.z);
            double halfExtent = (Math.abs(axis.x) * shape.getXsize()
                    + Math.abs(axis.y) * shape.getYsize() + Math.abs(axis.z) * shape.getZsize()) / 2;
            if (projected + halfExtent < span.min() || projected - halfExtent > span.max()) return true;
        }
        return false;
    }

    private static boolean agrees(AABB a, AABB b) {
        return Math.abs(a.minX - b.minX) <= NATIVE_BOUNDS_TOLERANCE
                && Math.abs(a.minY - b.minY) <= NATIVE_BOUNDS_TOLERANCE
                && Math.abs(a.minZ - b.minZ) <= NATIVE_BOUNDS_TOLERANCE
                && Math.abs(a.maxX - b.maxX) <= NATIVE_BOUNDS_TOLERANCE
                && Math.abs(a.maxY - b.maxY) <= NATIVE_BOUNDS_TOLERANCE
                && Math.abs(a.maxZ - b.maxZ) <= NATIVE_BOUNDS_TOLERANCE;
    }

    private static boolean finite(AABB box) {
        return box != null && RailAircraftMotionState.finite(new Vec3(box.minX, box.minY, box.minZ))
                && RailAircraftMotionState.finite(new Vec3(box.maxX, box.maxY, box.maxZ))
                && box.getXsize() > 0 && box.getYsize() > 0 && box.getZsize() > 0;
    }

    private static boolean unit(Quaterniondc q) {
        return q != null && Math.abs(q.lengthSquared() - 1) <= 1e-5;
    }
}
