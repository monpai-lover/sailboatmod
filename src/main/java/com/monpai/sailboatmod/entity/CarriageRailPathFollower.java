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

        // 推进**只算水平(XZ)距离**：rail 跟随是贴地面沿路线走，Y 由 cursor 沿段线性插值(projectOntoSegmentXZ
        // 已给) + 后续 railAutopilotSurfacePosition 地形修正负责，**不能让 waypoint 的 Y 参与推进归一化**。
        // 否则当某个 waypoint 的 Y 指向地下很深时(实测 route Y 异常)，3D normalize 会把 0.36 步长几乎全分给
        // Y 方向，XZ 只推进 0.0046 格 → 蠕动近乎卡死(实测返航 12s 仅移 1 格)。
        double remaining = maxStepDistance;
        while (remaining > EPSILON && index < route.size()) {
            previous = route.get(index - 1);
            target = route.get(index);
            if (previous == null || target == null) {
                return StepResult.inactive(cursor, index);
            }
            float segmentYaw = yawFromSegment(previous, target);
            double distanceToTarget = horizontalDistance(cursor, target);
            if (distanceToTarget <= EPSILON) {
                if (index >= route.size() - 1) {
                    return StepResult.finished(cursor, segmentYaw, index);
                }
                index++;
                continue;
            }
            if (remaining < distanceToTarget) {
                // 只沿 XZ 方向按 remaining 推进；Y 用沿段比例插值(t = 已推进/段总长)，与水平进度一致。
                double t = remaining / distanceToTarget;
                double nextX = cursor.x + (target.x - cursor.x) * t;
                double nextZ = cursor.z + (target.z - cursor.z) * t;
                double nextY = cursor.y + (target.y - cursor.y) * t;
                cursor = new Vec3(nextX, nextY, nextZ);
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
