package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RoadNetworkGraphSavedData extends SavedData {
    public static final String DATA_NAME = "sailboatmod_road_graphs";

    private final Map<UUID, RoadGraphNodeRecord> nodes = new LinkedHashMap<>();
    private final Map<UUID, RoadGraphEdgeRecord> edges = new LinkedHashMap<>();

    public static RoadNetworkGraphSavedData get(Level level) {
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() == null) {
            return new RoadNetworkGraphSavedData();
        }
        ServerLevel root = serverLevel.getServer().overworld();
        return root.getDataStorage().computeIfAbsent(
                RoadNetworkGraphSavedData::load,
                RoadNetworkGraphSavedData::new,
                DATA_NAME);
    }

    public static RoadNetworkGraphSavedData load(CompoundTag tag) {
        RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();

        ListTag nodeTag = tag.getList("Nodes", Tag.TAG_COMPOUND);
        for (Tag raw : nodeTag) {
            if (raw instanceof CompoundTag compound) {
                RoadGraphNodeRecord node = RoadGraphNodeRecord.load(compound);
                data.nodes.put(node.nodeId(), node);
            }
        }

        ListTag edgeTag = tag.getList("Edges", Tag.TAG_COMPOUND);
        for (Tag raw : edgeTag) {
            if (raw instanceof CompoundTag compound) {
                RoadGraphEdgeRecord edge = RoadGraphEdgeRecord.load(compound);
                if (data.nodes.containsKey(edge.fromNodeId()) && data.nodes.containsKey(edge.toNodeId())) {
                    data.edges.put(edge.edgeId(), edge);
                }
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag nodeTag = new ListTag();
        for (RoadGraphNodeRecord node : nodes.values()) {
            nodeTag.add(node.save());
        }
        tag.put("Nodes", nodeTag);

        ListTag edgeTag = new ListTag();
        for (RoadGraphEdgeRecord edge : edges.values()) {
            edgeTag.add(edge.save());
        }
        tag.put("Edges", edgeTag);
        return tag;
    }

    public Collection<RoadGraphNodeRecord> nodes() {
        return List.copyOf(nodes.values());
    }

    public Collection<RoadGraphEdgeRecord> edges() {
        return List.copyOf(edges.values());
    }

    public Collection<RoadGraphEdgeRecord> edgesForDimension(String dimensionId) {
        String normalized = normalizeDimension(dimensionId);
        if (normalized.isBlank()) {
            return List.of();
        }
        return edges.values().stream()
                .filter(edge -> normalized.equals(normalizeDimension(edge.dimensionId())))
                .toList();
    }

    public Optional<RoadGraphNodeRecord> getNode(UUID nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public Optional<RoadGraphEdgeRecord> getEdge(UUID edgeId) {
        return Optional.ofNullable(edges.get(edgeId));
    }

    public void putNode(RoadGraphNodeRecord node) {
        if (node == null) {
            return;
        }
        nodes.put(node.nodeId(), node);
        setDirty();
    }

    public void putEdge(RoadGraphEdgeRecord edge) {
        if (edge == null || !nodes.containsKey(edge.fromNodeId()) || !nodes.containsKey(edge.toNodeId())) {
            return;
        }
        edges.put(edge.edgeId(), edge);
        setDirty();
    }

    public boolean removeEdge(UUID edgeId) {
        if (edgeId == null) {
            return false;
        }
        boolean removed = edges.remove(edgeId) != null;
        if (removed) {
            setDirty();
        }
        return removed;
    }

    RoadNetworkGraphSavedData withEdgeForTest(RoadGraphEdgeRecord edge) {
        if (edge != null) {
            edges.put(edge.edgeId(), edge);
        }
        return this;
    }

    boolean isDirtyForTest() {
        return isDirty();
    }

    private static String normalizeDimension(String dimensionId) {
        return dimensionId == null ? "" : dimensionId.trim().toLowerCase(Locale.ROOT);
    }
}
