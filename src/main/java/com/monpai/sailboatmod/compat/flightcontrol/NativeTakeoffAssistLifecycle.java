package com.monpai.sailboatmod.compat.flightcontrol;

import com.monpai.sailboatmod.transport.air.TakeoffAssistConfig;
import com.monpai.sailboatmod.transport.air.TakeoffAssistSession;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Non-persisted arming history. A loaded RUNNING task is never an arming command. */
final class NativeTakeoffAssistLifecycle {
    private static final int MAX_EPISODES = 64;
    private record Identity(UUID body, UUID task, String profile) { }
    private final Map<Identity, TakeoffAssistSession> sessions = new HashMap<>();
    private final Set<UUID> suppressed = new HashSet<>();
    private TakeoffAssistConfig config = TakeoffAssistConfig.DEFAULT;
    private boolean allowed, explicitArm, readySeen;
    private UUID readyBody, readyPreviousTask, lastTask;
    private String readyProfile = "", reason = "disabled";

    void configure(TakeoffAssistConfig config, boolean allowed) {
        this.config = config == null ? TakeoffAssistConfig.DEFAULT : config;
        this.allowed = allowed;
        sessions.values().forEach(session -> session.updateConfig(allowed ? this.config : TakeoffAssistConfig.DEFAULT));
        if (!this.config.enabled() || !allowed) {
            explicitArm = readySeen = false;
            reason = this.config.enabled() ? "not_allowed" : "disabled";
        }
    }
    void ready(UUID body, String profile) {
        if (!allowed || !config.enabled() || body == null || profile == null || profile.isBlank()) return;
        readyBody = body;
        readyProfile = profile;
        readyPreviousTask = lastTask;
        readySeen = true;
        reason = "armed";
    }
    void arm() {
        if (allowed && config.enabled()) {
            explicitArm = true;
            reason = "armed";
        }
    }
    void suppress(UUID task) {
        UUID current = task == null ? lastTask : task;
        if (current != null && suppressed.size() < MAX_EPISODES) suppressed.add(current);
        sessions.values().forEach(session -> session.updateConfig(TakeoffAssistConfig.DEFAULT));
        explicitArm = readySeen = false;
        reason = "suppressed";
    }
    void expire() {
        sessions.values().forEach(session -> session.updateConfig(TakeoffAssistConfig.DEFAULT));
        explicitArm = readySeen = false;
        reason = "request_expired";
    }
    TakeoffAssistSession.Decision evaluate(TakeoffAssistSession.Frame frame) {
        if (!allowed || !config.enabled()) return noForce(config.enabled() ? "not_allowed" : "disabled");
        if (frame == null || frame.body() == null || frame.task() == null || frame.profile() == null
                || frame.profile().isBlank()) return noForce("waiting_native_task");
        lastTask = frame.task();
        if (suppressed.contains(frame.task())) return noForce("suppressed");
        Identity identity = new Identity(frame.body(), frame.task(), frame.profile());
        if (sessions.keySet().stream().anyMatch(previous -> previous.task().equals(frame.task()) && !previous.equals(identity))) {
            suppress(frame.task());
            return noForce("identity_changed");
        }
        TakeoffAssistSession session = sessions.get(identity);
        if (session == null) {
            boolean readyTransition = readySeen && frame.body().equals(readyBody)
                    && frame.profile().equals(readyProfile) && !frame.task().equals(readyPreviousTask);
            if (!readyTransition && !explicitArm) return noForce("requires_arm");
            if (!frame.fresh() || !frame.authority() || !frame.automaticTakeoff()
                    || !frame.propulsionReady() || !frame.brakesReleased()
                    || frame.contact() == TakeoffAssistSession.Contact.UNKNOWN
                    || frame.contact() == TakeoffAssistSession.Contact.ROLLING) return noForce("waiting_grounded_takeoff");
            if (sessions.size() >= MAX_EPISODES || suppressed.size() >= MAX_EPISODES) return noForce("episode_limit");
            if (frame.contact() == TakeoffAssistSession.Contact.AIRBORNE) {
                // Liftoff confirmation applies only to an existing ground-armed roll.
                // A newly encountered airborne task never gains ground-start authority.
                suppressed.add(frame.task());
                explicitArm = readySeen = false;
                return noForce("airborne");
            }
            session = new TakeoffAssistSession(frame.body(), frame.task(), frame.profile(), config);
            sessions.put(identity, session);
            explicitArm = readySeen = false;
        }
        session.updateConfig(config);
        TakeoffAssistSession.Decision decision = session.evaluate(frame);
        reason = decision.status();
        return decision;
    }
    String status() { return reason; }
    double totalImpulse() { return sessions.values().stream().mapToDouble(TakeoffAssistSession::totalAppliedImpulse).sum(); }
    private TakeoffAssistSession.Decision noForce(String status) {
        reason = status;
        return new TakeoffAssistSession.Decision(0, status);
    }
}
