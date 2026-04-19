package com.monpai.sailboatmod.road.pathfinding;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.impl.*;

public final class PathfinderFactory {
    private PathfinderFactory() {}

    public static Pathfinder create(PathfindingConfig config) {
        return switch (config.getAlgorithm()) {
            case BASIC_ASTAR -> new BasicAStarPathfinder(config);
            case BIDIRECTIONAL_ASTAR -> new BidirectionalAStarPathfinder(config);
            case GRADIENT_DESCENT -> new GradientDescentPathfinder(config);
            case POTENTIAL_FIELD -> new PotentialFieldPathfinder(config);
        };
    }
}
