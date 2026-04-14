package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RoadNetworkSnapService {
    private static final double MAX_SNAP_DISTANCE = 3.0D;
    private static final double MIN_DIRECTION_DOT = 0.6D;

    private RoadNetworkSnapService() {
    }

    public static List<BlockPos> snapPath(List<BlockPos> rawPath, List<RoadNetworkRecord> roads) {
        if (rawPath == null || rawPath.isEmpty()) {
            return List.of();
        }
        List<BlockPos> copiedPath = rawPath.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
        if (copiedPath.size() < 2 || roads == null || roads.isEmpty()) {
            return copiedPath;
        }

        SnapMatch startMatch = findSnapMatch(copiedPath.get(0), copiedPath.get(1), roads);
        SnapMatch endMatch = findSnapMatch(copiedPath.get(copiedPath.size() - 1), copiedPath.get(copiedPath.size() - 2), roads);
        if (startMatch != null && endMatch != null && startMatch.road().equals(endMatch.road())) {
            List<BlockPos> snappedSubpath = extractRoadSubpath(startMatch.road(), startMatch.pathIndex(), endMatch.pathIndex());
            if (!snappedSubpath.isEmpty()) {
                return snappedSubpath;
            }
        }

        ArrayList<BlockPos> snapped = new ArrayList<>(copiedPath);
        if (startMatch != null) {
            snapped.set(0, startMatch.candidate());
        }
        if (endMatch != null) {
            snapped.set(snapped.size() - 1, endMatch.candidate());
        }
        return List.copyOf(snapped);
    }

    static List<BlockPos> snapPathForTest(List<BlockPos> rawPath, List<RoadNetworkRecord> roads) {
        return snapPath(rawPath, roads);
    }

    private static SnapMatch findSnapMatch(BlockPos endpoint, BlockPos neighbor, List<RoadNetworkRecord> roads) {
        SnapMatch best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        double maxDistanceSq = MAX_SNAP_DISTANCE * MAX_SNAP_DISTANCE;
        for (RoadNetworkRecord road : roads) {
            if (road == null || road.path() == null || road.path().isEmpty()) {
                continue;
            }
            for (int i = 0; i < road.path().size(); i++) {
                BlockPos candidate = road.path().get(i);
                if (candidate == null) {
                    continue;
                }
                double distanceSq = endpoint.distSqr(candidate);
                if (distanceSq > maxDistanceSq) {
                    continue;
                }
                BlockPos referenceNeighbor = resolveReferenceNeighbor(road.path(), i);
                if (referenceNeighbor == null || !directionCompatible(endpoint, neighbor, candidate, referenceNeighbor)) {
                    continue;
                }
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq;
                    best = new SnapMatch(road, i, candidate.immutable());
                }
            }
        }
        return best;
    }

    private static List<BlockPos> extractRoadSubpath(RoadNetworkRecord road, int startIndex, int endIndex) {
        if (road == null || road.path() == null || road.path().isEmpty()) {
            return List.of();
        }
        int safeStart = Math.max(0, Math.min(startIndex, road.path().size() - 1));
        int safeEnd = Math.max(0, Math.min(endIndex, road.path().size() - 1));
        ArrayList<BlockPos> path = new ArrayList<>();
        if (safeStart <= safeEnd) {
            for (int i = safeStart; i <= safeEnd; i++) {
                BlockPos pos = road.path().get(i);
                if (pos != null) {
                    path.add(pos.immutable());
                }
            }
            return List.copyOf(path);
        }
        for (int i = safeStart; i >= safeEnd; i--) {
            BlockPos pos = road.path().get(i);
            if (pos != null) {
                path.add(pos.immutable());
            }
        }
        return List.copyOf(path);
    }

    private static BlockPos resolveReferenceNeighbor(List<BlockPos> roadPath, int index) {
        if (roadPath == null || roadPath.isEmpty()) {
            return null;
        }
        if (index > 0 && roadPath.get(index - 1) != null) {
            return roadPath.get(index - 1).immutable();
        }
        if (index + 1 < roadPath.size() && roadPath.get(index + 1) != null) {
            return roadPath.get(index + 1).immutable();
        }
        return null;
    }

    private static boolean directionCompatible(BlockPos endpoint,
                                               BlockPos neighbor,
                                               BlockPos snapped,
                                               BlockPos snappedNeighbor) {
        double leftX = neighbor.getX() - endpoint.getX();
        double leftZ = neighbor.getZ() - endpoint.getZ();
        double rightX = snappedNeighbor.getX() - snapped.getX();
        double rightZ = snappedNeighbor.getZ() - snapped.getZ();
        double leftLength = Math.hypot(leftX, leftZ);
        double rightLength = Math.hypot(rightX, rightZ);
        if (leftLength < 1.0E-6D || rightLength < 1.0E-6D) {
            return true;
        }
        double dot = Math.abs(((leftX / leftLength) * (rightX / rightLength)) + ((leftZ / leftLength) * (rightZ / rightLength)));
        return dot >= MIN_DIRECTION_DOT;
    }

    private record SnapMatch(RoadNetworkRecord road, int pathIndex, BlockPos candidate) {
    }
}
