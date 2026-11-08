package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.compat.aeronautics.RailAircraftMotionBridge;
import com.monpai.sailboatmod.transport.GuidedSurfacePath;
import com.monpai.sailboatmod.transport.board.TownVoyageSnapshot;
import com.monpai.sailboatmod.transport.physical.PhysicalVehicleView;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

/** Moves a real Sable body through one checked pose at a time. Only native readback advances freight progress. */
final class RegisteredSurfaceGuide {
    private static final double TICKS_PER_SECOND = 20;
    private static final double ACCELERATION_PER_TICK = 0.16;
    private static final double MIN_PROGRESS_PER_TICK = 0.0125;
    private static final double MAX_HEADING_STEP = Math.toRadians(4);
    private static final double MAX_PITCH_STEP = Math.toRadians(2);
    private static final double POSITION_TOLERANCE = 0.018;
    private static final double ANGLE_TOLERANCE = 0.016;
    private static final double MAX_WATER_APPROACH = 4;
    private static final double MAX_ROAD_APPROACH = 12;
    private static final double START_PROJECTION_WINDOW = 8;
    private static final double MAX_VERTICAL_OFFSET = 8;

    interface Probe {
        String obstruction(Vec3 anchor, Vec3 tangent, GuidedSurfacePath.Sample routePoint, AABB swept);
    }

    record Result(int routeIndex, boolean arrived, double speed, double turnRadians, String reason) {}
    private record Pending(double distance, Vec3 anchor, Vec3 tangent, Vec3 from, double turn, long tick) {}

    private GuidedSurfacePath path;
    private GuidedSurfacePath.Timing timing;
    private RailAircraftMotionBridge.Lease lease;
    private Pending pending;
    private UUID body;
    private int routeHash;
    private double distance;
    private double verticalOffset;
    private double speed;
    private boolean align;
    private boolean restored;
    private boolean approach;
    private boolean legacyApproach;
    private boolean invalidSavedState;
    private boolean confirmedRouteProgress;

    Result tick(BlockEntity controller, UUID owner, PhysicalVehicleView view, Direction facing, List<Vec3> route,
                int routeIndex, double speedLimit, boolean water, Predicate<Vec3> departureZone, Probe probe) {
        if (invalidSavedState) return waitAt(controller, routeIndex, "guided_recovery_review");
        if (path == null) {
            int currentHash = route.hashCode();
            if (restored && (!view.physicalId().equals(body) || currentHash != routeHash))
                return waitAt(controller, routeIndex, "guided_recovery_review");
            try {
                path = GuidedSurfacePath.compile(route, water ? 4 : 2.5);
            } catch (IllegalArgumentException invalid) {
                return waitAt(controller, routeIndex, "missing_route");
            }
            if (!restored) {
                double around = path.distanceAtRouteIndex(routeIndex);
                distance = path.nearestDistance(view.worldPosition(), Math.max(0, around - START_PROJECTION_WINDOW),
                        around + START_PROJECTION_WINDOW);
                verticalOffset = view.worldPosition().y - path.sample(distance).position().y;
                body = view.physicalId();
                routeHash = currentHash;
                approach = distance <= START_PROJECTION_WINDOW;
            } else if (legacyApproach) {
                // fix88 stored the projected cursor before actual acquisition had finished.
                approach = distance <= START_PROJECTION_WINDOW;
                legacyApproach = false;
            }
            align = true;
        }
        if (!view.physicalId().equals(body) || !Double.isFinite(distance) || distance < 0 || distance > path.length()
                || !Double.isFinite(verticalOffset) || Math.abs(verticalOffset) > MAX_VERTICAL_OFFSET)
            return waitAt(controller, routeIndex, "guided_recovery_review");
        GuidedSurfacePath.Sample current = path.sample(distance);
        Vec3 currentAnchor = anchor(current);
        if (align) {
            if (!reacquirePosition(view, currentAnchor, water, departureZone))
                return waitAt(controller, routeIndex, "guided_off_route");
            current = path.sample(distance);
            currentAnchor = anchor(current);
        }

        if (lease == null || !RailAircraftMotionBridge.owns(controller, lease)) {
            confirmedRouteProgress = false;
            var acquired = RailAircraftMotionBridge.acquire(controller, owner, body, facing);
            if (!acquired.accepted()) return waitAt(controller, current.routeIndex(), acquired.reason());
            lease = acquired.lease();
        }

        long now = controller.getLevel().getGameTime();
        double actualSpeed = 0;
        double actualTurn = 0;
        if (pending != null) {
            var nativeStatus = RailAircraftMotionBridge.status(controller, lease);
            if (!nativeStatus.success()) return waitAt(controller, current.routeIndex(), nativeStatus.reason());
            if (!RailAircraftMotionBridge.matchesTarget(controller, lease, pending.anchor(), pending.tangent(), 0,
                    POSITION_TOLERANCE, ANGLE_TOLERANCE)) {
                if (now - pending.tick() < 6) return new Result(current.routeIndex(), false, 0, 0, "");
                return waitAt(controller, current.routeIndex(), "guided_pose_unconfirmed");
            }
            if (!align && pending.distance() > distance) confirmedRouteProgress = true;
            distance = pending.distance();
            actualSpeed = view.worldPosition().subtract(pending.from()).horizontalDistance() * TICKS_PER_SECOND;
            actualTurn = pending.turn();
            pending = null;
            current = path.sample(distance);
            currentAnchor = anchor(current);
        }

        if (distance >= path.length() - 1e-6 && view.worldPosition().distanceToSqr(currentAnchor) <= 0.04) {
            String blocked = probe.obstruction(currentAnchor, view.worldForward(), current, view.worldBounds());
            if (!blocked.isEmpty()) return waitAt(controller, current.routeIndex(), blocked);
            RailAircraftMotionBridge.hold(controller, lease);
            speed = 0;
            return new Result(current.routeIndex(), true, 0, 0, "");
        }

        Vec3 position = view.worldPosition();
        Vec3 forward = view.worldForward();
        if (forward.horizontalDistanceSqr() < 1e-6)
            return waitAt(controller, current.routeIndex(), "guided_pose_unconfirmed");
        double heading = Math.atan2(forward.z, forward.x);
        double pitch = Math.atan2(forward.y, forward.horizontalDistance());

        if (align && position.distanceToSqr(currentAnchor) > 0.0004) {
            Vec3 toPath = currentAnchor.subtract(position);
            double gap = toPath.length();
            double desiredHeading = toPath.horizontalDistanceSqr() > 0.01 ? Math.atan2(toPath.z, toPath.x) : heading;
            double headingGap = wrap(desiredHeading - heading);
            double yawStep = Math.clamp(headingGap, -MAX_HEADING_STEP, MAX_HEADING_STEP);
            double step = Math.abs(headingGap) <= 2 * MAX_HEADING_STEP
                    ? Math.min(Math.min(0.10, speedLimit / TICKS_PER_SECOND), gap) : 0;
            Vec3 target = position.add(toPath.scale(step / gap));
            Vec3 tangent = new Vec3(Math.cos(heading + yawStep) * Math.cos(pitch), Math.sin(pitch),
                    Math.sin(heading + yawStep) * Math.cos(pitch));
            return submit(controller, position, target, current, tangent, distance,
                    now, yawStep, actualSpeed, actualTurn, probe, view.worldBounds());
        }
        align = approach = false;

        speed = Math.min(Math.min(speedLimit, speed + ACCELERATION_PER_TICK), path.speedLimit(distance));
        double nextDistance = Math.min(path.length(), distance + Math.max(MIN_PROGRESS_PER_TICK, speed / TICKS_PER_SECOND));
        var next = path.sample(nextDistance);
        double targetHeading = Math.atan2(next.tangent().z, next.tangent().x);
        double yawDelta = wrap(targetHeading - heading);
        if (Math.abs(yawDelta) > MAX_HEADING_STEP) {
            // Keep the body over the last proved road while the nose catches a tight turn.
            speed = Math.min(speed, 1.5);
            nextDistance = Math.abs(yawDelta) > MAX_HEADING_STEP * 2 ? distance
                    : Math.min(nextDistance, distance + speed / TICKS_PER_SECOND);
            next = path.sample(nextDistance);
        }
        double targetPitch = water ? 0 : Math.atan2(next.tangent().y, next.tangent().horizontalDistance());
        targetPitch = Math.clamp(targetPitch, -Math.PI / 6, Math.PI / 6);
        double yawStep = Math.clamp(yawDelta, -MAX_HEADING_STEP, MAX_HEADING_STEP);
        double nose = heading + yawStep;
        double rise = pitch + Math.clamp(targetPitch - pitch, -MAX_PITCH_STEP, MAX_PITCH_STEP);
        Vec3 tangent = new Vec3(Math.cos(nose) * Math.cos(rise), Math.sin(rise), Math.sin(nose) * Math.cos(rise));
        return submit(controller, position, anchor(next), next, tangent, nextDistance,
                now, yawStep, actualSpeed, actualTurn, probe, view.worldBounds());
    }

    private Result submit(BlockEntity controller, Vec3 from, Vec3 anchor, GuidedSurfacePath.Sample routePoint,
                          Vec3 tangent, double nextDistance, long now, double turn, double actualSpeed,
                          double actualTurn, Probe probe, AABB actualBounds) {
        var swept = RailAircraftMotionBridge.sweptBounds(controller, lease, actualBounds, anchor, tangent, 0);
        if (swept.isEmpty()) return waitAt(controller, path.sample(distance).routeIndex(), "guided_pose_unconfirmed");
        String blocked = probe.obstruction(anchor, tangent, routePoint, swept.orElseThrow());
        if (!blocked.isEmpty()) return waitAt(controller, path.sample(distance).routeIndex(), blocked);
        var movement = RailAircraftMotionBridge.move(controller, lease, anchor, tangent, 0);
        if (!movement.success()) return waitAt(controller, path.sample(distance).routeIndex(), movement.reason());
        pending = new Pending(nextDistance, anchor, tangent, from, turn, now);
        return new Result(path.sample(distance).routeIndex(), false, actualSpeed, actualTurn, "");
    }

    private boolean reacquirePosition(PhysicalVehicleView view, Vec3 expected, boolean water,
                                      Predicate<Vec3> departureZone) {
        if (view.worldPosition().distanceToSqr(expected) <= 0.04) return true;
        if (approach) return canApproach(view.worldPosition(), expected, water, departureZone);
        // World and controller are saved separately. Reacquire at most one plausible tick away.
        double actual = path.nearestDistance(view.worldPosition(), distance - 1.7, distance + 1.7);
        Vec3 actualAnchor = anchor(path.sample(actual));
        if (view.worldPosition().distanceToSqr(actualAnchor) > 0.16) return false;
        distance = actual;
        return true;
    }

    static boolean canApproach(Vec3 parked, Vec3 entry, boolean water, Predicate<Vec3> departureZone) {
        double limit = water ? MAX_WATER_APPROACH : MAX_ROAD_APPROACH;
        Vec3 difference = parked.subtract(entry);
        return difference.horizontalDistanceSqr() <= limit * limit && Math.abs(difference.y) <= 0.75
                && departureZone.test(parked) && departureZone.test(entry);
    }

    private Vec3 anchor(GuidedSurfacePath.Sample sample) {
        return sample.position().add(0, verticalOffset, 0);
    }

    private static double wrap(double radians) {
        return Math.atan2(Math.sin(radians), Math.cos(radians));
    }

    private Result waitAt(BlockEntity controller, int routeIndex, String reason) {
        hold(controller);
        return new Result(routeIndex, false, 0, 0, reason);
    }

    OptionalLong remainingEtaTicks(double cruiseSpeed, IntPredicate reserveTiming) {
        if (!confirmedRouteProgress || !Double.isFinite(cruiseSpeed) || cruiseSpeed <= 0) return OptionalLong.empty();
        if (timing == null && !reserveTiming.test(path.pointCount())) return OptionalLong.empty();
        double effectiveCruiseSpeed = Math.max(cruiseSpeed, MIN_PROGRESS_PER_TICK * TICKS_PER_SECOND);
        if (timing == null || timing.cruiseSpeed() != effectiveCruiseSpeed) timing = path.timing(effectiveCruiseSpeed);
        long ticks = timing.remainingTicks(distance);
        return ticks > 0 && ticks <= TownVoyageSnapshot.MAX_ETA_TICKS ? OptionalLong.of(ticks) : OptionalLong.empty();
    }

    void discardEtaTiming() { timing = null; }

    void hold(BlockEntity controller) {
        if (lease != null) RailAircraftMotionBridge.hold(controller, lease);
        pending = null;
        speed = 0;
        confirmedRouteProgress = false;
    }

    void realign() {
        align = true;
        restored = body != null;
        confirmedRouteProgress = false;
    }

    boolean release(BlockEntity controller) {
        confirmedRouteProgress = false;
        if (lease != null) {
            var result = RailAircraftMotionBridge.release(controller, lease);
            if (!result.success() && !"air_rail_stale_lease".equals(result.reason())) return false;
        }
        lease = null;
        pending = null;
        speed = 0;
        align = true;
        return true;
    }

    boolean clear(BlockEntity controller) {
        if (!release(controller)) return false;
        path = null;
        timing = null;
        body = null;
        restored = invalidSavedState = approach = legacyApproach = false;
        return true;
    }

    CompoundTag save() {
        if (body == null && !invalidSavedState) return new CompoundTag();
        CompoundTag tag = new CompoundTag();
        if (invalidSavedState) tag.putBoolean("Invalid", true);
        else {
            tag.putUUID("Body", body);
            tag.putInt("RouteHash", routeHash);
            tag.putDouble("Distance", distance);
            tag.putDouble("Offset", verticalOffset);
            tag.putBoolean("Approach", approach);
        }
        return tag;
    }

    void load(CompoundTag tag) {
        path = null;
        timing = null;
        body = null;
        pending = null;
        speed = 0;
        invalidSavedState = false;
        confirmedRouteProgress = false;
        restored = approach = legacyApproach = false;
        if (tag.isEmpty()) return;
        if (!tag.hasUUID("Body") || !tag.contains("RouteHash", Tag.TAG_INT)
                || !tag.contains("Distance", Tag.TAG_DOUBLE) || !tag.contains("Offset", Tag.TAG_DOUBLE)) {
            invalidSavedState = true;
            return;
        }
        body = tag.getUUID("Body");
        routeHash = tag.getInt("RouteHash");
        distance = tag.getDouble("Distance");
        verticalOffset = tag.getDouble("Offset");
        approach = tag.getBoolean("Approach");
        legacyApproach = !tag.contains("Approach", Tag.TAG_BYTE);
        restored = true;
    }
}
