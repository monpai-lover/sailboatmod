package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.transport.air.TakeoffAssistConfig;
import com.monpai.sailboatmod.transport.air.TakeoffAssistSession;
import com.monpai.sailboatmod.transport.air.UnloadedWheelContactPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Optional, explicitly armed physical acceleration during a real native takeoff. */
public final class NativeTakeoffAssist {
    private static final long LEASE_NANOS = 400_000_000L;
    private static final int MAX_REQUESTS = 128;
    private static final Map<BlockEntity, Request> REQUESTS = new IdentityHashMap<>();
    private static boolean registered, listening;
    private static Method eventSystem, eventStep, systemLevel;
    public record Diagnostics(String status, String reason, UUID body, UUID task, String profile,
            double physicsTime, double mass, double rotationSpeed, int configuredWheels,
            int positiveWheels, double lastImpulse, double totalImpulse, double commandedSpeed) { }
    private static final class Request {
        final NativeTakeoffAssistLifecycle lifecycle = new NativeTakeoffAssistLifecycle();
        NativeTakeoffAssistReflection.Binding binding;
        NativeTakeoffAssistReflection access;
        NativeTakeoffAssistReflection.Observation last;
        TakeoffAssistConfig config = TakeoffAssistConfig.DEFAULT;
        long renewed;
        boolean allowed, expired;
        double groundedAltitude = Double.NaN, lastImpulse, totalImpulse;
        long unloadedWheelForcesSuppressed;
        UUID groundedTask;
        String reason = "disabled";
    }
    private NativeTakeoffAssist() { }
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> clear());
        try {
            ClassLoader loader = NativeTakeoffAssist.class.getClassLoader();
            Class<?> event = Class.forName("dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent", false, loader);
            if (!Event.class.isAssignableFrom(event)) return;
            eventSystem = event.getMethod("getPhysicsSystem");
            eventStep = event.getMethod("getTimeStep");
            systemLevel = eventSystem.getReturnType().getMethod("getLevel");
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, (Class) event, (Consumer<Event>) NativeTakeoffAssist::physics);
            listening = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError absent) {
            listening = false;
        }
    }

    public static synchronized void update(BlockEntity requester, UUID registrationId, BlockEntity terminal,
            List<BlockPos> wheelOffsets, TakeoffAssistConfig config, boolean allowed) {
        if (!serverThread(requester)) return;
        TakeoffAssistConfig settings = config == null ? TakeoffAssistConfig.DEFAULT : config;
        Request request = REQUESTS.get(requester);
        if (request == null) {
            if (!settings.enabled() || REQUESTS.size() >= MAX_REQUESTS) return;
            request = new Request();
            REQUESTS.put(requester, request);
        }
        if (request.binding != null && (!Objects.equals(request.binding.registration(), registrationId)
                || request.binding.terminal() != terminal || !Objects.equals(request.binding.wheels(), wheelOffsets))) {
            request.lifecycle.expire();
            request.groundedTask = null;
            request.groundedAltitude = Double.NaN;
        }
        request.binding = new NativeTakeoffAssistReflection.Binding(requester, registrationId, terminal,
                wheelOffsets == null ? List.of() : List.copyOf(wheelOffsets));
        request.config = settings;
        request.allowed = allowed;
        request.renewed = System.nanoTime();
        request.expired = false;
        request.lastImpulse = 0;
        request.lifecycle.configure(settings, allowed);
        if (!settings.enabled() || !allowed) {
            request.reason = settings.enabled() ? "not_allowed" : "disabled";
            return;
        }
        if (!listening) { fail(request,"native_physics_callback_unavailable"); return; }
        try {
            if (request.access == null) request.access = new NativeTakeoffAssistReflection(terminal.getClass().getClassLoader());
            Request current = request;
            // This read can witness READY, but never evaluates/consumes a physics frame or applies an impulse.
            request.access.observe(request.binding, null, 0, state -> {
                current.last = state;
                if (state.ready() && state.contact() == TakeoffAssistSession.Contact.GROUNDED) {
                    current.lifecycle.ready(state.body(), state.profile());
                    current.reason = current.lifecycle.status();
                } else if ("disabled".equals(current.reason)) current.reason = "requires_arm";
            });
        } catch (Exception | LinkageError unavailable) { fail(request, failure(unavailable)); }
    }

    /** Called only after an explicit owner off-to-on action; saved enabled state cannot call this. */
    public static synchronized void arm(BlockEntity requester) {
        Request request = REQUESTS.get(requester);
        if (!serverThread(requester) || request == null || !request.config.enabled() || !request.allowed || request.access == null) return;
        try {
            request.access.observe(request.binding, null, 0, state -> {
                boolean grounded = state.contact() == TakeoffAssistSession.Contact.GROUNDED;
                if (grounded && state.fresh() && state.propulsion() && state.brakes()
                        && ((state.authority() && state.takeoff()) || state.ready())) {
                    request.lifecycle.arm();
                    request.reason = "armed";
                } else request.reason = "waiting_grounded_takeoff";
            });
        } catch (Exception | LinkageError unavailable) { fail(request, failure(unavailable)); }
    }

    /** Owner PAUSE/STOP suppresses the native task UUID, including later config off/on for that task. */
    public static synchronized void suppress(BlockEntity requester) {
        Request request = REQUESTS.get(requester);
        if (request == null || !serverThread(requester)) return;
        UUID task = request.last == null ? null : request.last.task();
        // Read only the current task identity; suppression must work even when other native evidence is unavailable.
        try {
            Object raw = NativeTakeoffAssistReflection.field(request.binding.terminal(),"taskSnapshot");
            task = (UUID) NativeTakeoffAssistReflection.field(raw,"taskId");
        } catch (ReflectiveOperationException | RuntimeException absent) { }
        request.lifecycle.suppress(task);
        request.reason = "suppressed";
        request.lastImpulse = 0;
    }
    public static synchronized void remove(BlockEntity requester) { REQUESTS.remove(requester); }
    private static synchronized void clear() { REQUESTS.clear(); }

    public static synchronized String status(BlockEntity requester) { return diagnostics(requester).status(); }
    public static synchronized long unloadedWheelForcesSuppressed(BlockEntity requester) {
        Request request = REQUESTS.get(requester);
        return request == null ? 0 : request.unloadedWheelForcesSuppressed;
    }

    /** No new force: omit only an unloaded wheel's native downward/drag impulse at its actual call site. */
    public static synchronized boolean ignoreUnloadedWheelForce(BlockEntity wheel, Object body,
            double extension, double verticalImpulse, double dt) {
        if (!AircraftWheelContactCompatibility.isApplied() || !serverThread(wheel)) return false;
        long now = System.nanoTime();
        Request match = null;
        for (Request request : REQUESTS.values()) {
            var last = request.last;
            if (!request.allowed || !request.config.enabled() || request.expired || request.access == null
                    || now-request.renewed < 0 || now-request.renewed > LEASE_NANOS
                    || last == null || !last.fresh() || !last.authority() || !last.takeoff()
                    || last.task() == null || request.binding.requester().getLevel() != wheel.getLevel()) continue;
            try {
                if (!request.access.configuredWheel(request.binding,wheel,body,last.body())
                        || !UnloadedWheelContactPolicy.ignore(extension,request.access.wheelRadius(wheel),
                                verticalImpulse,request.access.upright(body),dt)
                        || !request.access.staticFloorStillUnloaded(wheel,body,extension)) continue;
                var ownership = FlightControlBridge.inspectControlOwnership((ServerLevel)wheel.getLevel(),
                        request.binding.terminal().getBlockPos());
                if (!ownership.provesFlight(last.body(),last.task(),last.profile())) continue;
                // Ambiguous duplicate registrars cannot opt a body into a physics correction.
                if (match != null) return false;
                match = request;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return false;
            }
        }
        if (match == null) return false;
        match.unloadedWheelForcesSuppressed++;
        return true;
    }
    /** Returns stored scalar evidence only. Reading diagnostics never observes native state or applies force. */
    public static synchronized Diagnostics diagnostics(BlockEntity requester) {
        Request request = REQUESTS.get(requester);
        if (request == null) return new Diagnostics("off","disabled",null,null,"",-1,0,0,0,0,0,0,0);
        var last = request.last;
        String reason = request.reason;
        if (System.nanoTime()-request.renewed > LEASE_NANOS) reason = "request_expired";
        return new Diagnostics(uiStatus(request.config.enabled(),reason),reason,
                last == null ? null : last.body(), last == null ? null : last.task(), last == null ? "" : last.profile(),
                last == null ? -1 : last.physicsTime(),last == null ? 0 : last.mass(),last == null ? 0 : last.vr(),
                request.binding == null ? 0 : request.binding.wheels().size(),last == null ? 0 : last.positiveWheels(),
                request.lastImpulse,request.totalImpulse,last == null ? 0 : last.commandedSpeed());
    }

    private static synchronized void physics(Event event) {
        try {
            Object system = eventSystem.invoke(event);
            Object eventLevel = systemLevel.invoke(system);
            if (!(eventLevel instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
            double dt = ((Number)eventStep.invoke(event)).doubleValue();
            long now = System.nanoTime();
            REQUESTS.entrySet().removeIf(entry -> entry.getKey().isRemoved() || entry.getKey().getLevel() == null);
            List<Request> requests = new ArrayList<>(REQUESTS.values());
            for (Request request : requests) {
                if (request.binding.requester().getLevel() != level) continue;
                request.lastImpulse = 0;
                if (now-request.renewed < 0 || now-request.renewed > LEASE_NANOS) {
                    if (!request.expired) request.lifecycle.expire();
                    request.expired = true;
                    request.reason = "request_expired";
                    continue;
                }
                if (!request.allowed || !request.config.enabled() || request.access == null) continue;
                // Two registrars on one real body must not double the requested acceleration.
                UUID body = request.last == null ? null : request.last.body();
                long matching = body == null ? 0 : requests.stream().filter(other -> other.allowed && other.config.enabled()
                        && !other.expired && now-other.renewed <= LEASE_NANOS && other.last != null
                        && body.equals(other.last.body()) && other.binding.requester().getLevel() == level).count();
                if (matching != 1) { fail(request,"duplicate_or_unknown_body_registration"); continue; }
                try { request.access.observe(request.binding,system,dt,state -> apply(request,state)); }
                catch (Exception | LinkageError unavailable) { fail(request,failure(unavailable)); }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            for (Request request : REQUESTS.values()) fail(request,"native_physics_callback_failed");
        }
    }

    private static void apply(Request request, NativeTakeoffAssistReflection.Observation state) {
        request.last = state;
        if (state.ready() && state.contact() == TakeoffAssistSession.Contact.GROUNDED) {
            request.lifecycle.ready(state.body(),state.profile());
            request.reason = request.lifecycle.status();
            return;
        }
        var contact = state.contact();
        if (contact == TakeoffAssistSession.Contact.GROUNDED) {
            request.groundedAltitude = state.altitude();
            request.groundedTask = state.task();
        } else if (contact == TakeoffAssistSession.Contact.AIRBORNE
                && (!Objects.equals(state.task(),request.groundedTask) || !Double.isFinite(request.groundedAltitude)
                    || state.altitude()-request.groundedAltitude < .5 || state.verticalSpeed() <= 0)) {
            // A missing cast or a small wheel bounce is no force, but cannot certify a completed takeoff.
            contact = TakeoffAssistSession.Contact.UNKNOWN;
        }
        var decision = request.lifecycle.evaluate(new TakeoffAssistSession.Frame(state.body(),state.task(),state.profile(),
                state.physicsTime(),state.dt(),state.mass(),state.speed(),state.vr(),state.commandedSpeed(),contact,state.takeoff(),
                state.authority(),state.propulsion(),state.brakes(),state.fresh()));
        request.reason = decision.status();
        if (decision.impulse() <= 0) return;
        try {
            request.access.apply(state,decision.impulse());
            request.lastImpulse = decision.impulse();
            request.totalImpulse += decision.impulse();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failed) { fail(request,"native_impulse_failed"); }
    }
    private static void fail(Request request, String reason) {
        request.lifecycle.expire();
        request.lastImpulse = 0;
        request.reason = reason;
    }
    private static String failure(Throwable failure) {
        Throwable cause = failure;
        for (int depth=0; depth<8 && cause instanceof java.lang.reflect.InvocationTargetException
                && cause.getCause()!=null && cause.getCause()!=cause; depth++) cause=cause.getCause();
        return cause instanceof IllegalStateException && cause.getMessage() != null ? cause.getMessage()
                : "native_observation_unavailable:"+cause.getClass().getSimpleName();
    }
    private static boolean serverThread(BlockEntity requester) {
        return requester != null && !requester.isRemoved() && requester.getLevel() instanceof ServerLevel level
                && level.getServer().isSameThread();
    }
    private static String uiStatus(boolean enabled, String reason) {
        if (!enabled) return "off";
        return switch (reason) {
            case "assisting" -> "active";
            case "armed" -> "armed";
            case "airborne" -> "airborne";
            case "requires_arm", "waiting_native_task", "waiting_grounded_takeoff", "contact_unknown", "airborne_pending",
                    "brakes_applied", "zero_strength", "speed_limit", "stale_frame", "replayed_frame" -> "waiting";
            default -> "blocked";
        };
    }
}
