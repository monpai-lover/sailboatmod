package com.monpai.sailboatmod.roadplanner.model;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;
import java.util.UUID;

public record RoadPlanningSession(UUID sessionId,
                                  UUID playerId,
                                  ResourceKey<Level> dimension,
                                  BlockPos startPos,
                                  BlockPos destinationPos,
                                  int activeRegionIndex,
                                  RoadPlan plan) {
    public RoadPlanningSession {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        playerId = Objects.requireNonNull(playerId, "playerId");
        dimension = Objects.requireNonNull(dimension, "dimension");
        startPos = Objects.requireNonNull(startPos, "startPos").immutable();
        destinationPos = Objects.requireNonNull(destinationPos, "destinationPos").immutable();
        if (activeRegionIndex < 0) {
            throw new IllegalArgumentException("activeRegionIndex must be non-negative");
        }
    }
}
