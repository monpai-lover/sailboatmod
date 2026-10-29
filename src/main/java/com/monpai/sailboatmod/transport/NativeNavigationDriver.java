package com.monpai.sailboatmod.transport;

import net.minecraft.world.phys.Vec3;

/** Native actuator commands only; this class never changes physical position or velocity. */
public final class NativeNavigationDriver {
    public record Input(LogisticsCarrier.Kind kind, Vec3 position, Vec3 forward, Vec3 velocity,
                        Vec3 target, double maxSpeed, double arrivalRadius, boolean terrainReady,
                        boolean manualOverride, boolean allowReverse) { }
    public record Command(int throttle, int left, int right, int brake, int forwardGear, int reverseGear,
                          boolean arrived, boolean waiting, boolean engaged, String reason) { }
    private NativeNavigationDriver() { }

    public static Command drive(Input input) {
        if (input != null && input.manualOverride()) {
            return new Command(0, 0, 0, 0, 0, 0, false, false, false, "manual");
        }
        if (input == null || !finite(input.position()) || !finite(input.forward()) || !finite(input.velocity())
                || !finite(input.target()) || input.kind() == null || !(input.maxSpeed() > 0)
                || !Double.isFinite(input.maxSpeed()) || !(input.arrivalRadius() > 0)
                || !Double.isFinite(input.arrivalRadius()) || input.forward().horizontalDistanceSqr() < 1e-8) {
            return hold("invalid_pose");
        }
        if (!input.terrainReady()) return hold("waiting_terrain");
        Vec3 delta = input.target().subtract(input.position());
        double distance = Math.sqrt(delta.horizontalDistanceSqr());
        double speed = Math.sqrt(input.velocity().horizontalDistanceSqr());
        if (distance <= input.arrivalRadius()) {
            return new Command(0, 0, 0, 15, 0, 0, speed <= 0.25, false, true,
                    speed <= 0.25 ? "arrived" : "braking");
        }
        double dot = input.forward().x * delta.x + input.forward().z * delta.z;
        double cross = input.forward().x * delta.z - input.forward().z * delta.x;
        double angle = Math.toDegrees(Math.atan2(cross, dot));
        boolean reversing = input.kind() == LogisticsCarrier.Kind.LAND && input.allowReverse() && Math.abs(angle) > 120;
        if (reversing) {
            angle -= Math.copySign(180, angle);
            angle = -angle; // Negative drive velocity reverses the wheel-angle/yaw response.
        }
        int steering = clamp((int) Math.ceil(Math.abs(angle) / (input.kind() == LogisticsCarrier.Kind.LAND ? 30 : 50) * 15));
        int left = angle < -1 ? steering : 0;
        int right = angle > 1 ? steering : 0;
        if (speed > input.maxSpeed() * 1.05) {
            return new Command(0, left, right, 12, 0, 0, false, false, true, "speed_limit");
        }
        double cornerScale = Math.max(0.12, 1 - Math.abs(angle) / 110);
        double approachScale = Math.min(1, Math.max(0.2, (distance - input.arrivalRadius()) / 15));
        int throttle = clamp((int) Math.ceil(15 * cornerScale * approachScale * Math.max(0.1, 1 - speed / input.maxSpeed())));
        int brake = 0;
        if (input.kind() == LogisticsCarrier.Kind.LAND && Math.abs(angle) > 55) {
            throttle = Math.min(throttle, 3);
            if (speed > input.maxSpeed() * 0.3) brake = 10;
        }
        if (reversing) throttle = Math.min(throttle, 4);
        return new Command(throttle, left, right, brake, reversing ? 0 : 15, reversing ? 15 : 0,
                false, false, true, reversing ? "reversing" : "driving");
    }

    public static int transmissionSignal(int throttle, boolean inverted) {
        int signal = clamp(throttle);
        return inverted ? 15 - signal : signal;
    }

    private static Command hold(String reason) {
        return new Command(0, 0, 0, 15, 0, 0, false, true, true, reason);
    }

    private static int clamp(int signal) { return Math.max(0, Math.min(15, signal)); }
    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
