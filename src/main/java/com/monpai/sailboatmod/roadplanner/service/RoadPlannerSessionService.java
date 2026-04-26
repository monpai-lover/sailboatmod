package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.roadplanner.model.RoadPlan;
import com.monpai.sailboatmod.roadplanner.model.RoadPlanningSession;
import com.monpai.sailboatmod.roadplanner.model.RoadSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RoadPlannerSessionService {
    private final ConcurrentMap<UUID, RoadPlanningSession> sessionsByPlayer = new ConcurrentHashMap<>();

    public RoadPlanningSession startSession(UUID playerId,
                                            ResourceKey<Level> dimension,
                                            BlockPos startPos,
                                            BlockPos destinationPos) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(startPos, "startPos");
        Objects.requireNonNull(destinationPos, "destinationPos");

        RoadPlan plan = new RoadPlan(UUID.randomUUID(), startPos, destinationPos, List.of(), RoadSettings.defaults());
        RoadPlanningSession session = new RoadPlanningSession(
                UUID.randomUUID(),
                playerId,
                dimension,
                startPos,
                destinationPos,
                0,
                plan);
        sessionsByPlayer.put(playerId, session);
        return session;
    }

    public Optional<RoadPlanningSession> getSession(UUID playerId) {
        return Optional.ofNullable(sessionsByPlayer.get(playerId));
    }

    public Optional<RoadPlanningSession> setDestination(UUID playerId, BlockPos destinationPos) {
        Objects.requireNonNull(destinationPos, "destinationPos");
        return update(playerId, session -> {
            RoadPlan oldPlan = session.plan();
            RoadPlan newPlan = new RoadPlan(
                    oldPlan.planId(),
                    oldPlan.start(),
                    destinationPos,
                    oldPlan.segments(),
                    oldPlan.settings());
            return copySession(session, destinationPos, session.activeRegionIndex(), newPlan);
        });
    }

    public Optional<RoadPlanningSession> replacePlan(UUID playerId, RoadPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return update(playerId, session -> copySession(session, plan.destination(), session.activeRegionIndex(), plan));
    }

    private Optional<RoadPlanningSession> update(UUID playerId, SessionUpdater updater) {
        Objects.requireNonNull(playerId, "playerId");
        RoadPlanningSession updated = sessionsByPlayer.computeIfPresent(playerId, (ignored, session) -> updater.update(session));
        return Optional.ofNullable(updated);
    }

    private RoadPlanningSession copySession(RoadPlanningSession session,
                                            BlockPos destinationPos,
                                            int activeRegionIndex,
                                            RoadPlan plan) {
        return new RoadPlanningSession(
                session.sessionId(),
                session.playerId(),
                session.dimension(),
                session.startPos(),
                destinationPos,
                activeRegionIndex,
                plan);
    }

    @FunctionalInterface
    private interface SessionUpdater {
        RoadPlanningSession update(RoadPlanningSession session);
    }
}
