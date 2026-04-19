package com.monpai.sailboatmod.road.api;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.model.StructureConnection;
import com.monpai.sailboatmod.road.planning.NetworkPlannerFactory;
import com.monpai.sailboatmod.road.planning.NetworkPlanner;
import com.monpai.sailboatmod.road.planning.RoadPlanningService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public class RoadNetworkApi {
    private final RoadConfig config;
    private final RoadPlanningService planningService;

    public RoadNetworkApi(RoadConfig config) {
        this.config = config;
        this.planningService = new RoadPlanningService(config);
    }

    public List<StructureConnection> planNetwork(List<BlockPos> structurePositions,
                                                   int maxEdgeLength,
                                                   NetworkPlannerFactory.PlanningAlgorithm algorithm) {
        NetworkPlanner planner = NetworkPlannerFactory.create(algorithm);
        return planner.plan(structurePositions, maxEdgeLength);
    }

    public String buildRoad(StructureConnection connection) {
        return planningService.planRoad(connection);
    }

    public void tick(ServerLevel level) {
        planningService.tick(level);
    }

    public Optional<ConstructionQueue> getConstruction(String roadId) {
        return planningService.getConstruction(roadId);
    }

    public Map<String, ConstructionQueue> getActiveConstructions() {
        return planningService.getActiveConstructions();
    }

    public void cancelRoad(String roadId, ServerLevel level) {
        planningService.cancelRoad(roadId, level);
    }

    public void shutdown() {
        planningService.shutdown();
    }
}
