package com.monpai.sailboatmod.roadplanner.weaver.geometry;

import net.minecraft.world.phys.Vec3;

public class WeaverLine {
    private final Vec3 start;
    private final Vec3 end;

    public WeaverLine(Vec3 start, Vec3 end) {
        this.start = start;
        this.end = end;
    }

    public Frame getFrame(Vec3 testPoint) {
        Vec3 direction = end.subtract(start);
        double length = direction.length();

        if (length == 0.0D) {
            return new Frame(start, Vec3.ZERO, 0.0D, Vec3.ZERO, new Vec3(0, 1, 0), Vec3.ZERO);
        }

        Vec3 tangent = direction.normalize();
        double distanceAlongLine = testPoint.subtract(start).dot(tangent);
        double clampedDistance = Math.max(0.0D, Math.min(length, distanceAlongLine));
        Vec3 closestPoint = start.add(tangent.scale(clampedDistance));
        Vec3 horizontalTangent = new Vec3(tangent.x, 0.0D, tangent.z);
        Vec3 tangent0 = horizontalTangent.lengthSqr() == 0.0D ? Vec3.ZERO : horizontalTangent.normalize();
        Vec3 normal0 = new Vec3(0, 1, 0);
        Vec3 binormal0 = tangent0.lengthSqr() == 0.0D ? Vec3.ZERO : tangent0.cross(normal0).normalize();

        return new Frame(closestPoint, tangent, clampedDistance / length, tangent0, normal0, binormal0);
    }

    public double getTotalLength() {
        return start.distanceTo(end);
    }

    public record Frame(
            Vec3 closestPoint,
            Vec3 tangent,
            double globalT,
            Vec3 tangent0,
            Vec3 normal0,
            Vec3 binormal0
    ) {
    }
}
