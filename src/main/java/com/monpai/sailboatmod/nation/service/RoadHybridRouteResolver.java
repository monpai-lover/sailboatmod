package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/**
 * Resolves hybrid routes combining road network paths with terrain pathfinding.
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
        if (sources == null || sources.isEmpty() || targets == null || targets.isEmpty() || resolver == null) {
            return new HybridRoute(ResolutionKind.NONE, List.of());
        }

        PathSummary bestDirect = null;
        for (BlockPos src : sources) {
            for (BlockPos tgt : targets) {
                PathSummary ps = resolver.resolve(src, tgt, true);
                if (ps != null && ps.path() != null && ps.path().size() >= 2) {
                    if (bestDirect == null || ps.cost() < bestDirect.cost()) {
                        bestDirect = ps;
                    }
                }
            }
        }

        if (networkNodes != null && !networkNodes.isEmpty() && adjacency != null && !adjacency.isEmpty()) {
            for (BlockPos src : sources) {
                for (BlockPos tgt : targets) {
                    BlockPos nearSrc = findNearest(src, networkNodes);
                    BlockPos nearTgt = findNearest(tgt, networkNodes);
                    if (nearSrc == null || nearTgt == null) continue;

                    PathSummary toNetwork = resolver.resolve(src, nearSrc, false);
                    PathSummary fromNetwork = resolver.resolve(nearTgt, tgt, false);
                    if (toNetwork == null || toNetwork.path().size() < 1) continue;
                    if (fromNetwork == null || fromNetwork.path().size() < 1) continue;

                    List<BlockPos> networkPath = dijkstraOnAdjacency(nearSrc, nearTgt, adjacency);
                    if (networkPath.isEmpty()) continue;

                    List<BlockPos> full = new ArrayList<>();
                    full.addAll(toNetwork.path());
                    full.addAll(networkPath);
                    full.addAll(fromNetwork.path());
                    int cost = toNetwork.cost() + networkPath.size() + fromNetwork.cost();

                    if (bestDirect == null || cost < bestDirect.cost()) {
                        return new HybridRoute(ResolutionKind.HYBRID, full);
                    }
                }
            }
        }

        if (bestDirect != null) {
            ResolutionKind kind = bestDirect.usedWater() ? ResolutionKind.HYBRID : ResolutionKind.LAND;
            return new HybridRoute(kind, bestDirect.path());
        }
        return new HybridRoute(ResolutionKind.NONE, List.of());
    }

    private static BlockPos findNearest(BlockPos origin, Set<BlockPos> candidates) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos c : candidates) {
            double d = origin.distSqr(c);
            if (d < bestDist) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    private static List<BlockPos> dijkstraOnAdjacency(BlockPos start, BlockPos end, Map<BlockPos, Set<BlockPos>> adjacency) {
        if (start.equals(end)) return List.of(start);
        java.util.PriorityQueue<Map.Entry<BlockPos, Double>> open = new java.util.PriorityQueue<>(
            java.util.Comparator.comparingDouble(Map.Entry::getValue));
        Map<BlockPos, Double> dist = new HashMap<>();
        Map<BlockPos, BlockPos> prev = new HashMap<>();
        open.add(Map.entry(start, 0.0));
        dist.put(start, 0.0);
        while (!open.isEmpty()) {
            Map.Entry<BlockPos, Double> cur = open.poll();
            if (cur.getKey().equals(end)) break;
            if (cur.getValue() > dist.getOrDefault(cur.getKey(), Double.MAX_VALUE)) continue;
            for (BlockPos neighbor : adjacency.getOrDefault(cur.getKey(), Set.of())) {
                double step = Math.sqrt(cur.getKey().distSqr(neighbor));
                double candidate = cur.getValue() + step;
                if (candidate < dist.getOrDefault(neighbor, Double.MAX_VALUE)) {
                    dist.put(neighbor, candidate);
                    prev.put(neighbor, cur.getKey());
                    open.add(Map.entry(neighbor, candidate));
                }
            }
        }
        if (!dist.containsKey(end)) return List.of();
        List<BlockPos> path = new ArrayList<>();
        BlockPos cursor = end;
        while (cursor != null && !cursor.equals(start)) {
            path.add(cursor);
            cursor = prev.get(cursor);
        }
        if (cursor == null) return List.of();
        path.add(start);
        java.util.Collections.reverse(path);
        return path;
    }

    public static PathSummary summarizePath(ServerLevel level, List<BlockPos> path, boolean usedWater) {
        return new PathSummary(path == null ? List.of() : path, usedWater, path == null ? 0 : path.size());
    }
}
