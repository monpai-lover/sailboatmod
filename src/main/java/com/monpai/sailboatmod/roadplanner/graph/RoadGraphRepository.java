package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class RoadGraphRepository {
    private final RoadNetworkGraphSavedData data;
    private final RoadGraphSpatialIndex spatialIndex = new RoadGraphSpatialIndex();
    private boolean indexDirty = true;

    public RoadGraphRepository(RoadNetworkGraphSavedData data) {
        this.data = data == null ? new RoadNetworkGraphSavedData() : data;
    }

    public static RoadGraphRepository forLevel(ServerLevel level) {
        return new RoadGraphRepository(RoadNetworkGraphSavedData.get(level));
    }

    public void putNode(RoadGraphNodeRecord node) {
        data.putNode(node);
        indexDirty = true;
    }

    public void putEdge(RoadGraphEdgeRecord edge) {
        data.putEdge(edge);
        indexDirty = true;
    }

    public Optional<RoadGraphNodeRecord> node(UUID nodeId) {
        return data.getNode(nodeId);
    }

    public Optional<RoadGraphEdgeRecord> edge(UUID edgeId) {
        return data.getEdge(edgeId);
    }

    public Collection<RoadGraphNodeRecord> nodes() {
        return data.nodes();
    }

    public Collection<RoadGraphEdgeRecord> edges() {
        return data.edges();
    }

    public List<RoadGraphEdgeRecord> edgesForDimension(String dimensionId) {
        return data.edgesForDimension(dimensionId).stream().toList();
    }

    public RoadGraphSpatialIndex spatialIndex() {
        if (indexDirty) {
            spatialIndex.rebuild(data.edges().stream().toList());
            indexDirty = false;
        }
        return spatialIndex;
    }

    public boolean removeEdge(UUID edgeId) {
        boolean removed = data.removeEdge(edgeId);
        if (removed) {
            indexDirty = true;
        }
        return removed;
    }
}
