package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.Objects;

/** Same-invocation evidence only; this policy never certifies axial authority. */
final class TrimEnvironmentFrame {
    record State(Object snapshot, Object model, Object bodyId, Object structureId,
                 double physicsTime, double clock, double qx, double qy, double qz, double qw,
                 long topology, long session, long revision, Object executionVehicle,
                 boolean active, boolean fresh, boolean thrust, boolean execution, Object environmentContext) {
        State(Object snapshot, Object model, Object bodyId, Object structureId,
              double physicsTime, double clock, double qx, double qy, double qz, double qw,
              long topology, long session, long revision, Object executionVehicle,
              boolean active, boolean fresh, boolean thrust, boolean execution) {
            this(snapshot, model, bodyId, structureId, physicsTime, clock, qx, qy, qz, qw,
                    topology, session, revision, executionVehicle, active, fresh, thrust, execution, null);
        }
        State withEnvironmentContext(Object context) {
            return new State(snapshot, model, bodyId, structureId, physicsTime, clock, qx, qy, qz, qw,
                    topology, session, revision, executionVehicle, active, fresh, thrust, execution, context);
        }
    }
    record Entry(Object computer, Object body, Object handle, State before, double dt) { }

    static boolean permits(Entry entry, Object computer, Object body, Object handle, State state,
                           Object vehicle, long topology, long session, long revision) {
        if (entry == null || state == null || entry.before() == null) return false;
        State before = entry.before();
        double dt = entry.dt();
        double norm = state.qx()*state.qx() + state.qy()*state.qy()
                + state.qz()*state.qz() + state.qw()*state.qw();
        return entry.computer() == computer && entry.body() == body && entry.handle() == handle
                && computer != null && body != null && handle != null
                && state.snapshot() != null && state.snapshot() != before.snapshot()
                && state.snapshot() == vehicle && state.executionVehicle() == vehicle
                && state.model() != null && state.model() != before.model()
                && state.bodyId() != null && Objects.equals(before.bodyId(), state.bodyId())
                && Objects.equals(state.bodyId(), state.structureId())
                && Double.isFinite(dt) && dt > 0 && dt <= .25
                && Double.isFinite(before.clock()) && before.clock() >= 0
                && Double.isFinite(state.physicsTime()) && state.physicsTime() >= 0
                && Double.compare(state.physicsTime(), state.clock()) == 0
                && Double.compare(state.physicsTime(), before.clock() + dt) == 0
                && Double.isFinite(norm) && Math.abs(norm - 1) <= 1e-6
                && before.topology() == topology && state.topology() == topology
                && before.session() == session && state.session() == session
                && before.revision() == revision && state.revision() == revision
                && state.active() && state.fresh() && state.thrust() && state.execution();
    }
}
