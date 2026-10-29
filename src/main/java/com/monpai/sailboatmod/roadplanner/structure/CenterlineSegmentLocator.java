package com.monpai.sailboatmod.roadplanner.structure;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Finds the centerline segment nearest to a column, with the same answer as scanning every segment
 * (the first strictly nearer one wins), but without visiting the whole road for every road cell.
 * That full scan made long roads cost length squared: a 10,000 block road froze the server for about 30 s.
 *
 * Segments are bucketed into 16x16 cells. A column only checks its own cell and the eight around it,
 * which covers everything closer than 16 blocks; anything farther falls back to the full scan.
 */
final class CenterlineSegmentLocator {
    private static final int CELL_SHIFT = 4;
    private static final double EXACT_RADIUS_SQ = 16.0D * 16.0D;
    /** A segment spanning more cells than this is checked on every query instead of being bucketed. */
    private static final long MAX_CELLS_PER_SEGMENT = 1024L;

    private final List<RoadCenterlinePoint> centerline;
    private final Long2ObjectOpenHashMap<IntArrayList> cells = new Long2ObjectOpenHashMap<>();
    private final IntArrayList oversized = new IntArrayList();

    CenterlineSegmentLocator(List<RoadCenterlinePoint> centerline) {
        this.centerline = centerline == null ? List.of() : centerline;
        for (int i = 0; i < this.centerline.size() - 1; i++) {
            BlockPos start = this.centerline.get(i).pos();
            BlockPos end = this.centerline.get(i + 1).pos();
            int minCellX = Math.min(start.getX(), end.getX()) >> CELL_SHIFT;
            int maxCellX = Math.max(start.getX(), end.getX()) >> CELL_SHIFT;
            int minCellZ = Math.min(start.getZ(), end.getZ()) >> CELL_SHIFT;
            int maxCellZ = Math.max(start.getZ(), end.getZ()) >> CELL_SHIFT;
            if ((long) (maxCellX - minCellX + 1) * (maxCellZ - minCellZ + 1) > MAX_CELLS_PER_SEGMENT) {
                oversized.add(i);
                continue;
            }
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    cells.computeIfAbsent(cellKey(cellX, cellZ), key -> new IntArrayList(4)).add(i);
                }
            }
        }
    }

    /** Index of the nearest segment (its start point), 0 when the centerline has fewer than two points. */
    int nearestSegment(int x, int z) {
        int best = -1;
        double bestDistanceSq = Double.MAX_VALUE;
        int cellX = x >> CELL_SHIFT;
        int cellZ = z >> CELL_SHIFT;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                IntArrayList bucket = cells.get(cellKey(cellX + dx, cellZ + dz));
                if (bucket == null) {
                    continue;
                }
                for (int k = 0; k < bucket.size(); k++) {
                    int segment = bucket.getInt(k);
                    double distanceSq = segmentDistanceSq(x, z, segment);
                    if (distanceSq < bestDistanceSq || (distanceSq == bestDistanceSq && segment < best)) {
                        bestDistanceSq = distanceSq;
                        best = segment;
                    }
                }
            }
        }
        for (int k = 0; k < oversized.size(); k++) {
            int segment = oversized.getInt(k);
            double distanceSq = segmentDistanceSq(x, z, segment);
            if (distanceSq < bestDistanceSq || (distanceSq == bestDistanceSq && segment < best)) {
                bestDistanceSq = distanceSq;
                best = segment;
            }
        }
        // Nothing outside the 3x3 cells can be closer than 16 blocks, so a hit inside that radius is exact.
        if (best >= 0 && bestDistanceSq < EXACT_RADIUS_SQ) {
            return best;
        }
        return fullScan(x, z);
    }

    private int fullScan(int x, int z) {
        int best = 0;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int i = 0; i < centerline.size() - 1; i++) {
            double distanceSq = segmentDistanceSq(x, z, i);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                best = i;
            }
        }
        return best;
    }

    private double segmentDistanceSq(int x, int z, int segment) {
        BlockPos start = centerline.get(segment).pos();
        BlockPos end = centerline.get(segment + 1).pos();
        double ax = start.getX();
        double az = start.getZ();
        double dx = end.getX() - ax;
        double dz = end.getZ() - az;
        double lengthSq = dx * dx + dz * dz;
        double t = lengthSq < 1.0E-9D ? 0.0D : Math.max(0.0D, Math.min(1.0D, ((x - ax) * dx + (z - az) * dz) / lengthSq));
        double px = ax + dx * t;
        double pz = az + dz * t;
        return ((x - px) * (x - px)) + ((z - pz) * (z - pz));
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }
}
