package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.road.RoadBuilder;
import com.monpai.sailboatmod.road.model.*;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.post.PathPostProcessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class BothOrchestrator {
    private final RoadConfig config;

    public BothOrchestrator(RoadConfig config) {
        this.config = config;
    }

    public record PlanCandidate(String optionId, String label, boolean success, String failureReason,
                                 List<BlockPos> centerPath, List<BridgeSpan> bridgeSpans,
                                 List<BuildStep> buildSteps, RoadData roadData) {}

    public record OrchestratedResult(List<PlanCandidate> candidates) {
        public boolean hasAnySuccess() {
            return candidates.stream().anyMatch(PlanCandidate::success);
        }
    }

    public OrchestratedResult plan(ServerLevel level, BlockPos source, BlockPos target, int width) {
        List<PlanCandidate> candidates = new ArrayList<>();

        // Run detour and bridge in parallel
        CompletableFuture<PlanCandidate> detourFuture = CompletableFuture.supplyAsync(() ->
            planDetour(level, source, target, width));
        CompletableFuture<PlanCandidate> bridgeFuture = CompletableFuture.supplyAsync(() ->
            planBridge(level, source, target, width));

        try {
            CompletableFuture.allOf(detourFuture, bridgeFuture).join();
            PlanCandidate detour = detourFuture.get();
            PlanCandidate bridge = bridgeFuture.get();
            if (detour.success()) candidates.add(detour);
            if (bridge.success()) candidates.add(bridge);
            // If both failed, add them anyway so failure reasons are visible
            if (candidates.isEmpty()) {
                candidates.add(detour);
                candidates.add(bridge);
            }
        } catch (Exception e) {
            candidates.add(new PlanCandidate("detour", "Detour", false, e.getMessage(),
                List.of(), List.of(), List.of(), null));
        }

        return new OrchestratedResult(candidates);
    }

    private PlanCandidate planDetour(ServerLevel level, BlockPos source, BlockPos target, int width) {
        try {
            TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
            Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
            PathResult result = pathfinder.findPath(source, target, cache);

            if (!result.success()) {
                return new PlanCandidate("detour", "Detour", false, result.failureReason(),
                    List.of(), List.of(), List.of(), null);
            }

            PathPostProcessor post = new PathPostProcessor();
            PathPostProcessor.ProcessedPath processed = post.process(
                result.path(), cache, config.getBridge().getBridgeMinWaterDepth());

            // Detour: only allow short-span bridges (<=8 blocks)
            List<BridgeSpan> filteredSpans = new ArrayList<>();
            for (BridgeSpan span : processed.bridgeSpans()) {
                if (span.length() <= 8) {
                    filteredSpans.add(span);
                }
                // >8 spans are NOT bridged in detour mode - they stay as ground path
            }

            RoadBuilder builder = new RoadBuilder(config);
            RoadData roadData = builder.buildRoad("detour", processed.path(), width, cache);

            return new PlanCandidate("detour", "Detour", true, null,
                processed.path(), filteredSpans, roadData.buildSteps(), roadData);
        } catch (Exception e) {
            return new PlanCandidate("detour", "Detour", false, e.getMessage(),
                List.of(), List.of(), List.of(), null);
        }
    }

    private PlanCandidate planBridge(ServerLevel level, BlockPos source, BlockPos target, int width) {
        try {
            BridgePlanner bridgePlanner = new BridgePlanner(config);
            BridgePlanner.BridgePlanResult result = bridgePlanner.plan(level, source, target, width);

            if (!result.success()) {
                return new PlanCandidate("bridge", "Bridge", false, result.failureReason(),
                    List.of(), List.of(), List.of(), null);
            }

            return new PlanCandidate("bridge", "Bridge", true, null,
                result.centerPath(), result.bridgeSpans(), result.buildSteps(), result.roadData());
        } catch (Exception e) {
            return new PlanCandidate("bridge", "Bridge", false, e.getMessage(),
                List.of(), List.of(), List.of(), null);
        }
    }
}