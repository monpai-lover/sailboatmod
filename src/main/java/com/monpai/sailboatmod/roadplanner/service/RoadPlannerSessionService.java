package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.roadplanner.model.RoadPlan;
import com.monpai.sailboatmod.roadplanner.model.RoadPlanningSession;
import com.monpai.sailboatmod.roadplanner.model.RoadSegment;
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
    private static final RoadPlannerSessionService GLOBAL = new RoadPlannerSessionService();
    private final ConcurrentMap<UUID, RoadPlanningSession> sessionsByPlayer = new ConcurrentHashMap<>();

    public static RoadPlannerSessionService global() {
        return GLOBAL;
    }

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

    public Optional<RoadPlanningSession> nextRegion(UUID playerId, int regionSize) {
        return update(playerId, session -> navigateRelative(session, Math.max(16, regionSize), 1));
    }

    public Optional<RoadPlanningSession> previousRegion(UUID playerId, int regionSize) {
        return update(playerId, session -> navigateRelative(session, Math.max(16, regionSize), -1));
    }

    private RoadPlanningSession navigateRelative(RoadPlanningSession session, int regionSize, int delta) {
        int targetIndex = Math.max(0, session.activeRegionIndex() + delta);
        RoadPlan plan = session.plan();
        if (targetIndex < plan.segments().size()) {
            return copySession(session, session.destinationPos(), targetIndex, plan);
        }

        BlockPos entry = lastExitOrStart(plan);
        BlockPos center = nextRegionCenter(entry, plan.destination(), regionSize);
        RoadSegment newSegment = new RoadSegment(targetIndex, center, entry, null, List.of(), false);
        List<RoadSegment> segments = new java.util.ArrayList<>(plan.segments());
        segments.add(newSegment);
        RoadPlan newPlan = new RoadPlan(plan.planId(), plan.start(), plan.destination(), segments, plan.settings());
        return copySession(session, session.destinationPos(), targetIndex, newPlan);
    }

    private BlockPos lastExitOrStart(RoadPlan plan) {
        if (plan.segments().isEmpty()) {
            return plan.start();
        }
        RoadSegment last = plan.segments().get(plan.segments().size() - 1);
        if (last.exitPoint() != null) {
            return last.exitPoint();
        }
        if (!plan.nodesInOrder().isEmpty()) {
            return plan.nodesInOrder().get(plan.nodesInOrder().size() - 1).pos();
        }
        return plan.start();
    }

    private BlockPos nextRegionCenter(BlockPos from, BlockPos destination, int regionSize) {
        int dx = Integer.compare(destination.getX() - from.getX(), 0);
        int dz = Integer.compare(destination.getZ() - from.getZ(), 0);
        if (dx == 0 && dz == 0) {
            dx = 1;
        }
        return new BlockPos(from.getX() + dx * regionSize, from.getY(), from.getZ() + dz * regionSize);
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
