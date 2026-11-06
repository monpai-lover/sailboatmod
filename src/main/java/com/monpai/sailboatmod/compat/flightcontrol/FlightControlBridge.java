package com.monpai.sailboatmod.compat.flightcontrol;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Optional FC 0.8.3 integration. All mutations must be called on the server thread. */
public final class FlightControlBridge {
    private FlightControlBridge() { }
    public enum Action { START, PAUSE, RESUME, CANCEL }
    public enum SuccessorDecision { REJECTED, PENDING, ACCEPTED }
    public static void claimOwnedTask(ServerLevel level, BlockPos terminalPos, UUID controllerId,
                                      UUID bodyId, TerminalSnapshot accepted) {
        if (level == null || terminalPos == null || !level.getServer().isSameThread()
                || !FlightControlRecoverySupport.isApplied()
                || accepted == null || accepted.taskId() == null) return;
        try {
            var access = FlightControlReflection.open(level, terminalPos);
            var state = access.ownershipState();
            if (!accepted.taskId().equals(state.taskId()) || accepted.expected().configRevision() != state.configRevision()
                    || accepted.expected().slotGeneration() != state.slotGeneration()
                    || !accepted.expected().profileFingerprint().equals(state.fingerprint())) return;
            FlightTaskRecoveryHooks.REGISTRY.claim(level.getServer(), FlightTaskRecoveryHooks.key(level, terminalPos), controllerId, bodyId, state);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { /* Keep strict UUID checks. */ }
    }
    public static SuccessorDecision consumeOwnedSuccessor(ServerLevel level, BlockPos terminalPos,
            UUID controllerId, UUID bodyId, UUID recordedTask, TerminalSnapshot current) {
        if (level == null || terminalPos == null || !level.getServer().isSameThread()
                || !FlightControlRecoverySupport.isApplied()
                || current == null) return SuccessorDecision.REJECTED;
        try {
            var state = FlightControlReflection.open(level, terminalPos).ownershipState();
            if (!Objects.equals(current.taskId(), state.taskId())) return SuccessorDecision.REJECTED;
            return SuccessorDecision.valueOf(FlightTaskRecoveryHooks.REGISTRY.consume(level.getServer(),
                    FlightTaskRecoveryHooks.key(level, terminalPos), controllerId, bodyId, recordedTask, state, level.getGameTime()).name());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return SuccessorDecision.REJECTED; }
    }
    public static void clearOwnedTask(ServerLevel level, BlockPos terminalPos, UUID controllerId) {
        if (level != null && terminalPos != null && level.getServer().isSameThread())
            FlightTaskRecoveryHooks.REGISTRY.clear(level.getServer(), FlightTaskRecoveryHooks.key(level, terminalPos), controllerId);
    }
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
    /** Read-only native control proof. A completed task can still have unexpired broker commands. */
    /** Own-mod opaque handle only; no native optional type is exposed in a descriptor. */
    public record GroundNeutralSnapshot(boolean available,String reason,Object handle,boolean acknowledged,
            long generation,double physicsTimeSeconds,int observations,long observedAtNanos) { }
    public record GroundNeutralRelease(double physicsTimeSeconds,long sampledNanos,long releasedAtNanos,Object identity) {
        public GroundNeutralRelease(double physicsTimeSeconds,long sampledNanos,long releasedAtNanos){this(physicsTimeSeconds,sampledNanos,releasedAtNanos,null);}
    }
    record GroundNeutralHandle(Object computer,NativeGroundNeutral.Context context,NativeGroundNeutral.Lease lease) { }
    record GroundNeutralBarrierIdentity(GroundNeutralHandle owner,Object thrustBefore) { }
    public static GroundNeutralSnapshot requestGroundNeutral(ServerLevel level,BlockEntity requester,UUID owner,
            Object mission,BlockPos terminal,UUID body,String profile,boolean eligible) {
        if(!eligible||!NativeGroundNeutralCompatibility.isApplied())return unavailableGroundNeutral("ground_native_neutral_unavailable");
        try{return FlightControlReflection.open(level,terminal).requestGroundNeutral(requester,owner,mission,body,profile);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){return unavailableGroundNeutral(failureReason(unavailable));}
    }
    /** A separate stop-only scope; its acknowledgement can never be used as DRIVE authority. */
    public static GroundNeutralSnapshot requestGroundHold(ServerLevel level,BlockEntity requester,UUID owner,
            Object mission,BlockPos terminal,UUID body,String profile,boolean eligible) {
        if(!eligible||!NativeGroundNeutralCompatibility.isApplied())return unavailableGroundNeutral("ground_native_neutral_unavailable");
        try{return FlightControlReflection.open(level,terminal).requestGroundHold(requester,owner,mission,body,profile);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){return unavailableGroundNeutral(failureReason(unavailable));}
    }
    public static GroundNeutralRelease releaseGroundNeutral(ServerLevel level,BlockPos terminal,Object handle) {
        GroundNeutralRelease before=new GroundNeutralRelease(-1,-1,0,handle);
        try{before=FlightControlReflection.open(level,terminal).groundNeutralReleaseSnapshot(handle);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){/* Always release our handle even if its world disappeared. */}
        finally{if(handle instanceof GroundNeutralHandle own)NativeGroundNeutral.release(own.lease());}
        return new GroundNeutralRelease(before.physicsTimeSeconds(),before.sampledNanos(),System.nanoTime(),before.identity());
    }
    public static boolean normalFrameAfterGroundRelease(ServerLevel level,BlockPos terminal,UUID body,String profile,GroundNeutralRelease barrier) {
        if(barrier==null||!NativeGroundNeutralCompatibility.isApplied())return false;
        try{return FlightControlReflection.open(level,terminal).normalFrameAfterGroundRelease(body,profile,barrier);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable){return false;}
    }
    private static GroundNeutralSnapshot unavailableGroundNeutral(String reason){return new GroundNeutralSnapshot(false,reason,null,false,-1,-1,0,0);}
    public record ControlOwnership(boolean available, String reason, UUID bodyId, UUID taskId, String fingerprint,
                                   boolean leaseOwned, boolean anyActiveLease, boolean foreignLease) {
        public boolean provesFlight(UUID expectedBody, UUID expectedTask, String expectedFingerprint) {
            return available && expectedBody != null && expectedTask != null
                    && expectedBody.equals(bodyId) && expectedTask.equals(taskId)
                    && expectedFingerprint != null && !expectedFingerprint.isBlank()
                    && expectedFingerprint.equals(fingerprint) && leaseOwned && anyActiveLease && !foreignLease;
        }
        public boolean provesReleased(UUID expectedBody) {
            return available && expectedBody != null && expectedBody.equals(bodyId)
                    && !leaseOwned && !anyActiveLease && !foreignLease;
        }
    }
    public static ControlOwnership inspectControlOwnership(ServerLevel level, BlockPos terminalPos) {
        if (level == null || terminalPos == null || !level.getServer().isSameThread())
            return new ControlOwnership(false, "not_server_thread", null, null, "", false, true, true);
        try { return FlightControlReflection.open(level, terminalPos).controlOwnership(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new ControlOwnership(false, failureReason(failure), null, null, "", false, true, true);
        }
    }
    /** Native available propulsion, not a takeoff-performance or fuel-endurance estimate. */
    public record PowerSnapshot(boolean available, String reason, int linkedThrusters,
                                int poweredThrusters, double totalAvailableThrust) {
        public boolean ready() {
            return available && linkedThrusters > 0 && linkedThrusters <= 128 && poweredThrusters > 0
                    && poweredThrusters <= linkedThrusters && Double.isFinite(totalAvailableThrust)
                    && totalAvailableThrust > 0;
        }
    }
    /** Native speed/reference evidence, independent of the registrar's nominal speed and taxi controls. */
    public record FixedWingPerformanceSnapshot(boolean available, String reason, UUID structureId,
            long estimateGameTick, long sampledGameTick, String referenceStatus, double v1, double vr,
            double landingPitchDegrees, double forwardSpeedLimit, double speedEnvelope) {
        public String failure(UUID expectedBody, long now) {
            if (!available) return reason == null || reason.isBlank() ? "fixed_wing_reference_unavailable" : reason;
            if (expectedBody == null || !expectedBody.equals(structureId)) return "flight_binding_lost";
            if (!"READY".equals(referenceStatus) || estimateGameTick < 0 || now != sampledGameTick
                    || now < estimateGameTick || now - estimateGameTick >= 100)
                return "fixed_wing_reference_unavailable";
            if (!Double.isFinite(v1) || v1 <= 0 || !Double.isFinite(vr) || vr < v1
                    || !Double.isFinite(landingPitchDegrees) || !Double.isFinite(forwardSpeedLimit)
                    || forwardSpeedLimit < 0 || !Double.isFinite(speedEnvelope) || speedEnvelope <= 0)
                return "fixed_wing_speed_invalid";
            return forwardSpeedLimit + 1e-6 < vr ? "fixed_wing_forward_limit_below_vr" : "";
        }
        public double conservativeSpeed() {
            double landingMinimum = 1.05 * vr / Math.sqrt(Math.max(.1, Math.cos(Math.toRadians(landingPitchDegrees))));
            return Math.max(speedEnvelope, Math.max(vr, landingMinimum));
        }
    }
    public static FixedWingPerformanceSnapshot inspectFixedWingPerformance(ServerLevel level, BlockPos terminalPos) {
        try { return FlightControlReflection.open(level, terminalPos).fixedWingPerformanceSnapshot(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new FixedWingPerformanceSnapshot(false, failureReason(failure), null, -1, -1,
                    "UNAVAILABLE", Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
    }
    public static PowerSnapshot inspectPower(ServerLevel level, BlockPos terminalPos) {
        try { return FlightControlReflection.open(level, terminalPos).powerSnapshot(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return new PowerSnapshot(false, failureReason(failure), 0, 0, 0);
        }
    }
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
