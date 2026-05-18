package com.monpai.sailboatmod.roadplanner.structure;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

final class RoadBandRasterizer {
    private RoadBandRasterizer() {
    }

    static List<List<BlockPos>> surfacePositionsByIndex(List<RoadCenterlinePoint> centerline, int width) {
        if (centerline == null || centerline.isEmpty()) {
            return List.of();
        }
        ArrayList<LinkedHashSet<BlockPos>> byIndex = new ArrayList<>(centerline.size());
        for (int i = 0; i < centerline.size(); i++) {
            byIndex.add(new LinkedHashSet<>());
        }
        if (centerline.size() == 1) {
            BlockPos only = centerline.get(0).pos();
            byIndex.get(0).add(new BlockPos(only.getX(), centerline.get(0).targetY(), only.getZ()));
            return freeze(byIndex);
        }

        LinkedHashMap<Long, OwnedCell> owned = new LinkedHashMap<>();
        double halfWidth = Math.max(1.0D, width / 2.0D);
        double halfWidthSq = halfWidth * halfWidth;

        for (int segment = 0; segment < centerline.size() - 1; segment++) {
            RoadCenterlinePoint start = centerline.get(segment);
            RoadCenterlinePoint end = centerline.get(segment + 1);
            int minX = (int) Math.floor(Math.min(start.pos().getX(), end.pos().getX()) - halfWidth - 1);
            int maxX = (int) Math.ceil(Math.max(start.pos().getX(), end.pos().getX()) + halfWidth + 1);
            int minZ = (int) Math.floor(Math.min(start.pos().getZ(), end.pos().getZ()) - halfWidth - 1);
            int maxZ = (int) Math.ceil(Math.max(start.pos().getZ(), end.pos().getZ()) + halfWidth + 1);

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Projection projection = projectToSegment(x, z, start, end, segment);
                    if (!projection.withinSegment() || projection.distanceSq() > halfWidthSq) {
                        continue;
                    }
                    int ownerIndex = projection.t() < 0.5D ? segment : segment + 1;
                    int y = interpolateTargetY(x, z, centerline);
                    BlockPos pos = new BlockPos(x, y, z);
                    long key = BlockPos.asLong(x, 0, z);
                    OwnedCell existing = owned.get(key);
                    if (existing == null || projection.distanceSq() < existing.distanceSq()) {
                        owned.put(key, new OwnedCell(pos, ownerIndex, projection.distanceSq()));
                    }
                }
            }
        }

        for (OwnedCell cell : owned.values()) {
            byIndex.get(Math.max(0, Math.min(byIndex.size() - 1, cell.ownerIndex()))).add(cell.pos());
        }
        for (int i = 0; i < centerline.size(); i++) {
            RoadCenterlinePoint point = centerline.get(i);
            byIndex.get(i).add(new BlockPos(point.pos().getX(), point.targetY(), point.pos().getZ()));
        }
        return freeze(byIndex);
    }

    private static List<List<BlockPos>> freeze(ArrayList<LinkedHashSet<BlockPos>> byIndex) {
        ArrayList<List<BlockPos>> out = new ArrayList<>(byIndex.size());
        for (LinkedHashSet<BlockPos> bucket : byIndex) {
            out.add(bucket.stream()
                    .sorted(Comparator.comparingInt((BlockPos pos) -> pos.getX())
                            .thenComparingInt(pos -> pos.getY())
                            .thenComparingInt(pos -> pos.getZ()))
                    .toList());
        }
        return List.copyOf(out);
    }

    private static int interpolateTargetY(int x, int z, List<RoadCenterlinePoint> centerline) {
        Projection best = null;
        for (int segment = 0; segment < centerline.size() - 1; segment++) {
            Projection projection = projectToSegment(x, z, centerline.get(segment), centerline.get(segment + 1), segment);
            if (best == null || projection.distanceSq() < best.distanceSq()) {
                best = projection;
            }
        }
        if (best == null) {
            return centerline.get(0).targetY();
        }
        int startY = centerline.get(best.segmentIndex()).targetY();
        int endY = centerline.get(best.segmentIndex() + 1).targetY();
        return (int) Math.floor(startY + (endY - startY) * best.t());
    }

    private static Projection projectToSegment(int x, int z, RoadCenterlinePoint start, RoadCenterlinePoint end, int segmentIndex) {
        double ax = start.pos().getX();
        double az = start.pos().getZ();
        double bx = end.pos().getX();
        double bz = end.pos().getZ();
        double dx = bx - ax;
        double dz = bz - az;
        double lengthSq = dx * dx + dz * dz;
        double rawT = lengthSq < 1.0E-9D ? 0.0D : ((x - ax) * dx + (z - az) * dz) / lengthSq;
        double t = Math.max(0.0D, Math.min(1.0D, rawT));
        double px = ax + (dx * t);
        double pz = az + (dz * t);
        double distSq = ((x - px) * (x - px)) + ((z - pz) * (z - pz));
        return new Projection(segmentIndex, t, distSq, rawT >= -1.0E-9D && rawT <= 1.0D + 1.0E-9D);
    }

    private record Projection(int segmentIndex, double t, double distanceSq, boolean withinSegment) {
    }

    private record OwnedCell(BlockPos pos, int ownerIndex, double distanceSq) {
    }
}
