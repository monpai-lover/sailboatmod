package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.UUID;

public final class RoadGraphOverlayService {
    private final RoadGraphRepository repository;
    private final NationSavedData legacyData;

    public RoadGraphOverlayService(RoadGraphRepository repository, NationSavedData legacyData) {
        this.repository = repository == null ? new RoadGraphRepository(new RoadNetworkGraphSavedData()) : repository;
        this.legacyData = legacyData == null ? new NationSavedData() : legacyData;
    }

    public List<Overlay> visibleOverlays(String dimensionId, BlockPos center, int regionSize, int lodStepBlocks) {
        if (center == null || regionSize <= 0) {
            return List.of();
        }
        int half = regionSize / 2;
        int minX = center.getX() - half;
        int maxX = center.getX() + half;
        int minZ = center.getZ() - half;
        int maxZ = center.getZ() + half;
        java.util.ArrayList<Overlay> overlays = new java.util.ArrayList<>();
        for (RoadGraphEdgeRecord edge : repository.spatialIndex().queryRect(dimensionId, minX, minZ, maxX, maxZ)) {
            overlays.add(new Overlay(edge.edgeId().toString(), false, edge.roadName(),
                    simplify(edge.displayPath(), Math.max(1, lodStepBlocks)), edge.width(), edge.status().name()));
        }
        String dim = dimensionId == null ? "" : dimensionId.trim().toLowerCase(java.util.Locale.ROOT);
        for (RoadNetworkRecord legacy : legacyData.getRoadNetworks()) {
            if (legacy != null && dim.equals(legacy.dimensionId().toLowerCase(java.util.Locale.ROOT))
                    && intersects(legacy.path(), minX, minZ, maxX, maxZ)) {
                overlays.add(new Overlay(legacy.roadId(), true, legacy.roadId(),
                        simplify(legacy.path(), Math.max(1, lodStepBlocks)), 1, "LEGACY"));
            }
        }
        return List.copyOf(overlays);
    }

    public List<Candidate> candidatesNear(String dimensionId, BlockPos probe, int radiusBlocks) {
        return repository.spatialIndex().near(dimensionId, probe, radiusBlocks).stream()
                .map(hit -> new Candidate(hit.edge().edgeId(), hit.edge().roadName(), hit.pos(), hit.segmentIndex()))
                .toList();
    }

    private static boolean intersects(List<BlockPos> path, int minX, int minZ, int maxX, int maxZ) {
        if (path == null) {
            return false;
        }
        for (BlockPos pos : path) {
            if (pos != null && pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> simplify(List<BlockPos> path, int lodStepBlocks) {
        if (path == null || path.size() <= 2) {
            return path == null ? List.of() : path;
        }
        java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
        BlockPos keep = path.get(0);
        out.add(keep);
        for (int index = 1; index < path.size() - 1; index++) {
            BlockPos current = path.get(index);
            if (Math.abs(current.getX() - keep.getX()) + Math.abs(current.getZ() - keep.getZ()) >= lodStepBlocks) {
                out.add(current);
                keep = current;
            }
        }
        BlockPos tail = path.get(path.size() - 1);
        if (!tail.equals(out.get(out.size() - 1))) {
            out.add(tail);
        }
        return List.copyOf(out);
    }

    public record Overlay(String id, boolean legacy, String displayName, List<BlockPos> displayPath, int width, String status) {
    }

    public record Candidate(UUID edgeId, String displayName, BlockPos anchorPos, int segmentIndex) {
    }
}
