package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.road.model.BuildStep;

import java.util.List;
import java.util.Set;

public record BridgeEmissionResult(List<BuildStep> steps, Set<Long> transitionColumns) {
    public BridgeEmissionResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        transitionColumns = transitionColumns == null ? Set.of() : Set.copyOf(transitionColumns);
    }

    public static BridgeEmissionResult empty() {
        return new BridgeEmissionResult(List.of(), Set.of());
    }
}
