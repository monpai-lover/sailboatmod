package com.monpai.sailboatmod.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

final class CarriageRailPathFollower {
    private static final double MAX_SNAP_DISTANCE = 2.25D;
    private static final double EPSILON = 1.0E-6D;

    private CarriageRailPathFollower() {
    }

    static StepResult step(List<Vec3> route, Vec3 currentPosition, int targetIndex, double maxStepDistance) {
        if (route == null || route.size() < 2 || currentPosition == null || maxStepDistance <= 0.0D) {
            return StepResult.inactive(currentPosition == null ? Vec3.ZERO : currentPosition, targetIndex);
        }
        int index = Mth.clamp(targetIndex, 1, route.size() - 1);
        Vec3 previous = route.get(index - 1);
        Vec3 target = route.get(index);
        if (previous == null || target == null) {
            return StepResult.inactive(currentPosition, index);
        }

        Vec3 cursor = projectOntoSegmentXZ(currentPosition, previous, target);
        if (horizontalDistance(currentPosition, cursor) > MAX_SNAP_DISTANCE) {
            return StepResult.inactive(currentPosition, index);
        }

        // 净沿轨位移 = frontGap + remaining。cursor 初值是投影点(可能因 t-clamp 落后于车的真实
        // 沿轨位置 → frontGap 为负),补回这部分回退量使每 tick 净推进恒 = maxStepDistance，消除起步/
        // 到站蠕动。上限钳 2 个步长，避免发车头一两 tick 因补偿一次性窜出去肉眼可见；下限保证极端
        // 贴段端时仍微推、防 0 步长卡住。
        double frontGap = frontGap(currentPosition, cursor, previous, target);
        double remaining = Mth.clamp(maxStepDistance - frontGap, EPSILON * 2.0D, maxStepDistance * 2.0D);
        while (remaining > EPSILON && index < route.size()) {
            previous = route.get(index - 1);
            target = route.get(index);
            if (previous == null || target == null) {
                return StepResult.inactive(cursor, index);
            }
            float segmentYaw = yawFromSegment(previous, target);
            double distanceToTarget = cursor.distanceTo(target);
            if (distanceToTarget <= EPSILON) {
                if (index >= route.size() - 1) {
                    return StepResult.finished(cursor, segmentYaw, index);
                }
                index++;
                continue;
            }
            if (remaining < distanceToTarget) {
                cursor = cursor.add(target.subtract(cursor).normalize().scale(remaining));
                return StepResult.active(cursor, segmentYaw, index, cursor.subtract(currentPosition));
            }
            cursor = target;
            remaining -= distanceToTarget;
            if (index >= route.size() - 1) {
                return StepResult.finished(cursor, segmentYaw, index);
            }
            index++;
        }
        return StepResult.active(cursor, yawFromRouteSegment(route, index), index, cursor.subtract(currentPosition));
    }

    private static Vec3 projectOntoSegmentXZ(Vec3 point, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double lengthSqr = dx * dx + dz * dz;
        if (lengthSqr <= EPSILON) {
            return from;
        }
        double t = ((point.x - from.x) * dx + (point.z - from.z) * dz) / lengthSqr;
        t = Mth.clamp(t, 0.0D, 1.0D);
        return new Vec3(
                from.x + dx * t,
                from.y + (to.y - from.y) * t,
                from.z + dz * t
        );
    }

    private static double horizontalDistance(Vec3 left, Vec3 right) {
        double dx = right.x - left.x;
        double dz = right.z - left.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // cursor 相对 current 在 (from→to) 行进方向上的领先距离;cursor0 因 t-clamp 落后于车时为负。
    private static double frontGap(Vec3 current, Vec3 cursor, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len <= EPSILON) {
            return 0.0D;
        }
        double ux = dx / len;
        double uz = dz / len;
        return (cursor.x - current.x) * ux + (cursor.z - current.z) * uz;
    }

    private static float yawFromDelta(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        if (Math.abs(dx) <= EPSILON && Math.abs(dz) <= EPSILON) {
            return 0.0F;
        }
        return (float) (Mth.atan2(-dx, dz) * (180.0D / Math.PI));
    }

    private static float yawFromSegment(Vec3 from, Vec3 to) {
        return yawFromDelta(from, to);
    }

    private static float yawFromRouteSegment(List<Vec3> route, int targetIndex) {
        if (route == null || route.size() < 2) {
            return 0.0F;
        }
        int index = Mth.clamp(targetIndex, 1, route.size() - 1);
        Vec3 from = route.get(index - 1);
        Vec3 to = route.get(index);
        if (from == null || to == null) {
            return 0.0F;
        }
        return yawFromSegment(from, to);
    }

    record StepResult(boolean active,
                      boolean finished,
                      Vec3 position,
                      float yaw,
                      int targetIndex,
                      Vec3 deltaMovement) {
        StepResult {
            position = position == null ? Vec3.ZERO : position;
            deltaMovement = deltaMovement == null ? Vec3.ZERO : deltaMovement;
        }

        static StepResult active(Vec3 position, float yaw, int targetIndex, Vec3 deltaMovement) {
            return new StepResult(true, false, position, yaw, targetIndex, deltaMovement);
        }

        static StepResult finished(Vec3 position, float yaw, int targetIndex) {
            return new StepResult(false, true, position, yaw, targetIndex, Vec3.ZERO);
        }

        static StepResult inactive(Vec3 position, int targetIndex) {
            return new StepResult(false, false, position, 0.0F, targetIndex, Vec3.ZERO);
        }
    }
}
