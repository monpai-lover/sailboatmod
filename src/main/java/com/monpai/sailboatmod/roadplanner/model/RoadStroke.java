package com.monpai.sailboatmod.roadplanner.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RoadStroke(UUID strokeId,
                         RoadToolType tool,
                         List<RoadNode> nodes,
                         RoadStrokeSettings settings) {
    public RoadStroke {
        strokeId = Objects.requireNonNull(strokeId, "strokeId");
        tool = tool == null ? RoadToolType.ROAD : tool;
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        settings = settings == null ? RoadStrokeSettings.defaults() : settings;
    }
}
