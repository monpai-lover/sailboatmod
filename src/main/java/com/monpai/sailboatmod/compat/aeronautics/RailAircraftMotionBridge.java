package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Exclusive, body-scoped artificial route motion. No Flight Control class is required or loaded. */
public final class RailAircraftMotionBridge {
    public record Lease(UUID owner, UUID physicalId, long generation) { }
    public record Attitude(double pitchRadians,double bankRadians,double headingRadians) { }
    public record Result(boolean accepted, String reason, Lease lease) {
        static Result rejected(String reason) { return new Result(false, reason, null); }
    }
    private static final RailAircraftMotionState STATE = new RailAircraftMotionState();
    private static final RailAircraftConstraintTracker CONSTRAINTS = new RailAircraftConstraintTracker();
    private static final Logger LOGGER = LoggerFactory.getLogger(RailAircraftMotionBridge.class);
    private static final AtomicBoolean WARNED = new AtomicBoolean();
    private static volatile RailAircraftReflectionAccess access;
    private static volatile boolean attempted;
    private RailAircraftMotionBridge() { }

    public static boolean available(BlockEntity controller) {
        if (!loadedController(controller)) return false;
        RailAircraftReflectionAccess api = api();
        if (api == null || !RailAircraftMotionCompatibility.applied()) return false;
        try { return api.body(controller) != null && api.system((ServerLevel) controller.getLevel()) != null; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { warn(failure); return false; }
    }

    /** Current native angular speed in radians/second; also readable before a motion lease exists. */
    public static OptionalDouble angularSpeed(BlockEntity controller) {
        if (!loadedController(controller)) return OptionalDouble.empty();
        RailAircraftReflectionAccess api = api();
        if (api == null) return OptionalDouble.empty();
        try {
            Object body = api.body(controller);
            return body == null ? OptionalDouble.empty() : OptionalDouble.of(api.angularSpeed(body));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure); return OptionalDouble.empty();
        }
    }

    /** Reads every loaded FCC actor on this body; an absent Flight Control installation needs no class lookup. */
    public static NativeControlResult foreignFlightControlStatus(BlockEntity controller) {
        if (!loadedController(controller)) return NativeControlResult.failure("air_rail_controller_not_loaded");
        RailAircraftReflectionAccess api = api();
        if (api == null) return NativeControlResult.failure("air_rail_native_abi_unavailable");
        try {
            Object body = api.body(controller);
            return body == null ? NativeControlResult.failure("air_rail_body_mismatch")
                    : api.foreignFlightControlStatus(body, controller.getLevel());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            warn(unavailable); return NativeControlResult.failure("flight_control_state_unavailable");
        }
    }

    public static Result acquire(BlockEntity controller, UUID owner, UUID bodyId, Direction localForward) {
        if (!loadedController(controller)) return Result.rejected("air_rail_controller_not_loaded");
        RailAircraftReflectionAccess api = api();
        if (api == null || !RailAircraftMotionCompatibility.applied()) return Result.rejected("air_rail_native_abi_unavailable");
        try {
            ServerLevel level = (ServerLevel) controller.getLevel();
            Object body = api.body(controller);
            if (body == null || !api.id(body).equals(bodyId)) return Result.rejected("air_rail_body_mismatch");
            Object pipeline = api.pipeline(api.system(level));
            String topology = CONSTRAINTS.reason(pipeline, body, api::constraintValid);
            if (!topology.isEmpty()) return Result.rejected(topology);
            var previous = STATE.existing(bodyId);
            boolean predecessorLive = previous != null && (loadedController((BlockEntity) previous.controller)
                    || previous.body != body && api.live(previous.body, level));
            String foreignReason;
            try {
                var foreign=api.foreignFlightControlStatus(body,level);
                foreignReason=foreign.success()?"":foreign.reason();
                if(!foreign.success()&&(foreignReason==null||foreignReason.isBlank()))foreignReason="flight_control_state_unavailable";
            } catch(ReflectiveOperationException|RuntimeException|LinkageError unavailable) {
                warn(unavailable);foreignReason="flight_control_state_unavailable";
            }
            var actual = api.frame(body, Vec3.atCenterOf(controller.getBlockPos()));
            var result = STATE.acquire(controller, level, body, owner, bodyId, localForward, actual, predecessorLive,foreignReason);
            if (result.accepted()) STATE.session(result.token()).nativePipeline = pipeline;
            return new Result(result.accepted(), result.reason(), lease(result.token()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure); return Result.rejected("air_rail_native_binding_failed");
        }
    }

    /** Accepts one next-game-tick target; movement is applied and acknowledged separately. */
    public static NativeControlResult move(BlockEntity controller, Lease lease, Vec3 desiredAnchor,
                                           Vec3 desiredTangent, double bankRadians) {
        if (!loadedController(controller)) return NativeControlResult.failure("air_rail_controller_not_loaded");
        RailAircraftReflectionAccess api = api();
        if (api == null) return NativeControlResult.failure("air_rail_native_abi_unavailable");
        try {
            Object body = api.body(controller);
            if (body == null) return NativeControlResult.failure("air_rail_body_mismatch");
            var s = STATE.session(token(lease));
            if (!STATE.owns(controller, controller.getLevel(), body, token(lease)))
                return NativeControlResult.failure("air_rail_stale_lease");
            if (s != null && failed(s))
                return NativeControlResult.failure(s.reason);
            String topology = CONSTRAINTS.reason(api.pipeline(api.system((ServerLevel) controller.getLevel())), body,
                    api::constraintValid);
            if (!topology.isEmpty()) {
                STATE.failHolding(s, topology);
                return NativeControlResult.failure(topology);
            }
            return STATE.move(controller, controller.getLevel(), body, token(lease), desiredAnchor,
                    desiredTangent, bankRadians, controller.getLevel().getGameTime());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure); return NativeControlResult.failure("air_rail_native_binding_failed");
        }
    }

    public static NativeControlResult hold(BlockEntity controller, Lease lease) {
        var s = STATE.session(token(lease));
        if (!serverThread(controller) || s == null || s.controller != controller || s.world != controller.getLevel())
            return NativeControlResult.failure("air_rail_stale_lease");
        return STATE.hold(controller, s.world, s.body, s.token);
    }

    /** Pure lease/fault read for pending-pose acknowledgments; does not load or query native classes. */
    public static NativeControlResult status(BlockEntity controller, Lease lease) {
        if (!serverThread(controller)) return NativeControlResult.failure("air_rail_stale_lease");
        return STATE.status(controller, controller.getLevel(), token(lease));
    }

    public static boolean owns(BlockEntity controller, Lease lease) {
        if (!loadedController(controller)) return false;
        RailAircraftReflectionAccess api = api();
        if (api == null) return false;
        try { return STATE.owns(controller, controller.getLevel(), api.body(controller), token(lease)); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { warn(failure); return false; }
    }

    /** Read-only authority check for explicitly bound auxiliaries on this exact controlled aircraft. */
    public static boolean ownedBy(BlockEntity controller,UUID owner,UUID physicalId) {
        if(!loadedController(controller)||owner==null||physicalId==null)return false;
        var session=STATE.existing(physicalId);
        if(session==null||!owner.equals(session.token.owner()))return false;
        RailAircraftReflectionAccess api=api();if(api==null)return false;
        try{return STATE.owns(controller,controller.getLevel(),api.body(controller),session.token);}
        catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(failure);return false;}
    }

    /** Native current-pose observation for the visual surface controller; it never reads a queued target. */
    public static Optional<Attitude> attitude(BlockEntity controller,Direction localForward) {
        if(!loadedController(controller)||localForward==null||!localForward.getAxis().isHorizontal())return Optional.empty();
        RailAircraftReflectionAccess api=api();if(api==null)return Optional.empty();
        try {
            Object body=api.body(controller);if(body==null)return Optional.empty();
            var frame=api.frame(body,Vec3.atCenterOf(controller.getBlockPos()));
            double[] read=RailAircraftMotionState.attitude(localForward,frame.orientation());
            return Optional.of(new Attitude(read[0],read[1],read[2]));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(failure);return Optional.empty();}
    }

    /** Reads the current native pose, including roll. A queued target alone can never acknowledge itself. */
    public static boolean matchesTarget(BlockEntity controller, Lease lease, Vec3 anchor, Vec3 tangent,
                                         double bankRadians, double positionTolerance, double angularToleranceRadians) {
        if (!loadedController(controller) || !Double.isFinite(positionTolerance) || positionTolerance < 0
                || positionTolerance > 1000 || !Double.isFinite(angularToleranceRadians)
                || angularToleranceRadians < 0 || angularToleranceRadians > Math.PI
                || !RailAircraftMotionState.finite(anchor)) return false;
        RailAircraftReflectionAccess api = api();
        if (api == null) return false;
        try {
            Object body = api.body(controller);
            if (!STATE.owns(controller, controller.getLevel(), body, token(lease))) return false;
            var s = STATE.session(token(lease));
            if (failed(s)) return false;
            var actual = api.frame(body, Vec3.atCenterOf(controller.getBlockPos()));
            return RailAircraftMotionState.matches(actual, s.forward, anchor, tangent, bankRadians,
                    positionTolerance, angularToleranceRadians);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { warn(failure); return false; }
    }

    /** Uses the actual native attitude to include wings and tail throughout the next interpolated move. */
    public static Optional<AABB> sweptBounds(BlockEntity controller,Lease lease,AABB currentBounds,
                                             Vec3 targetAnchor,Vec3 tangent,double bankRadians) {
        if(!loadedController(controller))return Optional.empty();
        RailAircraftReflectionAccess api=api();if(api==null)return Optional.empty();
        try {
            Object body=api.body(controller);var session=STATE.session(token(lease));
            if(session==null||failed(session)||!STATE.owns(controller,controller.getLevel(),body,token(lease)))return Optional.empty();
            var actual=api.frame(body,Vec3.atCenterOf(controller.getBlockPos()));
            var target=new RailAircraftMotionState.Frame(targetAnchor,RailAircraftMotionState.orientation(session.forward,tangent,bankRadians));
            return Optional.of(RailAircraftMotionState.rotationSweep(actual,currentBounds,target));
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){warn(failure);return Optional.empty();}
    }

    /** Water only: disprove broad hits using the same body and start pose as the authorized movement. */
    public static Optional<WaterHullSweep> waterHullSweep(BlockEntity controller, UUID owner, UUID physicalId,
                                                        AABB currentBounds, Vec3 targetAnchor, Vec3 tangent) {
        if (!loadedController(controller) || owner == null || physicalId == null) return Optional.empty();
        RailAircraftReflectionAccess api = api();
        var session = STATE.existing(physicalId);
        if (api == null || session == null || failed(session) || !owner.equals(session.token.owner()))
            return Optional.empty();
        try {
            Object body = api.body(controller);
            if (!STATE.owns(controller, controller.getLevel(), body, session.token)) return Optional.empty();
            Vec3 localAnchor = Vec3.atCenterOf(controller.getBlockPos());
            var actual = api.frame(body, localAnchor);
            var start = session.current;
            if (Math.abs(actual.orientation().normalize().dot(start.orientation().normalize())) < 1 - 1e-10)
                return Optional.empty();
            var target = RailAircraftMotionState.orientation(session.forward, tangent, 0);
            return WaterHullSweep.create(api.localHullOffsets(body, localAnchor), actual.anchor(), start.anchor(),
                    start.orientation(), targetAnchor, target, currentBounds);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
            return Optional.empty();
        }
    }

    /** Explicit manual/stop/removal release; unloading or a missed heartbeat does not call this. */
    public static NativeControlResult release(BlockEntity controller, Lease lease) {
        var s = STATE.session(token(lease));
        if (!serverThread(controller) || s == null || s.controller != controller || s.world != controller.getLevel())
            return NativeControlResult.failure("air_rail_stale_lease");
        try {
            RailAircraftReflectionAccess api = access;
            if (api != null) api.release(api.system((ServerLevel) s.world), s.body);
            STATE.release(controller, s.token);
            return NativeControlResult.applied();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            STATE.failHolding(s, "air_rail_native_motion_failed_release");
            warn(failure); return NativeControlResult.failure("air_rail_native_motion_failed_release");
        }
    }

    /** Called only by the optional body mixin; constant-time identity lookup, no optional class loading. */
    public static boolean controlsNativeBody(Object body) { return STATE.controls(body); }

    /** Optional native hooks observe topology/lifecycle only; foreign bodies are never moved. */
    public static void nativeConstraintAdded(Object pipeline, Object first, Object second, Object handle) {
        CONSTRAINTS.add(pipeline, first, second, handle);
        if (handle == null) return;
        var a = STATE.controlled(first);
        var b = STATE.controlled(second);
        String reason = first == null || second == null
                ? "air_rail_world_constraint_unsupported" : "air_rail_multi_body_unsupported";
        if (a != null) STATE.failHolding(a, reason);
        if (b != null) STATE.failHolding(b, reason);
    }
    public static void nativeConstraintRemoved(Object handle) { CONSTRAINTS.removed(handle); }
    public static void nativeBodyRemoved(Object pipeline, Object body) {
        CONSTRAINTS.bodyRemoved(pipeline, body);
        var s = STATE.controlled(body);
        if (s != null && s.nativePipeline == pipeline) STATE.release(s.controller, s.token);
    }
    public static void nativePipelineDisposed(Object pipeline) {
        CONSTRAINTS.disposed(pipeline); STATE.pipelineDisposed(pipeline);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static synchronized RailAircraftReflectionAccess api() {
        if (attempted) return access;
        ModList mods = ModList.get();
        if (mods == null || !mods.isLoaded("sable")) return null;
        try {
            ClassLoader loader = RailAircraftMotionBridge.class.getClassLoader();
            RailAircraftReflectionAccess bound = RailAircraftReflectionAccess.bind(name -> Class.forName(name, false, loader));
            if (!RailAircraftMotionCompatibility.applied()) return null;
            Consumer<Object> post = event -> postPhysics(bound, event);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, (Class) bound.postEvent, (Consumer) post);
            NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> { STATE.clear(); CONSTRAINTS.clear(); });
            access = bound;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { warn(failure); }
        attempted = true;
        return access;
    }

    private static void postPhysics(RailAircraftReflectionAccess api, Object event) {
        try {
            Object system = api.eventSystem(event);
            ServerLevel level = api.level(system);
            if (!level.getServer().isSameThread()) return;
            double dt = api.eventStep(event);
            for (var s : STATE.in(level)) {
                try {
                    if (!api.live(s.body, level)) { STATE.release(s.controller, s.token); continue; }
                    String topology = CONSTRAINTS.reason(api.pipeline(system), s.body, api::constraintValid);
                    if (!topology.isEmpty()) STATE.failHolding(s, topology);
                    // Missing controller updates finish only the accepted 1/20-second segment, then hold indefinitely.
                    var target = STATE.advance(s, dt);
                    BlockEntity controller = (BlockEntity) s.controller;
                    if (!loadedController(controller)) STATE.detached(s);
                    api.apply(system, s.body, Vec3.atCenterOf(controller.getBlockPos()), target);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                    STATE.failHolding(s, "air_rail_native_motion_failed"); warn(failure);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { warn(failure); }
    }

    private static boolean serverThread(BlockEntity c) {
        return c != null && c.getLevel() instanceof ServerLevel level && level.getServer() != null
                && level.getServer().isSameThread();
    }
    private static boolean failed(RailAircraftMotionState.Session s) {
        return RailAircraftMotionState.failed(s);
    }
    private static boolean loadedController(BlockEntity c) {
        if (!serverThread(c) || c.isRemoved()) return false;
        ServerLevel level = (ServerLevel) c.getLevel();
        var chunk = level.getChunkSource().getChunkNow(c.getBlockPos().getX() >> 4, c.getBlockPos().getZ() >> 4);
        return chunk != null && chunk.getBlockEntities().get(c.getBlockPos()) == c;
    }
    private static RailAircraftMotionState.Token token(Lease lease) {
        return lease == null ? null : new RailAircraftMotionState.Token(lease.owner(), lease.physicalId(), lease.generation());
    }
    private static Lease lease(RailAircraftMotionState.Token token) {
        return token == null ? null : new Lease(token.owner(), token.physicalId(), token.generation());
    }
    private static void warn(Throwable failure) {
        if (WARNED.compareAndSet(false, true)) LOGGER.warn("[AirRail] Native route motion is unavailable: {}", failure.toString());
    }
}
