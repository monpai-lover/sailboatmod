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
        return drive(input, false);
    }

    public static Command drive(Input input, boolean atFinalTerminal) {
        return drive(input, atFinalTerminal, true);
    }

    /** Passing a route point keeps propulsion; only the final target uses arrival braking. */
    public static Command drive(Input input, boolean atFinalTerminal, boolean stopAtTarget) {
        if (input != null && input.manualOverride()) {
            return new Command(0, 0, 0, 0, 0, 0, false, false, false, "manual");
        }
        if (input == null || !finite(input.position()) || !finite(input.forward()) || !finite(input.velocity())
                || !finite(input.target()) || input.kind() == null || !(input.maxSpeed() > 0)
                || !Double.isFinite(input.maxSpeed()) || input.arrivalRadius() < 0
                || !Double.isFinite(input.arrivalRadius()) || input.forward().horizontalDistanceSqr() < 1e-8) {
            return hold("invalid_pose");
        }
        if (!input.terrainReady()) return hold("waiting_terrain");
        Vec3 delta = input.target().subtract(input.position());
        double distance = Math.sqrt(delta.horizontalDistanceSqr());
        double speed = Math.sqrt(input.velocity().horizontalDistanceSqr());
        if (atFinalTerminal || stopAtTarget && distance <= input.arrivalRadius()) return brakeForArrival(speed);
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
        double approachScale = stopAtTarget
                ? Math.min(1, Math.max(0.2, (distance - input.arrivalRadius()) / 15)) : 1;
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

    private static Command brakeForArrival(double speed) {
        boolean stopped = speed <= 0.25;
        return new Command(0, 0, 0, 15, 0, 0, stopped, false, true, stopped ? "arrived" : "braking");
    }

    /** The final parking circle must fit the terminal's inclusive horizontal zone. Negative means outside. */
    public static double terminalArrivalRadius(double radius, Vec3 offset, int minX, int maxX, int minZ, int maxZ) {
        double xClearance = Math.min(offset.x - minX, maxX - offset.x);
        double zClearance = Math.min(offset.z - minZ, maxZ - offset.z);
        return Math.min(radius, Math.min(xClearance, zClearance));
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
