package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.road.model.BuildStep;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSegmentPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class RoadPlannerBuiltRoadMapRefresh {
    private RoadPlannerBuiltRoadMapRefresh() {
    }

    public static Set<ChunkPos> chunksForBuildSteps(Collection<BuildStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        for (BuildStep step : steps) {
            if (step != null) {
                addChunk(chunks, step.pos());
            }
        }
        return Set.copyOf(chunks);
    }

    public static Set<ChunkPos> chunksForRoadPlan(RoadPlacementPlan plan) {
        if (plan == null) {
            return Set.of();
        }
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        addPositions(chunks, plan.ownedBlocks());
        if (chunks.isEmpty()) {
            addRoadBuildSteps(chunks, plan.buildSteps());
        }
        if (chunks.isEmpty()) {
            addPositions(chunks, plan.centerPath());
        }
        return Set.copyOf(chunks);
    }

    public static Set<ChunkPos> chunksForGraphPlacements(Collection<RoadGraphSegmentPlacement> placements) {
        if (placements == null || placements.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<ChunkPos> chunks = new LinkedHashSet<>();
        for (RoadGraphSegmentPlacement placement : placements) {
            if (placement != null) {
                addChunk(chunks, placement.middlePos());
                addPositions(chunks, placement.positions());
            }
        }
        return Set.copyOf(chunks);
    }

    public static void enqueueBuildStepRefresh(ServerLevel level, Collection<BuildStep> steps) {
        enqueueChunks(level, chunksForBuildSteps(steps));
    }

    public static void enqueueRoadPlanRefresh(ServerLevel level, RoadPlacementPlan plan) {
        enqueueChunks(level, chunksForRoadPlan(plan));
    }

    public static void enqueueGraphPlacementRefresh(ServerLevel level, Collection<RoadGraphSegmentPlacement> placements) {
        enqueueChunks(level, chunksForGraphPlacements(placements));
    }

    public static void enqueueChunks(ServerLevel level, Collection<ChunkPos> chunks) {
        if (level == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        RoadPlannerMapPreloadService.global().enqueueBuiltRoadRefresh(level, chunks);
    }

    private static void addPositions(Set<ChunkPos> chunks, List<BlockPos> positions) {
        if (positions == null || positions.isEmpty()) {
            return;
        }
        for (BlockPos pos : positions) {
            addChunk(chunks, pos);
        }
    }

    private static void addRoadBuildSteps(Set<ChunkPos> chunks, List<RoadGeometryPlanner.RoadBuildStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (RoadGeometryPlanner.RoadBuildStep step : steps) {
            if (step != null) {
                addChunk(chunks, step.pos());
            }
        }
    }

    private static void addChunk(Set<ChunkPos> chunks, BlockPos pos) {
        if (chunks != null && pos != null) {
            chunks.add(new ChunkPos(pos));
        }
    }
}
