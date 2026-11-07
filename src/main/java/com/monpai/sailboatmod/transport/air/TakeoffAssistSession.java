package com.monpai.sailboatmod.transport.air;

import java.util.UUID;
import java.util.Objects;

/** One non-persisted automatic takeoff episode; decisions use physical impulses. */
public final class TakeoffAssistSession {
    public enum Contact { GROUNDED, ROLLING, AIRBORNE, UNKNOWN }
    public record Frame(UUID body, UUID task, String profile, double physicsTime, double dt,
            double mass, double horizontalSpeed, double rotationSpeed, double commandedSpeed, Contact contact,
            boolean automaticTakeoff, boolean authority, boolean propulsionReady,
            boolean brakesReleased, boolean fresh) { }
    public record Decision(double impulse, String status) { }

    private static final double MAX_DURATION = 20;
    private final UUID body;
    private final UUID task;
    private final String profile;
    private TakeoffAssistConfig config;
    private double lastPhysicsTime = Double.NEGATIVE_INFINITY;
    private double firstPhysicsTime = Double.NaN;
    private double duration;
    private double totalAppliedImpulse;
    private boolean ended;
    private String status = "armed";

    public TakeoffAssistSession(UUID body, UUID task, String profile, TakeoffAssistConfig config) {
        this.body = body;
        this.task = task;
        this.profile = profile;
        this.config = Objects.requireNonNullElse(config, TakeoffAssistConfig.DEFAULT);
        if (body == null || task == null || profile == null || profile.isBlank()) end("invalid_identity");
        else if (!this.config.enabled()) end("disabled");
    }

    public Decision evaluate(Frame frame) {
        if (ended) return new Decision(0, status);
        if (!config.enabled()) return end("disabled");
        // Corrupt observations cannot consume a real frame or create a duration budget.
        if (!validFrame(frame)) return waitFor("invalid_frame");
        if (!body.equals(frame.body()) || !task.equals(frame.task()) || !profile.equals(frame.profile()))
            return end("identity_changed");
        if (!frame.automaticTakeoff()) return end("not_taking_off");
        if (!frame.authority()) return end("authority_lost");
        if (!frame.propulsionReady()) return end("propulsion_lost");
        if (!frame.fresh()) return waitFor("stale_frame");
        if (frame.physicsTime() <= lastPhysicsTime) return waitFor("replayed_frame");

        double observedAdvance = frame.physicsTime() - lastPhysicsTime;
        double physicalDt = Double.isFinite(lastPhysicsTime)
                ? Math.min(frame.dt(), observedAdvance) : frame.dt();
        // Consume before returning any decision: an exception in the native sink cannot replay this frame.
        lastPhysicsTime = frame.physicsTime();
        if (Double.isNaN(firstPhysicsTime)) firstPhysicsTime = frame.physicsTime() - frame.dt();
        duration = frame.physicsTime() - firstPhysicsTime;
        if (duration >= MAX_DURATION) return end("duration_limit");
        if (frame.contact() == Contact.AIRBORNE) {
            // Zero wheel support permits no impulse, but does not prove native takeoff
            // handoff. Keep this same ground-armed episode and its original deadline;
            // automaticTakeoff/authority loss above still close it permanently.
            return waitFor("airborne_pending");
        }
        if (frame.contact() != Contact.GROUNDED && frame.contact() != Contact.ROLLING) return waitFor("contact_unknown");
        if (!frame.brakesReleased()) return waitFor("brakes_applied");
        if (config.acceleration() <= 0) return waitFor("zero_strength");

        // VR is the rotation reference, not an upper limit on speed while still supported
        // by the wheels. Let this airframe's native takeoff ramp request the speed needed
        // for liftoff, without accelerating ahead of that command.
        double speedHeadroom = frame.commandedSpeed() - frame.horizontalSpeed();
        if (!(speedHeadroom > 0)) return waitFor("speed_limit");
        double deltaV = Math.min(config.acceleration() * physicalDt, speedHeadroom);
        double impulse = frame.mass() * deltaV;
        if (!Double.isFinite(impulse) || !(impulse > 0)
                || !Double.isFinite(totalAppliedImpulse + impulse)) return end("impulse_limit");
        totalAppliedImpulse += impulse;
        status = "assisting";
        return new Decision(impulse, status);
    }

    public void updateConfig(TakeoffAssistConfig config) {
        this.config = Objects.requireNonNullElse(config, TakeoffAssistConfig.DEFAULT);
        if (!this.config.enabled() && !ended) end("disabled");
    }

    public double totalAppliedImpulse() { return totalAppliedImpulse; }
    public double duration() { return duration; }
    public String status() { return status; }

    private Decision waitFor(String reason) {
        status = reason;
        return new Decision(0, status);
    }

    private Decision end(String reason) {
        ended = true;
        status = reason;
        return new Decision(0, status);
    }

    private static boolean validFrame(Frame frame) {
        return frame != null && Double.isFinite(frame.physicsTime()) && frame.physicsTime() >= 0
                && Double.isFinite(frame.dt()) && frame.dt() > 0 && frame.dt() <= .05
                && Double.isFinite(frame.mass()) && frame.mass() > 0
                && Double.isFinite(frame.horizontalSpeed()) && frame.horizontalSpeed() >= 0
                && Double.isFinite(frame.rotationSpeed()) && frame.rotationSpeed() > 0
                && Double.isFinite(frame.commandedSpeed()) && frame.commandedSpeed() >= 0;
    }
}
