package com.monpai.sailboatmod.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

public final class CarriageLandDriveModel {
    public static final float MAX_FORWARD_SPEED = 10.5F;
    public static final float MAX_REVERSE_SPEED = 4.0F;
    public static final float ACCELERATION_SPEED = 0.52F;
    public static final float TURN_SENSITIVITY = 3.0F;
    public static final float MAX_TURN_ANGLE = 35.0F;
    public static final float FRONT_AXLE_Z = 14.0F * 0.0625F;
    public static final float REAR_AXLE_Z = -14.5F * 0.0625F;

    private static final float ROAD_SURFACE_MODIFIER = 1.0F;
    private static final float OFFROAD_SURFACE_MODIFIER = 0.62F;
    private static final double GRAVITY = -0.08D;

    private CarriageLandDriveModel() {
    }

    public static State step(State state, CarriageDriveInput input, Environment environment) {
        State current = state == null ? State.idle(0.0F) : state;
        CarriageDriveInput command = input == null ? CarriageDriveInput.idle() : input;
        Environment env = environment == null ? Environment.offroad(false) : environment;

        float speed = updateSpeed(current.currentSpeed(), command, env);
        float turnAngle = updateTurnAngle(current.turnAngle(), command, speed);
        float wheelAngle = turnAngle * Math.max(0.45F, 1.0F - Math.abs(speed / 20.0F));
        Motion motion = axleMotion(current.yaw(), speed, wheelAngle, env.onGround());
        return new State(speed, turnAngle, wheelAngle, motion.yaw(), motion.delta());
    }

    public static float targetTurnAngle(float currentTurnAngle,
                                        CarriageDriveInput.TurnDirection turnDirection,
                                        float currentSpeed,
                                        boolean drifting) {
        CarriageDriveInput.TurnDirection direction = turnDirection == null
                ? CarriageDriveInput.TurnDirection.FORWARD
                : turnDirection;
        if (direction != CarriageDriveInput.TurnDirection.FORWARD) {
            float amount = direction.direction() * TURN_SENSITIVITY * Math.max(0.65F, 1.0F - Math.abs(currentSpeed / 20.0F));
            if (drifting) {
                amount *= 0.45F;
            }
            float next = currentTurnAngle + amount;
            if (Math.abs(next) > MAX_TURN_ANGLE) {
                return MAX_TURN_ANGLE * direction.direction();
            }
            return next;
        }
        return drifting ? currentTurnAngle * 0.95F : currentTurnAngle * 0.85F;
    }

    private static float updateSpeed(float currentSpeed, CarriageDriveInput input, Environment environment) {
        float surface = environment.onRoad() ? ROAD_SURFACE_MODIFIER : OFFROAD_SURFACE_MODIFIER;
        float power = Math.max(input.power(), input.hasThrottle() ? 1.0F : 0.0F);
        if (environment.onGround()) {
            if (input.acceleration() == CarriageDriveInput.AccelerationDirection.FORWARD
                    || input.acceleration() == CarriageDriveInput.AccelerationDirection.CHARGING) {
                float maxSpeed = MAX_FORWARD_SPEED * surface * power;
                if (currentSpeed < maxSpeed) {
                    currentSpeed = Math.min(maxSpeed, currentSpeed + ACCELERATION_SPEED);
                } else if (currentSpeed > maxSpeed) {
                    currentSpeed *= 0.975F;
                }
                return currentSpeed;
            }
            if (input.acceleration() == CarriageDriveInput.AccelerationDirection.REVERSE) {
                if (currentSpeed > 0.5F) {
                    return Math.max(0.0F, currentSpeed - ACCELERATION_SPEED * 1.8F);
                }
                float maxReverse = -MAX_REVERSE_SPEED * surface * power;
                if (currentSpeed > maxReverse) {
                    currentSpeed = Math.max(maxReverse, currentSpeed - ACCELERATION_SPEED);
                } else if (currentSpeed < maxReverse) {
                    currentSpeed *= 0.975F;
                }
                return currentSpeed;
            }
            return currentSpeed * 0.85F;
        }
        return currentSpeed * 0.98F;
    }

    private static float updateTurnAngle(float currentTurnAngle, CarriageDriveInput input, float currentSpeed) {
        if (input.turn() == CarriageDriveInput.TurnDirection.FORWARD) {
            return currentTurnAngle * 0.85F;
        }
        float target = input.targetTurnAngle();
        if (Math.abs(target) < 1.0E-3F) {
            target = targetTurnAngle(currentTurnAngle, input.turn(), currentSpeed, false);
        }
        return Mth.clamp(target, -MAX_TURN_ANGLE, MAX_TURN_ANGLE);
    }

    private static Motion axleMotion(float yaw, float speed, float wheelAngle, boolean onGround) {
        Vec3 nextFrontAxle = new Vec3(0.0D, 0.0D, speed / 20.0F)
                .yRot(wheelAngle * Mth.DEG_TO_RAD)
                .add(0.0D, 0.0D, FRONT_AXLE_Z);
        Vec3 nextRearAxle = new Vec3(0.0D, 0.0D, speed / 20.0F)
                .add(0.0D, 0.0D, REAR_AXLE_Z);
        double deltaYaw = Math.toDegrees(Math.atan2(
                nextRearAxle.z - nextFrontAxle.z,
                nextRearAxle.x - nextFrontAxle.x
        )) + 90.0D;
        float nextYaw = yaw + (float) deltaYaw;

        Vec3 axleCenterDelta = nextFrontAxle.add(nextRearAxle).scale(0.5D)
                .subtract(new Vec3(0.0D, 0.0D, (FRONT_AXLE_Z + REAR_AXLE_Z) * 0.5D))
                .yRot((-nextYaw + 90.0F) * Mth.DEG_TO_RAD);
        float targetRotation = (float) Math.toDegrees(Math.atan2(axleCenterDelta.z, axleCenterDelta.x));
        float f1 = Mth.sin(targetRotation * Mth.DEG_TO_RAD) / 20.0F * (speed > 0.0F ? 1.0F : -1.0F);
        float f2 = Mth.cos(targetRotation * Mth.DEG_TO_RAD) / 20.0F * (speed > 0.0F ? 1.0F : -1.0F);
        Vec3 delta = new Vec3(-speed * f1, GRAVITY, speed * f2);
        return new Motion(nextYaw, delta);
    }

    public record State(float currentSpeed,
                        float turnAngle,
                        float wheelAngle,
                        float yaw,
                        Vec3 deltaMovement) {
        public State {
            deltaMovement = deltaMovement == null ? Vec3.ZERO : deltaMovement;
        }

        public static State idle(float yaw) {
            return new State(0.0F, 0.0F, 0.0F, yaw, Vec3.ZERO);
        }
    }

    public record Environment(boolean onGround, boolean onRoad) {
        public static Environment road() {
            return new Environment(true, true);
        }

        public static Environment offroad(boolean onGround) {
            return new Environment(onGround, false);
        }
    }

    private record Motion(float yaw, Vec3 delta) {
    }
}
