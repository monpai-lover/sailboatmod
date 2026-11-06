package com.monpai.sailboatmod.compat.aeronautics;

import com.monpai.sailboatmod.transport.physical.NativeControlResult;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Per-bearing finite-angle state. It never changes the kinetic network. */
public final class RailAircraftSurfaceServo {
    static final String TAG = "SailboatAirRailSurface";
    private static final AtomicLong GENERATIONS = new AtomicLong();
    record Token(UUID owner, UUID bodyId, long generation) { }
    record Acquisition(boolean accepted, String reason, Token token) { }
    public record View(double actualAngleDegrees, double targetAngleDegrees, double rateDegreesPerSecond,
                       boolean active, UUID owner, UUID physicalId, String reason) { }
    private boolean locked, bound, neutralHold, invalid;
    private UUID owner, bodyId;
    private long generation, controllerKey, lastCommandTick;
    private double target, neutral, rate = 60;
    private String reason = "surface_unbound";
    private WeakReference<Object> controller = new WeakReference<>(null), world = new WeakReference<>(null), body = new WeakReference<>(null);

    synchronized Acquisition claim(Object controller, Object world, Object body, UUID owner, UUID bodyId, long controllerKey,
                      double current, double neutral, long tick, boolean predecessorLive) {
        if (invalid) return rejected("surface_invalid_saved_state");
        if (controller == null || world == null || body == null || owner == null || bodyId == null
                || !Double.isFinite(current) || !Double.isFinite(neutral) || tick < 0) return rejected("surface_invalid_binding");
        neutral = normalize(neutral);
        if (locked) {
            if (!owner.equals(this.owner) || !bodyId.equals(this.bodyId)) return rejected("surface_owned");
            if (controllerKey != this.controllerKey || Math.abs(normalize(neutral - this.neutral)) > 1e-9)
                return rejected("surface_controller_mismatch");
            if (bound && this.controller.get() == controller && this.world.get() == world && this.body.get() == body)
                return new Acquisition(true, "", token());
            if (predecessorLive) return rejected("surface_controller_still_loaded");
        }
        long next = nextGeneration(generation);
        if (next == 0) return rejected("surface_generation_exhausted");
        this.owner = owner; this.bodyId = bodyId; this.controllerKey = controllerKey; this.neutral = neutral;
        this.controller = new WeakReference<>(controller); this.world = new WeakReference<>(world); this.body = new WeakReference<>(body);
        generation = next; locked = bound = true; neutralHold = false; target = normalize(current); lastCommandTick = tick;
        reason = "surface_holding";
        return new Acquisition(true, "", token());
    }
    synchronized NativeControlResult target(Object c, Object w, Object b, Token t, double a, double r, long tick) {
        if (!owns(c, w, b, t)) return NativeControlResult.failure("surface_stale_lease");
        if (!Double.isFinite(a) || Math.abs(normalize(a - neutral)) > 45 || !rate(r) || tick < lastCommandTick)
            return NativeControlResult.failure("surface_invalid_target");
        target = normalize(a); rate = r; lastCommandTick = tick; neutralHold = false; reason = "surface_tracking";
        return NativeControlResult.applied();
    }
    synchronized NativeControlResult hold(Object c, Object w, Object b, Token t, double actual, long tick) {
        if (!owns(c, w, b, t) || !Double.isFinite(actual)) return NativeControlResult.failure("surface_stale_lease");
        target = normalize(actual); neutralHold = true; lastCommandTick = tick; reason = "surface_holding";
        return NativeControlResult.applied();
    }
    synchronized NativeControlResult neutral(Object c, Object w, Object b, Token t) {
        if (!owns(c, w, b, t)) return NativeControlResult.failure("surface_stale_lease");
        target = neutral; neutralHold = true; reason = "surface_neutral_hold"; return NativeControlResult.applied();
    }
    synchronized boolean owns(Object c, Object w, Object b, Token t) {
        return locked && bound && !invalid && t != null && t.equals(token()) && controller.get() == c
                && world.get() == w && body.get() == b;
    }
    synchronized boolean clear(Object c, Object w, Object b, Token t) {
        if (!owns(c, w, b, t)) return false;
        locked = bound = neutralHold = invalid = false; owner = bodyId = null;
        controller.clear(); world.clear(); body.clear(); reason = "surface_unbound";
        return true;
    }
    synchronized boolean locked() { return locked; }
    synchronized Object controller() { return controller.get(); }
    synchronized Object world() { return world.get(); }
    synchronized Object body() { return body.get(); }
    synchronized Token token() { return new Token(owner, bodyId, generation); }
    synchronized boolean bound() { return bound; }
    synchronized boolean returningToNeutral() { return neutralHold && reason.equals("surface_neutral_hold"); }
    /** Returns true only when the current command was replaced by a finite safety hold. */
    synchronized boolean beforeTick(long tick, double actual, boolean healthy) {
        if (!locked || invalid) return false;
        if (!Double.isFinite(actual)) {
            invalid = true; bound = false; target = 0; reason = "surface_invalid_actual_angle"; return true;
        }
        if (bound && !healthy) {
            bound = false; target = normalize(actual); neutralHold = true; reason = "surface_owner_unavailable"; return true;
        }
        if (bound && !neutralHold && (tick < lastCommandTick || tick - lastCommandTick > 2)
                && !reason.equals("surface_command_stale")) {
            target = normalize(actual); reason = "surface_command_stale"; return true;
        }
        return false;
    }
    /** Degrees per game tick. Repeated rendering/readback calls never advance state. */
    public synchronized double step(double actual) {
        if (!locked || !Double.isFinite(actual) || invalid) return 0;
        double error = normalize(target - normalize(actual));
        return Math.abs(error) <= .01 ? 0 : Math.max(-rate / 20, Math.min(rate / 20, error));
    }
    public synchronized View view(double actual) {
        return new View(Double.isFinite(actual) ? normalize(actual) : Double.NaN, target, rate, locked, owner, bodyId, reason);
    }
    public synchronized void write(CompoundTag root) {
        if (!locked) { root.remove(TAG); return; }
        CompoundTag saved = new CompoundTag(); saved.putBoolean("Locked", true);
        if (owner != null) saved.putUUID("Owner", owner);
        if (bodyId != null) saved.putUUID("Body", bodyId);
        saved.putLong("Generation", generation); saved.putLong("Controller", controllerKey);
        saved.putDouble("Target", target); saved.putDouble("Neutral", neutral); saved.putDouble("Rate", rate);
        root.put(TAG, saved);
    }
    public synchronized void read(CompoundTag root, boolean client, double current) {
        controller.clear(); world.clear(); body.clear(); bound = false; neutralHold = true;
        if (!root.contains(TAG)) { locked = invalid = false; owner = bodyId = null; reason = "surface_unbound"; return; }
        locked = true;
        CompoundTag saved = root.getCompound(TAG);
        boolean valid = root.contains(TAG, Tag.TAG_COMPOUND) && saved.getBoolean("Locked") && saved.hasUUID("Owner")
                && saved.hasUUID("Body") && saved.getLong("Generation") > 0 && Double.isFinite(saved.getDouble("Neutral"))
                && Math.abs(saved.getDouble("Neutral")) <= 180
                && Double.isFinite(saved.getDouble("Target")) && Math.abs(saved.getDouble("Target")) <= 180
                && rate(saved.getDouble("Rate")) && Double.isFinite(current);
        if (!valid) {
            invalid = true; target = Double.isFinite(current) ? normalize(current) : 0;
            reason = "surface_invalid_saved_state"; return;
        }
        invalid = false; owner = saved.getUUID("Owner"); bodyId = saved.getUUID("Body");
        generation = saved.getLong("Generation"); controllerKey = saved.getLong("Controller");
        neutral = saved.getDouble("Neutral"); rate = saved.getDouble("Rate");
        target = client ? saved.getDouble("Target") : normalize(current);
        reason = client ? "surface_client_tracking" : "surface_awaiting_rebind";
    }
    static double normalize(double angle) { double v = angle % 360; return v >= 180 ? v - 360 : v < -180 ? v + 360 : v; }
    private static long nextGeneration(long persistedFloor) {
        for (;;) {
            long observed = GENERATIONS.get(), floor = Math.max(observed, persistedFloor);
            if (floor == Long.MAX_VALUE) return 0;
            long next = floor + 1;
            if (GENERATIONS.compareAndSet(observed, next)) return next;
        }
    }
    private static boolean rate(double value) { return Double.isFinite(value) && value > 0 && value <= 180; }
    private static Acquisition rejected(String reason) { return new Acquisition(false, reason, null); }
}
