package com.monpai.sailboatmod.compat.flightcontrol;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Optional FC 0.8.3 integration. All mutations must be called on the server thread. */
public final class FlightControlBridge {
    private FlightControlBridge() { }
    public enum Action { START, PAUSE, RESUME, CANCEL }
    public enum Airframe { AIRSHIP, ROTORCRAFT, FIXED_WING }
    public enum EndBehavior { RELEASE_CONTROL, HOLD_LAST_TARGET, HOVER, APPROACH_LANDING }
    public record Waypoint(double x, double y, double z) {
        public Waypoint {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("non_finite_waypoint");
            if (Math.abs(x) > 30_000_000 || Math.abs(z) > 30_000_000)
                throw new IllegalArgumentException("waypoint_outside_world");
        }
    }
    public record RoutePlan(String name, List<Waypoint> waypoints, Airframe airframe,
                            EndBehavior endBehavior, int linearThrottle, int angularThrottle) {
        public RoutePlan {
            name = Objects.requireNonNull(name, "name").strip();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > 64
                    || name.getBytes(StandardCharsets.UTF_8).length > 256
                    || name.codePoints().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("invalid_profile_name");
            waypoints = List.copyOf(Objects.requireNonNull(waypoints, "waypoints"));
            Objects.requireNonNull(airframe, "airframe");
            Objects.requireNonNull(endBehavior, "endBehavior");
            if (waypoints.isEmpty() || waypoints.size() > 64)
                throw new IllegalArgumentException("path_count_out_of_range");
            if (linearThrottle < 1 || linearThrottle > 15 || angularThrottle < 1 || angularThrottle > 15)
                throw new IllegalArgumentException("throttle_out_of_range");
            if (endBehavior == EndBehavior.APPROACH_LANDING) {
                if (airframe != Airframe.FIXED_WING || waypoints.size() < 2)
                    throw new IllegalArgumentException("landing_requires_fixed_wing_touchdown_and_stop");
                Waypoint touchdown = waypoints.get(waypoints.size() - 2);
                Waypoint stop = waypoints.get(waypoints.size() - 1);
                if (Math.hypot(stop.x() - touchdown.x(), stop.z() - touchdown.z()) < 1.0)
                    throw new IllegalArgumentException("landing_runway_too_short");
            }
            if (airframe == Airframe.FIXED_WING && endBehavior == EndBehavior.HOVER)
                throw new IllegalArgumentException("fixed_wing_cannot_hover");
        }
    }
    public record ExpectedState(long configRevision, long taskRevision, long slotGeneration,
                                String profileFingerprint) { }
    public record TerminalSnapshot(boolean available, String reason, boolean enabled, boolean linked,
                                   boolean profileValid, String phase, String blockReason, String detail,
                                   UUID taskId, ExpectedState expected, Integer currentPath,
                                   Integer pathCount, UUID structureId) {
        /** FC route completion only; the logistics service must still verify berth/landing and cargo. */
        public boolean completed() { return available && "COMPLETED".equals(phase) && "NONE".equals(blockReason); }
        public boolean active() {
            return switch (phase) {
                case "STARTING", "RUNNING", "HOLDING", "WAITING_REFERENCE" -> true;
                default -> false;
            };
        }
    }
    public record Result(boolean accepted, String reason, TerminalSnapshot snapshot,
                         String submittedProfileFingerprint) {
        public Result {
            submittedProfileFingerprint = Objects.requireNonNullElse(submittedProfileFingerprint, "");
            if (accepted && "profile_pending".equals(reason) && submittedProfileFingerprint.isBlank())
                throw new IllegalArgumentException("submitted_profile_fingerprint_required");
        }
        public Result(boolean accepted, String reason, TerminalSnapshot snapshot) {
            this(accepted, reason, snapshot, "");
        }
    }
    /** Position is FC's logical-pose origin, not a block center or a transformed registrar location. */
    public record VehicleSnapshot(boolean available, String reason, UUID structureId, double x, double y, double z,
                                  double worldSpeed, String airframeMode, int computerMode, boolean computerWorking,
                                  long sampledNanos, boolean fresh) { }
    public static VehicleSnapshot inspectVehicle(ServerLevel level, BlockPos terminalPos) {
        try { return FlightControlReflection.open(level, terminalPos).vehicleSnapshot(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new VehicleSnapshot(false, failureReason(failure), null, 0, 0, 0, 0, "UNKNOWN", -1, false, 0, false);
        }
    }
    /** Validates actual Sable structure UUIDs without requiring Create: Simulated controls. */
    public static boolean sameStructure(ServerLevel level, BlockPos firstPos, BlockPos terminalPos) {
        try {
            if (firstPos == null || !level.hasChunkAt(firstPos)) return false;
            return FlightControlReflection.open(level, terminalPos).sharesStructure(level.getBlockEntity(firstPos));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return false; }
    }
    public static TerminalSnapshot inspect(ServerLevel level, BlockPos terminalPos) {
        try { return FlightControlReflection.open(level, terminalPos).snapshot(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { return unavailable(failure); }
    }
    /** Reads the actual card even while FC's task/slot snapshots are still initializing. */
    public record InstalledProfileSnapshot(boolean available, String reason, String fingerprint) { }
    public static InstalledProfileSnapshot inspectInstalledProfile(ServerLevel level, BlockPos terminalPos) {
        try {
            return new InstalledProfileSnapshot(true, "", FlightControlReflection.open(level, terminalPos).installedProfileFingerprint());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new InstalledProfileSnapshot(false, failureReason(failure), "");
        }
    }
    /** Requires a real, installed navigation card and a disabled terminal. No items are created. */
    public static Result submitRoute(ServerLevel level, BlockPos terminalPos, RoutePlan route, ExpectedState expected) {
        try { return FlightControlReflection.open(level, terminalPos).submit(Objects.requireNonNull(route), expected); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            TerminalSnapshot state = inspect(level, terminalPos);
            return new Result(false, failureReason(failure), state);
        }
    }
    public static Result requestAction(ServerLevel level, BlockPos terminalPos, Action action, ExpectedState expected) {
        try { return FlightControlProtocol.request(FlightControlReflection.open(level, terminalPos), Objects.requireNonNull(action), expected); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new Result(false, failureReason(failure), inspect(level, terminalPos));
        }
    }
    static TerminalSnapshot unavailable(Throwable failure) {
        return new TerminalSnapshot(false, failureReason(failure), false, false, false, "UNAVAILABLE", "UNAVAILABLE", "", null,
                new ExpectedState(-1, -1, -1, ""), null, null, null);
    }
    private static String failureReason(Throwable failure) {
        if (failure instanceof java.lang.reflect.InvocationTargetException invoked && invoked.getCause() != null)
            failure = invoked.getCause();
        if (failure instanceof IllegalArgumentException || failure instanceof IllegalStateException)
            return failure.getMessage() == null ? "invalid_state" : failure.getMessage();
        if (failure instanceof ClassNotFoundException || failure instanceof NoClassDefFoundError)
            return "flight_control_unavailable";
        return "flight_control_api_incompatible:" + failure.getClass().getSimpleName();
    }
}
