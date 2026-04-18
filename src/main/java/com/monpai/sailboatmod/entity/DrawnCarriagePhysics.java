package com.monpai.sailboatmod.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class DrawnCarriagePhysics {
    private static final double ROAD_FORWARD_DAMPING = 0.94D;
    private static final double OFFROAD_FORWARD_DAMPING = 0.86D;
    private static final double ROAD_LATERAL_DAMPING = 0.22D;
    private static final double OFFROAD_LATERAL_DAMPING = 0.32D;
    private static final double TRACTION_RESPONSE = 0.35D;

    private DrawnCarriagePhysics() {
    }

    public record MotionInput(Vec3 currentDelta,
                              float carriageYaw,
                              float leadYaw,
                              double tractionForce,
                              double targetSpeed,
                              boolean onGround,
                              boolean onRoad,
                              boolean climbing,
                              boolean braking) {
    }

    public record MotionResult(Vec3 nextDelta, float nextYaw) {
    }

    public static MotionResult solveGroundMotion(MotionInput input) {
        if (input == null) {
            return new MotionResult(Vec3.ZERO, 0.0F);
        }

        Vec3 current = input.currentDelta() == null ? Vec3.ZERO : input.currentDelta();
        float nextYaw = Mth.rotLerp(0.45F, input.carriageYaw(), input.leadYaw());
        Vec3 forward = flatDirection(nextYaw);
        double forwardSpeed = current.x * forward.x + current.z * forward.z;
        Vec3 forwardComponent = new Vec3(forward.x * forwardSpeed, 0.0D, forward.z * forwardSpeed);
        Vec3 lateralComponent = new Vec3(current.x, 0.0D, current.z).subtract(forwardComponent);

        double speedError = input.targetSpeed() - forwardSpeed;
        double tractionDelta = Mth.clamp(speedError, -input.tractionForce() * TRACTION_RESPONSE, input.tractionForce() * TRACTION_RESPONSE);
        double nextForwardSpeed = forwardSpeed + tractionDelta;
        if (input.climbing() && nextForwardSpeed > 0.0D) {
            nextForwardSpeed *= 0.88D;
        }

        double forwardDamping = input.onRoad() ? ROAD_FORWARD_DAMPING : OFFROAD_FORWARD_DAMPING;
        double lateralDamping = input.onRoad() ? ROAD_LATERAL_DAMPING : OFFROAD_LATERAL_DAMPING;
        Vec3 nextPlanar = new Vec3(forward.x * nextForwardSpeed, 0.0D, forward.z * nextForwardSpeed)
                .scale(forwardDamping)
                .add(lateralComponent.scale(lateralDamping));

        if (input.braking()) {
            nextPlanar = nextPlanar.scale(0.55D);
        }

        double nextY = input.onGround() ? 0.0D : current.y;
        return new MotionResult(new Vec3(nextPlanar.x, nextY, nextPlanar.z), nextYaw);
    }

    private static Vec3 flatDirection(float yawDegrees) {
        double yawRad = yawDegrees * (Math.PI / 180.0D);
        return new Vec3(-Math.sin(yawRad), 0.0D, Math.cos(yawRad));
    }
}
