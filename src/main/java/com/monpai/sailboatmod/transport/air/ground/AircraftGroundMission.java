package com.monpai.sailboatmod.transport.air.ground;

import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pure fixed-wing ground mission. The caller owns persistence, reservations and native output writes. */
public final class AircraftGroundMission {
    public enum Phase { TAXI_OUT, HOLD_LINE, LINE_UP, FC_STARTING, FC_ACTIVE, WAIT_FC_RELEASE, TAXI_IN, STAND_STABILIZING, COMPLETE, NEEDS_REVIEW }
    public enum Support { CONTACT, AIRBORNE, UNKNOWN }
    public enum Lease { NONE, GROUND, FC, FOREIGN, UNKNOWN }
    public enum Handshake { NONE, ACQUIRE_GROUND, START_FC, RELEASE_FC, RELEASE_GROUND }
    /** Values must come from measured tests of this exact aircraft and control topology. */
    public record Calibration(boolean liveVerified, double brakingDeceleration, double minimumTurnRadius,
                              double maximumSteeringDegrees, int neutralThrottleSignal, int fullThrottleSignal) { }
    public record Plan(UUID missionId, UUID physicalId, String dimensionId, long layoutRevision, String runwayId,
                       String standId, String reservationId, List<Vec3> outbound, int holdPointIndex, List<Vec3> inbound,
                       double runwayYawDegrees, double standYawDegrees, double speedLimit, double corridorHalfWidth,
                       Calibration calibration) {
        public Plan { outbound = List.copyOf(outbound); inbound = List.copyOf(inbound); }
    }
    /** Persist all fields; reconstructed states must be passed through recovered before their first tick. */
    public record State(UUID missionId, UUID physicalId, String dimensionId, long layoutRevision, String runwayId,
                        String standId, String reservationId, Phase phase, int pointIndex, int stableTicks,
                        long lastObservationTick, boolean recovered, int lastThrottleSignal) {
        /** Compatibility constructor: no previous output is assumed for a newly created/resumed state. */
        public State(UUID missionId, UUID physicalId, String dimensionId, long layoutRevision, String runwayId,
                     String standId, String reservationId, Phase phase, int pointIndex, int stableTicks,
                     long lastObservationTick, boolean recovered) {
            this(missionId,physicalId,dimensionId,layoutRevision,runwayId,standId,reservationId,phase,pointIndex,
                    stableTicks,lastObservationTick,recovered,-1);
        }
    }
    /** All booleans are fresh observations/proofs from the caller, never predictions from this controller. */
    public record Observation(UUID physicalId, String dimensionId, long layoutRevision, String reservationId,
                              long tick, long sampledTick, Vec3 position, Vec3 forward, Vec3 velocity, double angularSpeed,
                              Support support, Lease lease, boolean leaseBoundToMission, boolean manualOverride,
                              boolean pathClear, boolean runwayPermit, boolean runwayAligned, boolean groundOutputsNeutral,
                              boolean fcMissionComplete, boolean fcSafeToRelease, boolean fcReleaseConfirmed,
                              boolean wholeHullInStand) { }
    /** Actual calibrated throttle signal; left/right/brake are native redstone levels. */
    public record Outputs(int throttleSignal, int left, int right, int brake, float steeringWheelDegrees) { }
    /** Null outputs means do not write any ground actuator, including hold/brake. */
    public record Action(Outputs outputs, Handshake handshake, boolean cargoReady, String reason) { }
    public record Step(State state, Action action) { }
    private AircraftGroundMission() { }
    public static State departure(Plan p) { return new State(p.missionId(),p.physicalId(),p.dimensionId(),p.layoutRevision(),p.runwayId(),p.standId(),p.reservationId(),Phase.TAXI_OUT,0,0,-1,false); }
    public static State recovered(State s) { return new State(s.missionId(),s.physicalId(),s.dimensionId(),s.layoutRevision(),s.runwayId(),s.standId(),s.reservationId(),s.phase(),s.pointIndex(),0,-1,true); }
    private static final double STOP_SPEED = .25;
    private static final double STOP_ANGULAR_SPEED = .035;
    private static final double STOP_RADIUS = .75;
    private static final int STAND_STABLE_TICKS = 40;

    public static Step step(Plan p, State s, Observation o) { return step(p,s,o,null); }

    /** Legacy boolean callers preserve false-as-blocked behavior. */
    public static Step step(Plan p, State s, Observation o, GroundRecoveryIngress.Proof ingress) {
        return step(p,s,o,ingress,o!=null&&o.runwayAligned()?GroundRunwayReadiness.READY:GroundRunwayReadiness.BLOCKED);
    }

    public static Step step(Plan p, State s, Observation o, GroundRecoveryIngress.Proof ingress, GroundRunwayReadiness readiness) {
        if (s == null || o == null || !validPlan(p)) return result(s, null, Handshake.NONE, false, "invalid_plan_or_observation");
        if (o.manualOverride()) return result(change(s, Phase.NEEDS_REVIEW, s.pointIndex(), 0, o), null,
                o.lease() == Lease.GROUND && o.leaseBoundToMission() ? Handshake.RELEASE_GROUND : Handshake.NONE, false, "manual_control");
        if (!bound(p, s, o)) return review(p, s, o, "binding_changed");
        if (!fresh(s, o) || !validPose(o)) return review(p, s, o, "stale_or_invalid_observation");
        if (o.lease() == Lease.FOREIGN || o.lease() == Lease.UNKNOWN || !o.leaseBoundToMission())
            return review(p, s, o, "control_lease_not_proven");
        if (s.phase() == null || s.pointIndex() < 0 || s.stableTicks() < 0 || !validThrottleLatch(p,s.lastThrottleSignal())) return review(p, s, o, "invalid_saved_state");
        if (s.recovered() && (s.phase() == Phase.COMPLETE || !recoveryProven(s, o)))
            return review(p, s, o, "recovery_requires_review");
        if (s.phase() == Phase.NEEDS_REVIEW) return result(change(s, s.phase(), s.pointIndex(), 0, o), safeStop(p, s, o), Handshake.NONE, false, "requires_review");
        if (s.phase() == Phase.FC_ACTIVE) {
            if (o.lease()==Lease.NONE && o.fcReleaseConfirmed() && o.fcMissionComplete() && o.fcSafeToRelease() && stopped(o))
                return result(change(s, Phase.WAIT_FC_RELEASE, 0, 0, o), null, Handshake.NONE, false, "fc_already_released");
            if (o.lease() != Lease.FC) return review(p, s, o, "fc_lease_lost");
            if (stopped(o) && o.fcMissionComplete() && o.fcSafeToRelease())
                return result(change(s, Phase.WAIT_FC_RELEASE, 0, 0, o), null, Handshake.RELEASE_FC, false, "request_fc_release");
            return result(change(s, Phase.FC_ACTIVE, s.pointIndex(), 0, o), null, Handshake.NONE, false, "fc_active");
        }
        if (s.phase() == Phase.WAIT_FC_RELEASE) {
            if (!stopped(o) || !o.fcMissionComplete() || !o.fcSafeToRelease())
                return result(change(s, s.phase(), s.pointIndex(), 0, o), null, Handshake.NONE, false, "waiting_safe_fc_release");
            if (o.lease() == Lease.FC) return result(change(s, s.phase(), 0, 0, o), null, Handshake.RELEASE_FC, false, "request_fc_release");
            if (!o.fcReleaseConfirmed()) return result(change(s, s.phase(), 0, 0, o), null, Handshake.NONE, false, "waiting_fc_release_proof");
            if (o.lease() == Lease.NONE) return result(change(s, s.phase(), 0, 0, o), null, Handshake.ACQUIRE_GROUND, false, "request_ground_lease");
            return result(change(s, Phase.TAXI_IN, 0, 0, o), stop(p), Handshake.NONE, false, "ground_lease_acquired");
        }
        if (s.phase() == Phase.FC_STARTING && o.lease() == Lease.FC) {
            // A real bound native FC lease is the acknowledgement, never the prior start request.
            return result(change(s, Phase.FC_ACTIVE, s.pointIndex(), 0, o), null, Handshake.NONE, false, "fc_lease_acquired");
        }
        if (o.support() != Support.CONTACT) return review(p, s, o, "ground_contact_not_proven");
        if (o.lease() == Lease.NONE) return result(change(s, s.phase(), s.pointIndex(), 0, o), null,
                Handshake.ACQUIRE_GROUND, false, "request_ground_lease");
        if (o.lease() != Lease.GROUND) return review(p, s, o, "ground_lease_not_proven");
        if (!p.calibration().liveVerified()) return review(p, s, o, "hardware_not_calibrated");
        if (!o.pathClear()) return result(change(s, s.phase(), s.pointIndex(), 0, o), stop(p), Handshake.NONE, false, "path_not_clear");
        return switch (s.phase()) {
            case TAXI_OUT -> taxi(p, s, o, p.outbound(), p.holdPointIndex(), Phase.HOLD_LINE, ingress);
            case HOLD_LINE -> {
                if (!near(o.position(), p.outbound().get(p.holdPointIndex()), STOP_RADIUS) || !stopped(o))
                    yield result(change(s, s.phase(), s.pointIndex(), 0, o), stop(p), Handshake.NONE, false, "holding_short");
                yield result(change(s, o.runwayPermit() ? Phase.LINE_UP : Phase.HOLD_LINE, p.holdPointIndex() + (o.runwayPermit() ? 1 : 0), 0, o),
                        stop(p), Handshake.NONE, false, o.runwayPermit() ? "runway_permitted" : "waiting_runway_permit");
            }
            case LINE_UP -> {
                if (!o.runwayPermit()) yield result(change(s, s.phase(), s.pointIndex(), 0, o), stop(p), Handshake.NONE, false, "runway_permit_lost");
                Step t = taxi(p, s, o, p.outbound(), p.outbound().size()-1, Phase.FC_STARTING);
                if (t.state().phase() == Phase.FC_STARTING) {
                    if (!heading(o.forward(), p.runwayYawDegrees())) yield review(p,s,o,"runway_alignment_not_proven");
                    if (readiness != GroundRunwayReadiness.READY || !o.runwayAligned()) {
                        if (readiness == GroundRunwayReadiness.WAITING)
                            yield result(change(s,Phase.LINE_UP,t.state().pointIndex(),0,o),stop(p),Handshake.NONE,false,"waiting_runway_validation");
                        yield review(p,s,o,"runway_alignment_not_proven");
                    }
                }
                yield t;
            }
            case FC_STARTING -> {
                if (!stopped(o) || !o.runwayPermit() || !heading(o.forward(), p.runwayYawDegrees())
                        || !near(o.position(), p.outbound().getLast(), STOP_RADIUS))
                    yield review(p, s, o, "unsafe_fc_handoff");
                if (readiness != GroundRunwayReadiness.READY || !o.runwayAligned()) {
                    if (readiness == GroundRunwayReadiness.WAITING)
                        yield result(change(s,Phase.LINE_UP,s.pointIndex(),0,o),stop(p),Handshake.NONE,false,"waiting_runway_validation");
                    yield review(p,s,o,"unsafe_fc_handoff");
                }
                // Release every ground output before asking FC to acquire its own native lease.
                yield result(change(s, s.phase(), s.pointIndex(), 0, o), neutral(p),
                        o.groundOutputsNeutral() ? Handshake.START_FC : Handshake.NONE, false,
                        o.groundOutputsNeutral() ? "request_fc_start" : "waiting_ground_neutral_ack");
            }
            case TAXI_IN -> taxi(p, s, o, p.inbound(), p.inbound().size()-1, Phase.STAND_STABILIZING);
            case STAND_STABILIZING, COMPLETE -> stand(p, s, o);
            default -> review(p, s, o, "unsupported_phase");
        };
    }

    private static Step taxi(Plan p, State s, Observation o, List<Vec3> points, int stopIndex, Phase next) { return taxi(p,s,o,points,stopIndex,next,null); }
    private static Step taxi(Plan p, State s, Observation o, List<Vec3> points, int stopIndex, Phase next, GroundRecoveryIngress.Proof ingress) {
        int index = s.pointIndex();
        if (index > stopIndex || stopIndex >= points.size()) return review(p, s, o, "route_index_invalid");
        while (index < stopIndex && passed(o.position(), points, index, p.corridorHalfWidth())) index++;
        Vec3 target = points.get(index);
        if (distanceToRoute(o.position(), points, index) > p.corridorHalfWidth()
                && !GroundRecoveryIngress.allows(ingress,p,s,o.position(),o.forward())) return review(p, s, o, "outside_taxi_corridor");
        if (index == stopIndex && near(o.position(), target, STOP_RADIUS) && stopped(o)) {
            State changed = change(s, next, index, 0, o);
            if (next == Phase.STAND_STABILIZING) return stand(p, changed, o);
            return result(changed, stop(p), Handshake.NONE, false, "stop_point_reached");
        }
        Vec3 delta=horizontal(target.subtract(o.position()));
        if (delta.horizontalDistanceSqr()>STOP_RADIUS*STOP_RADIUS && horizontal(o.forward()).normalize().dot(delta.normalize()) < -.5)
            return review(p,s,o,"taxi_target_behind");
        return result(change(s, s.phase(), index, 0, o), drive(p, s, o, target, index == stopIndex), Handshake.NONE, false, "taxiing");
    }

    private static Step stand(Plan p, State s, Observation o) {
        boolean ready = o.sampledTick()==o.tick() && o.wholeHullInStand() && stopped(o) && heading(o.forward(), p.standYawDegrees())
                && near(o.position(), p.inbound().getLast(), STOP_RADIUS);
        // A missing server tick breaks continuous stability; repeating one sample can never accrue dwell.
        int stable = ready ? (s.lastObservationTick() >= 0 && o.tick() != s.lastObservationTick()+1 ? 1 : s.stableTicks()+1) : 0;
        stable = Math.min(STAND_STABLE_TICKS, stable);
        Phase phase = stable >= STAND_STABLE_TICKS ? Phase.COMPLETE : Phase.STAND_STABILIZING;
        return result(change(s, phase, s.pointIndex(), stable, o), stop(p), Handshake.NONE,
                phase == Phase.COMPLETE, ready ? "stand_stabilizing" : "stand_not_stable");
    }

    private static Outputs drive(Plan p, State s, Observation o, Vec3 target, boolean stopAtTarget) {
        Vec3 delta = target.subtract(o.position());
        double distance = Math.sqrt(delta.horizontalDistanceSqr());
        double speed = Math.sqrt(o.velocity().horizontalDistanceSqr());
        double angle = Math.atan2(o.forward().x*delta.z-o.forward().z*delta.x, o.forward().x*delta.x+o.forward().z*delta.z);
        Calibration c = p.calibration();
        double lookahead = Math.max(1, Math.min(distance, c.minimumTurnRadius()));
        double steering = Math.toDegrees(Math.atan2(2*c.minimumTurnRadius()*Math.sin(angle), lookahead));
        steering = Math.max(-c.maximumSteeringDegrees(), Math.min(c.maximumSteeringDegrees(), steering));
        int turn = Math.min(15, (int)Math.ceil(Math.abs(steering)/c.maximumSteeringDegrees()*15));
        double stoppingDistance = speed*.25+speed*speed/(2*c.brakingDeceleration())+STOP_RADIUS;
        double cornerLimit = p.speedLimit()*Math.max(.15, Math.cos(Math.min(Math.PI/2, Math.abs(angle))));
        boolean brake = speed > cornerLimit || (stopAtTarget && distance <= stoppingDistance);
        // Native transmission signals need not be monotonic: only the two measured endpoints are safe.
        // Retain the prior endpoint between 80% and 100% of the current straight/corner speed limit.
        boolean powered = !brake && speed < cornerLimit
                && (speed < cornerLimit*.8 || s.lastThrottleSignal()==c.fullThrottleSignal());
        int signal = powered ? c.fullThrottleSignal() : c.neutralThrottleSignal();
        return new Outputs(signal, steering < -1 ? turn : 0, steering > 1 ? turn : 0, brake ? 15 : 0, (float)steering);
    }

    private static Step review(Plan p, State s, Observation o, String reason) {
        return result(change(s, Phase.NEEDS_REVIEW, s.pointIndex(), 0, o), safeStop(p, s, o), Handshake.NONE, false, reason);
    }
    private static Outputs safeStop(Plan p, State s, Observation o) {
        return validPlan(p) && validPose(o) && fresh(s,o) && Objects.equals(p.physicalId(),o.physicalId())
                && Objects.equals(p.dimensionId(),o.dimensionId()) && o.support()==Support.CONTACT && o.lease()==Lease.GROUND
                && o.leaseBoundToMission() && !o.manualOverride() && p.calibration().liveVerified() ? stop(p) : null;
    }
    private static Outputs stop(Plan p) { return new Outputs(p.calibration().neutralThrottleSignal(),0,0,15,0); }
    private static Outputs neutral(Plan p) { return new Outputs(p.calibration().neutralThrottleSignal(),0,0,0,0); }
    private static State change(State s, Phase phase, int index, int stable, Observation o) {
        return new State(s.missionId(),s.physicalId(),s.dimensionId(),s.layoutRevision(),s.runwayId(),s.standId(),s.reservationId(),phase,index,stable,o.tick(),false,s.lastThrottleSignal());
    }
    private static Step result(State s, Outputs outputs, Handshake handshake, boolean cargo, String reason) {
        if (s!=null && outputs!=null) s=new State(s.missionId(),s.physicalId(),s.dimensionId(),s.layoutRevision(),s.runwayId(),
                s.standId(),s.reservationId(),s.phase(),s.pointIndex(),s.stableTicks(),s.lastObservationTick(),s.recovered(),outputs.throttleSignal());
        return new Step(s,new Action(outputs,handshake,cargo,reason));
    }
    static boolean validThrottleLatch(Plan p,int signal) {
        return signal==-1 || signal==p.calibration().neutralThrottleSignal() || signal==p.calibration().fullThrottleSignal();
    }
    private static boolean bound(Plan p, State s, Observation o) {
        return Objects.equals(p.missionId(),s.missionId()) && Objects.equals(p.physicalId(),s.physicalId()) && Objects.equals(p.physicalId(),o.physicalId())
                && Objects.equals(p.dimensionId(),s.dimensionId()) && Objects.equals(p.dimensionId(),o.dimensionId())
                && p.layoutRevision()==s.layoutRevision() && p.layoutRevision()==o.layoutRevision() && Objects.equals(p.runwayId(),s.runwayId())
                && Objects.equals(p.standId(),s.standId()) && Objects.equals(p.reservationId(),s.reservationId()) && Objects.equals(p.reservationId(),o.reservationId());
    }
    private static boolean fresh(State s, Observation o) { return o.tick()>=0 && o.sampledTick()>=0 && o.tick()>=o.sampledTick() && o.tick()-o.sampledTick()<=2 && o.tick()>s.lastObservationTick(); }
    private static boolean recoveryProven(State s, Observation o) {
        if (s.phase()==Phase.FC_ACTIVE) return o.leaseBoundToMission() && (o.lease()==Lease.FC
                || (o.lease()==Lease.NONE && o.fcMissionComplete() && o.fcSafeToRelease() && o.fcReleaseConfirmed() && stopped(o)));
        if (s.phase()==Phase.WAIT_FC_RELEASE) return o.leaseBoundToMission() && (o.lease()==Lease.FC || o.fcReleaseConfirmed());
        return o.support()==Support.CONTACT && o.lease()==Lease.GROUND && o.leaseBoundToMission();
    }
    private static boolean stopped(Observation o) { return o.support()==Support.CONTACT && o.velocity().length()<=STOP_SPEED && o.angularSpeed()<=STOP_ANGULAR_SPEED; }
    private static boolean heading(Vec3 forward, double yaw) { return Math.abs(wrap(Math.toDegrees(Math.atan2(-forward.x,forward.z))-yaw))<=5; }
    private static double wrap(double degrees) { return ((degrees+180)%360+360)%360-180; }
    private static boolean near(Vec3 a, Vec3 b, double radius) { return a.subtract(b).horizontalDistanceSqr()<=radius*radius; }
    private static boolean passed(Vec3 position, List<Vec3> points, int index, double corridor) {
        if (near(position,points.get(index),Math.min(1.5,corridor))) return true;
        if (index==0) return position.subtract(points.get(0)).horizontalDistanceSqr() <= corridor*corridor && horizontal(points.get(1).subtract(points.get(0))).dot(horizontal(position.subtract(points.get(0))))>=0;
        Vec3 a=points.get(index-1), b=points.get(index), segment=horizontal(b.subtract(a));
        double t=segment.lengthSqr()>1e-8 ? horizontal(position.subtract(a)).dot(segment)/segment.lengthSqr() : 0;
        return t>=1 && position.subtract(b).horizontalDistanceSqr()<=corridor*corridor;
    }
    private static double distanceToRoute(Vec3 position,List<Vec3> points,int index) {
        if (index==0) return Math.sqrt(position.subtract(points.get(0)).horizontalDistanceSqr());
        Vec3 a=points.get(index-1), segment=horizontal(points.get(index).subtract(a));
        double t=Math.max(0,Math.min(1,horizontal(position.subtract(a)).dot(segment)/segment.lengthSqr()));
        return Math.sqrt(position.subtract(a.add(segment.scale(t))).horizontalDistanceSqr());
    }
    private static boolean validPose(Observation o) { return finite(o.position()) && finite(o.forward()) && finite(o.velocity()) && o.forward().horizontalDistanceSqr()>1e-8 && Double.isFinite(o.angularSpeed()) && o.angularSpeed()>=0 && o.support()!=null && o.lease()!=null; }
    static boolean validPlan(Plan p) {
        if (p==null || p.missionId()==null || p.physicalId()==null || empty(p.dimensionId()) || empty(p.runwayId()) || empty(p.standId()) || empty(p.reservationId()) || p.layoutRevision()<0 || !validPoints(p.outbound()) || !validPoints(p.inbound()) || p.holdPointIndex()<0 || p.holdPointIndex()>=p.outbound().size()-1 || !Double.isFinite(p.runwayYawDegrees()) || !Double.isFinite(p.standYawDegrees()) || !positive(p.speedLimit()) || p.speedLimit()>20 || !positive(p.corridorHalfWidth()) || p.corridorHalfWidth()>128) return false;
        Calibration c=p.calibration(); return c!=null && positive(c.brakingDeceleration()) && positive(c.minimumTurnRadius()) && positive(c.maximumSteeringDegrees()) && c.maximumSteeringDegrees()<=90 && c.neutralThrottleSignal()>=0 && c.neutralThrottleSignal()<=15 && c.fullThrottleSignal()>=0 && c.fullThrottleSignal()<=15 && c.neutralThrottleSignal()!=c.fullThrottleSignal();
    }
    private static boolean validPoints(List<Vec3> points) {
        if(points==null || points.size()<2 || points.size()>256) return false;
        for(int i=0;i<points.size();i++) if(!finite(points.get(i)) || (i>0 && points.get(i).subtract(points.get(i-1)).horizontalDistanceSqr()<1e-8)) return false;
        return true;
    }
    private static Vec3 horizontal(Vec3 value) { return new Vec3(value.x,0,value.z); }
    private static boolean empty(String value) { return value==null || value.isBlank() || value.length()>256; }
    private static boolean positive(double value) { return Double.isFinite(value) && value>0; }
    private static boolean finite(Vec3 value) { return value!=null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z); }
}
