package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public final class RoadHybridRouteResolver {
    private RoadHybridRouteResolver() {}

    public enum ResolutionKind { NONE, ROAD, LAND, HYBRID }

    public record HybridRoute(ResolutionKind kind, List<BlockPos> fullPath) {}

    public record PathSummary(List<BlockPos> path, boolean usedWater, int cost) {}

    @FunctionalInterface
    public interface SegmentResolver {
        PathSummary resolve(BlockPos from, BlockPos to, boolean allowWaterFallback);
    }

    public static Set<BlockPos> collectNetworkNodes(List<RoadNetworkRecord> roads) {
        Set<BlockPos> nodes = new HashSet<>();
        if (roads == null) return nodes;
        for (RoadNetworkRecord road : roads) {
            if (road != null && road.path() != null) {
                for (BlockPos pos : road.path()) {
                    if (pos != null) nodes.add(pos.immutable());
                }
            }
        }
        return nodes;
    }

    public static Map<BlockPos, Set<BlockPos>> collectNetworkAdjacency(List<RoadNetworkRecord> roads) {
        Map<BlockPos, Set<BlockPos>> adjacency = new HashMap<>();
        if (roads == null) return adjacency;
        for (RoadNetworkRecord road : roads) {
            if (road == null || road.path() == null || road.path().size() < 2) continue;
            List<BlockPos> path = road.path();
            for (int i = 0; i < path.size() - 1; i++) {
                BlockPos a = path.get(i).immutable();
                BlockPos b = path.get(i + 1).immutable();
                adjacency.computeIfAbsent(a, k -> new HashSet<>()).add(b);
                adjacency.computeIfAbsent(b, k -> new HashSet<>()).add(a);
            }
        }
        return adjacency;
    }

    public static HybridRoute resolveCandidates(
            List<BlockPos> sources,
            List<BlockPos> targets,
            Set<BlockPos> networkNodes,
            Map<BlockPos, Set<BlockPos>> adjacency,
            SegmentResolver resolver) {
        return new HybridRoute(ResolutionKind.NONE, List.of());
    }

    public static PathSummary summarizePath(ServerLevel level, List<BlockPos> path, boolean usedWater) {
        return new PathSummary(path == null ? List.of() : path, usedWater, path == null ? 0 : path.size());
    }
}
