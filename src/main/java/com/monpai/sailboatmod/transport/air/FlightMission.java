package com.monpai.sailboatmod.transport.air;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** A journal of authority, never a second inventory. All distances are blocks, speeds blocks/s. */
public final class FlightMission {
    public enum Representation { PHYSICAL, QUIESCING, VIRTUAL, MATERIALIZING }
    private final UUID id;
    private final String dimension;
    private final BlockPos controller;
    private final AircraftClass aircraftClass;
    private final List<String> traces;
    private final List<Vec3> route;
    private final double cruiseSpeed;
    private Representation representation = Representation.PHYSICAL;
    private long generation;
    private String storageRef = "", phase = "PREFLIGHT", reason = "";
    private Vec3 position;
    private int segment;
    private double distance;
    private boolean active = true;

    private FlightMission(UUID id, String dimension, BlockPos controller, AircraftClass type,
                          List<String> traces, List<Vec3> route, double speed) {
        if (id == null || dimension == null || dimension.isBlank() || controller == null || type == null
                || route == null || route.size() < 2 || route.size() > 4096
                || route.stream().anyMatch(p -> p == null || !finite(p))
                || !Double.isFinite(speed) || speed <= 0 || speed > 100) throw new IllegalArgumentException("invalid_flight_mission");
        this.id = id; this.dimension = dimension; this.controller = controller.immutable();
        this.aircraftClass = type; this.traces = List.copyOf(traces); this.route = List.copyOf(route);
        this.cruiseSpeed = speed; position = route.getFirst();
    }
    public static FlightMission start(UUID id, String dimension, BlockPos controller, AircraftClass type,
                                      List<String> traces, List<Vec3> route, double speed) {
        return new FlightMission(id, dimension, controller, type, traces, route, speed);
    }
    private static boolean finite(Vec3 p) { return Double.isFinite(p.x) && Double.isFinite(p.y) && Double.isFinite(p.z); }
    public UUID id() { return id; }
    public String dimension() { return dimension; }
    public BlockPos controller() { return controller; }
    public AircraftClass aircraftClass() { return aircraftClass; }
    public Vec3 position() { return position; }
    public String phase() { return phase; }
    public String reason() { return reason; }
    public Representation representation() { return representation; }
    public boolean active() { return active; }
    public long generation() { return generation; }
    public String storageRef() { return storageRef; }
    public List<Vec3> route() { return route; }
    public List<String> traceIds() { return traces; }
    public double cruiseSpeed() { return cruiseSpeed; }
    public boolean ownsTrace(String trace) { return traces.contains(trace) || ("manual-" + id).equals(trace); }
    public boolean hasExclusiveStorage() { return active && !storageRef.isBlank()
            && (representation == Representation.VIRTUAL || representation == Representation.MATERIALIZING); }
    public double progress() { double total = 0; for (int i=1;i<route.size();i++) total += route.get(i-1).distanceTo(route.get(i));
        return total > 0 ? Math.min(1, distance / total) : 0; }
    public void physicalObservation(Vec3 at, int currentSegment, String observedPhase, String blockedReason) {
        if (representation != Representation.PHYSICAL || !finite(at)) return;
        position = at; segment = Math.max(0, Math.min(route.size()-2, currentSegment));
        distance = 0; for (int i=1;i<=segment;i++) distance += route.get(i-1).distanceTo(route.get(i));
        distance += Math.min(route.get(segment).distanceTo(at), route.get(segment).distanceTo(route.get(segment+1)));
        phase = observedPhase == null || observedPhase.isBlank() ? "UNKNOWN" : observedPhase;
        reason = blockedReason == null ? "" : blockedReason;
    }
    public long beginHandoff() {
        if (!active || representation != Representation.PHYSICAL) return -1;
        representation = Representation.QUIESCING; phase = "QUIESCING"; return ++generation;
    }
    public boolean confirmStored(long expected, String custody) {
        if (!active || representation != Representation.QUIESCING || expected != generation || custody == null || custody.isBlank()) return false;
        storageRef = custody; representation = Representation.VIRTUAL; phase = "CRUISING"; return true;
    }
    public boolean abortHandoff(long expected, String why) {
        if (representation != Representation.QUIESCING || expected != generation) return false;
        representation = Representation.PHYSICAL; phase = "CRUISING"; reason = why; return true;
    }
    /** Caller must debit fuel through the custody backend before this is called; no wall-clock catch-up. */
    public boolean advanceVirtual(int elapsedTicks, double authorizedSpeed) {
        if (!hasExclusiveStorage() || representation != Representation.VIRTUAL || elapsedTicks <= 0
                || !Double.isFinite(authorizedSpeed) || authorizedSpeed <= 0) return false;
        double remaining = Math.min(elapsedTicks, 20) / 20.0 * Math.min(cruiseSpeed, authorizedSpeed);
        // Leave the last two waypoints for real approach/landing, never settle in virtual space.
        int recoveryPoint = Math.max(1, route.size() - 3);
        while (remaining > 0 && segment < recoveryPoint) {
            Vec3 target = route.get(segment+1); double toTarget = position.distanceTo(target);
            if (toTarget <= remaining) { position = target; segment++; distance += toTarget; remaining -= toTarget; }
            else { position = position.add(target.subtract(position).scale(remaining/toTarget)); distance += remaining; remaining = 0; }
        }
        if (segment >= recoveryPoint) { representation = Representation.MATERIALIZING; phase = "AWAITING_RECOVERY"; }
        return true;
    }
    public boolean requestRecovery(long expected) {
        if (!hasExclusiveStorage() || representation != Representation.VIRTUAL || expected != generation) return false;
        representation = Representation.MATERIALIZING; phase = "AWAITING_RECOVERY"; return true;
    }
    public boolean confirmRestored(long expected) {
        if (representation != Representation.MATERIALIZING || expected != generation || storageRef.isBlank()) return false;
        storageRef = ""; representation = Representation.PHYSICAL; phase = "APPROACH"; return true;
    }
    public boolean finishAtAirport(boolean physicallyParked) {
        if (!active || representation != Representation.PHYSICAL || !physicallyParked) return false;
        active = false; phase = "PARKED"; return true;
    }
    public void cancelPhysical() { if (representation == Representation.PHYSICAL) { active = false; phase = "CANCELLED"; } }
    public void requireRecovery(String why) { phase = "RECOVERY_REQUIRED"; reason = why == null ? "" : why; }
    public CompoundTag save() {
        CompoundTag t = new CompoundTag(); t.putUUID("Id", id); t.putString("Dimension", dimension);
        t.putLong("Controller", controller.asLong()); t.putString("AircraftClass", aircraftClass.name());
        t.putString("Representation", representation.name()); t.putLong("Generation", generation);
        t.putString("StorageRef", storageRef); t.putString("Phase", phase); t.putString("Reason", reason);
        t.putBoolean("Active", active); t.putDouble("Speed", cruiseSpeed); t.putDouble("Distance", distance); t.putInt("Segment", segment);
        t.put("Position", point(position)); ListTag points = new ListTag(); route.forEach(p -> points.add(point(p))); t.put("Route", points);
        ListTag ids = new ListTag(); traces.forEach(v -> ids.add(StringTag.valueOf(v))); t.put("Traces", ids); return t;
    }
    private static CompoundTag point(Vec3 p) { CompoundTag t = new CompoundTag(); t.putDouble("X",p.x);t.putDouble("Y",p.y);t.putDouble("Z",p.z);return t; }
    private static Vec3 point(CompoundTag t) { return new Vec3(t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z")); }
    public static FlightMission load(CompoundTag t) {
        List<Vec3> route = new ArrayList<>(); for (Tag raw : t.getList("Route",Tag.TAG_COMPOUND)) route.add(point((CompoundTag)raw));
        List<String> traces = new ArrayList<>(); for (Tag raw : t.getList("Traces",Tag.TAG_STRING)) traces.add(raw.getAsString());
        FlightMission m = start(t.getUUID("Id"),t.getString("Dimension"),BlockPos.of(t.getLong("Controller")),
                AircraftClass.valueOf(t.getString("AircraftClass")),traces,route,t.getDouble("Speed"));
        m.representation = Representation.valueOf(t.getString("Representation")); m.generation = Math.max(0,t.getLong("Generation"));
        m.storageRef = t.getString("StorageRef"); m.phase = t.getString("Phase"); m.reason=t.getString("Reason"); m.active=t.getBoolean("Active");
        m.position = point(t.getCompound("Position")); m.segment=Math.max(0,Math.min(route.size()-2,t.getInt("Segment")));
        m.distance=Math.max(0,t.getDouble("Distance"));
        if (!finite(m.position) || !Double.isFinite(m.distance)
                || ((m.representation==Representation.VIRTUAL || m.representation==Representation.MATERIALIZING) && m.storageRef.isBlank()))
            throw new IllegalArgumentException("invalid_flight_custody");
        return m;
    }
}
