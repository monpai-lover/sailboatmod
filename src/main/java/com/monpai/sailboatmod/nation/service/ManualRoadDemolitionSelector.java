package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class ManualRoadDemolitionSelector {
    private ManualRoadDemolitionSelector() {
    }

    public static RoadNetworkRecord roadForTest(String roadId, List<BlockPos> path) {
        return new RoadNetworkRecord(
                roadId,
                "nation:test",
                "town:test",
                "minecraft:overworld",
                "town:a",
                "town:b",
                path,
                0L,
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        );
    }

    public static RoadNetworkRecord selectRoadForTest(Vec3 hitPos,
                                                      Vec3 viewDir,
                                                      List<RoadNetworkRecord> roads,
                                                      double radius) {
        return selectRoad(hitPos, viewDir, roads, radius);
    }

    public static RoadNetworkRecord selectRoad(Vec3 hitPos,
                                               Vec3 viewDir,
                                               List<RoadNetworkRecord> roads,
                                               double radius) {
        if (hitPos == null || roads == null || roads.isEmpty() || radius <= 0.0D) {
            return null;
        }
        Vec3 look = normalizeOrFallback(viewDir, new Vec3(1.0D, 0.0D, 0.0D));
        RoadNetworkRecord best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (RoadNetworkRecord road : roads) {
            double score = scoreRoad(hitPos, look, road, radius);
            if (score > bestScore) {
                bestScore = score;
                best = road;
            }
        }
        return bestScore == Double.NEGATIVE_INFINITY ? null : best;
    }

    private static double scoreRoad(Vec3 hitPos, Vec3 look, RoadNetworkRecord road, double radius) {
        if (road == null || road.path().isEmpty()) {
            return Double.NEGATIVE_INFINITY;
        }
        if (road.path().size() == 1) {
            double distance = hitPos.distanceTo(Vec3.atCenterOf(road.path().get(0)));
            return distance <= radius ? (radius - distance) * 10.0D : Double.NEGATIVE_INFINITY;
        }
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 1; i < road.path().size(); i++) {
            Vec3 from = Vec3.atCenterOf(road.path().get(i - 1));
            Vec3 to = Vec3.atCenterOf(road.path().get(i));
            Vec3 nearest = nearestPointOnSegment(hitPos, from, to);
            double distance = nearest.distanceTo(hitPos);
            if (distance > radius) {
                continue;
            }
            Vec3 segmentDir = normalizeOrFallback(to.subtract(from), look);
            double alignment = Math.abs(segmentDir.dot(look));
            double score = (radius - distance) * 10.0D + alignment * 5.0D;
            if (score > bestScore) {
                bestScore = score;
            }
        }
        return bestScore;
    }

    private static Vec3 nearestPointOnSegment(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 segment = to.subtract(from);
        double lengthSq = segment.lengthSqr();
        if (lengthSq <= 1.0E-6D) {
            return from;
        }
        double t = point.subtract(from).dot(segment) / lengthSq;
        t = Math.max(0.0D, Math.min(1.0D, t));
        return from.add(segment.scale(t));
    }

    private static Vec3 normalizeOrFallback(Vec3 vector, Vec3 fallback) {
        if (vector == null || vector.lengthSqr() <= 1.0E-6D) {
            return fallback;
        }
        return vector.normalize();
    }
}
