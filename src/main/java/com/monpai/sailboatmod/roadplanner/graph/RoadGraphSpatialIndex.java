package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spatial grid adapted from RoadWeaver's RoadSpatialIndex for graph edge lookups.
 */
public final class RoadGraphSpatialIndex {
    static final int GRID_SHIFT = 3;
    static final int GRID_SIZE = 1 << GRID_SHIFT;

    private final Map<String, Map<Long, List<PointEntry>>> cellsByDimension = new LinkedHashMap<>();
    private final Map<UUID, RoadGraphEdgeRecord> edges = new LinkedHashMap<>();

    public void rebuild(List<RoadGraphEdgeRecord> sourceEdges) {
        cellsByDimension.clear();
        edges.clear();
        for (RoadGraphEdgeRecord edge : sourceEdges == null ? List.<RoadGraphEdgeRecord>of() : sourceEdges) {
            if (edge == null || !edge.built()) {
                continue;
            }
            edges.put(edge.edgeId(), edge);
            addEdge(edge);
        }
    }

    public List<EdgeHit> near(BlockPos pos, int radiusBlocks) {
        if (pos == null) {
            return List.of();
        }
        ArrayList<EdgeHit> all = new ArrayList<>();
        for (String dimension : cellsByDimension.keySet()) {
            all.addAll(near(dimension, pos, radiusBlocks));
        }
        all.sort(Comparator.comparingLong(EdgeHit::distanceSqr));
        return List.copyOf(all);
    }

    public List<EdgeHit> near(String dimensionId, BlockPos pos, int radiusBlocks) {
        if (pos == null) {
            return List.of();
        }
        String dim = normalizeDim(dimensionId);
        Map<Long, List<PointEntry>> cells = cellsByDimension.get(dim);
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        long radiusSqr = (long) Math.max(0, radiusBlocks) * (long) Math.max(0, radiusBlocks);
        int gx = pos.getX() >> GRID_SHIFT;
        int gz = pos.getZ() >> GRID_SHIFT;
        int gridRadius = Math.max(1, (Math.max(0, radiusBlocks) >> GRID_SHIFT) + 1);
        Map<UUID, EdgeHit> best = new HashMap<>();
        for (int dx = -gridRadius; dx <= gridRadius; dx++) {
            for (int dz = -gridRadius; dz <= gridRadius; dz++) {
                List<PointEntry> entries = cells.get(gridKey(gx + dx, gz + dz));
                if (entries == null) {
                    continue;
                }
                for (PointEntry entry : entries) {
                    long dist2 = dist2XZ(pos, entry.pos());
                    if (dist2 > radiusSqr) {
                        continue;
                    }
                    EdgeHit current = best.get(entry.edge().edgeId());
                    if (current == null || dist2 < current.distanceSqr()) {
                        best.put(entry.edge().edgeId(), new EdgeHit(entry.edge(), entry.segmentIndex(), entry.pos(), dist2));
                    }
                }
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingLong(EdgeHit::distanceSqr))
                .toList();
    }

    public List<RoadGraphEdgeRecord> queryRect(String dimensionId, int minX, int minZ, int maxX, int maxZ) {
        String dim = normalizeDim(dimensionId);
        Map<Long, List<PointEntry>> cells = cellsByDimension.get(dim);
        if (cells == null || cells.isEmpty()) {
            return List.of();
        }
        int minGridX = minX >> GRID_SHIFT;
        int maxGridX = maxX >> GRID_SHIFT;
        int minGridZ = minZ >> GRID_SHIFT;
        int maxGridZ = maxZ >> GRID_SHIFT;
        LinkedHashMap<UUID, RoadGraphEdgeRecord> visible = new LinkedHashMap<>();
        for (int gx = minGridX; gx <= maxGridX; gx++) {
            for (int gz = minGridZ; gz <= maxGridZ; gz++) {
                List<PointEntry> entries = cells.get(gridKey(gx, gz));
                if (entries == null) {
                    continue;
                }
                for (PointEntry entry : entries) {
                    BlockPos pos = entry.pos();
                    if (pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                        visible.put(entry.edge().edgeId(), entry.edge());
                    }
                }
            }
        }
        return List.copyOf(visible.values());
    }

    public boolean isRoadCorridor(String dimensionId, BlockPos pos, int radiusBlocks) {
        return !near(dimensionId, pos, Math.max(0, radiusBlocks)).isEmpty();
    }

    private void addEdge(RoadGraphEdgeRecord edge) {
        String dim = normalizeDim(edge.dimensionId());
        Map<Long, List<PointEntry>> cells = cellsByDimension.computeIfAbsent(dim, ignored -> new HashMap<>());
        int index = 0;
        for (RoadGraphSegmentPlacement placement : edge.placements()) {
            addPoint(cells, edge, index, placement.middlePos());
            for (BlockPos pos : placement.positions()) {
                addPoint(cells, edge, index, pos);
            }
            index++;
        }
        for (BlockPos pos : edge.centerline()) {
            addPoint(cells, edge, index++, pos);
        }
    }

    private static void addPoint(Map<Long, List<PointEntry>> cells, RoadGraphEdgeRecord edge, int index, BlockPos pos) {
        if (pos == null) {
            return;
        }
        cells.computeIfAbsent(gridKey(pos.getX() >> GRID_SHIFT, pos.getZ() >> GRID_SHIFT), ignored -> new ArrayList<>())
                .add(new PointEntry(edge, index, pos.immutable()));
    }

    private static long gridKey(int gx, int gz) {
        return (((long) gx) << 32) | (gz & 0xFFFFFFFFL);
    }

    private static long dist2XZ(BlockPos left, BlockPos right) {
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static String normalizeDim(String dimensionId) {
        return dimensionId == null ? "" : dimensionId.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private record PointEntry(RoadGraphEdgeRecord edge, int segmentIndex, BlockPos pos) {
    }

    public record EdgeHit(RoadGraphEdgeRecord edge, int segmentIndex, BlockPos pos, long distanceSqr) {
    }
}
