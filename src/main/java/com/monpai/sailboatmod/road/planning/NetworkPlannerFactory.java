package com.monpai.sailboatmod.road.planning;

import com.monpai.sailboatmod.road.planning.impl.*;

public final class NetworkPlannerFactory {
    public enum PlanningAlgorithm { DELAUNAY, MST, KNN }

    private NetworkPlannerFactory() {}

    public static NetworkPlanner create(PlanningAlgorithm algorithm) {
        return switch (algorithm) {
            case DELAUNAY -> new DelaunayPlanner();
            case MST -> new MSTPlanner();
            case KNN -> new KNNPlanner();
        };
    }
}
