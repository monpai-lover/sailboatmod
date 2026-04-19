package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.construction.road.RoadBuilder;
import com.monpai.sailboatmod.road.generation.RoadGenerationService;
import com.monpai.sailboatmod.road.generation.RoadGenerationTask;
import com.monpai.sailboatmod.road.model.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.road.pathfinding.post.PathPostProcessor;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

public class RoadPlanningService {
    private final RoadConfig config;
    private final RoadGenerationService generationService;
    private final RoadBuilder roadBuilder;
    private final Map<String, ConstructionQueue> activeConstructions = new LinkedHashMap<>();

    public RoadPlanningService(RoadConfig config) {
        this.config = config;
        this.generationService = new RoadGenerationService(config);
        this.roadBuilder = new RoadBuilder(config);
    }

    public String planRoad(StructureConnection connection) {
        return generationService.submit(connection);
    }

    public void tick(ServerLevel level) {
        generationService.tick(level);

        for (RoadGenerationTask task : generationService.pollCompleted()) {
            if (task.getResult() != null && task.getResult().success()) {
                TerrainSamplingCache cache = new TerrainSamplingCache(level,
                    config.getPathfinding().getSamplingPrecision());
                PathPostProcessor postProcessor = new PathPostProcessor();
                PathPostProcessor.ProcessedPath processed = postProcessor.process(
                    task.getResult().path(), cache, config.getBridge().getBridgeMinWaterDepth());

                int width = config.getAppearance().getDefaultWidth();
                RoadData roadData = roadBuilder.buildRoad(
                    task.getTaskId(), processed.path(), width, cache);

                ConstructionQueue queue = new ConstructionQueue(task.getTaskId(), roadData.buildSteps());
                activeConstructions.put(task.getTaskId(), queue);
            }
        }

        Iterator<Map.Entry<String, ConstructionQueue>> it = activeConstructions.entrySet().iterator();
        while (it.hasNext()) {
            ConstructionQueue queue = it.next().getValue();
            if (queue.getState() == ConstructionQueue.State.COMPLETED
                    || queue.getState() == ConstructionQueue.State.CANCELLED) {
                it.remove();
            }
        }
    }

    public Optional<ConstructionQueue> getConstruction(String roadId) {
        return Optional.ofNullable(activeConstructions.get(roadId));
    }

    public Map<String, ConstructionQueue> getActiveConstructions() {
        return Collections.unmodifiableMap(activeConstructions);
    }

    public void cancelRoad(String roadId, ServerLevel level) {
        ConstructionQueue queue = activeConstructions.get(roadId);
        if (queue != null) {
            queue.cancel();
            queue.rollback(level);
        }
        generationService.cancelTask(roadId);
    }

    public void shutdown() {
        generationService.shutdown();
    }
}
