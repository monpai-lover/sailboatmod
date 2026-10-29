package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/** Compressed submitted geometry. A distant corridor is never enumerated before its window is needed. */
public final class RoadPlannerStagedPlan {
    public static final int CORE_BLOCKS = 128;
    public static final int HALO_BLOCKS = 32;
    public static final int MAX_NODES = 4096;
    public static final int MIN_PACKED_COORDINATE = -33_554_432;
    public static final int MAX_PACKED_COORDINATE = 33_554_431;
    private final List<BlockPos> nodes;
    private final List<RoadPlannerSegmentType> types;
    private final int[] firstStage;
    private final double[] lengths;
    private final double[] legDistances;
    private final int totalStages;
    private final double distance;

    public RoadPlannerStagedPlan(List<BlockPos> nodes, List<RoadPlannerSegmentType> types) {
        if (nodes == null || nodes.size() < 2 || nodes.size() > MAX_NODES) throw new IllegalArgumentException("Invalid road node count");
        this.nodes = nodes.stream().map(BlockPos::immutable).toList();
        for (BlockPos node : nodes) {
            if (!withinPackedBounds(node)) {
                throw new IllegalArgumentException("Road leaves world bounds");
            }
        }
        List<RoadPlannerSegmentType> normalized = new ArrayList<>();
        firstStage = new int[nodes.size()];
        lengths = new double[nodes.size() - 1];
        legDistances = new double[nodes.size()];
        long count = 0;
        double totalDistance = 0;
        for (int leg = 0; leg < lengths.length; leg++) {
            BlockPos from = nodes.get(leg), to = nodes.get(leg + 1);
            double length = Math.hypot((double) to.getX() - from.getX(), (double) to.getZ() - from.getZ());
            lengths[leg] = length;
            firstStage[leg] = (int) count;
            count += Math.max(1L, (long) Math.ceil(length / CORE_BLOCKS));
            if (count > Integer.MAX_VALUE) throw new IllegalArgumentException("Too many road stages");
            totalDistance += length;
            legDistances[leg + 1] = totalDistance;
            normalized.add(types != null && leg < types.size() && types.get(leg) != null ? types.get(leg) : RoadPlannerSegmentType.ROAD);
        }
        firstStage[lengths.length] = (int) count;
        this.types = List.copyOf(normalized);
        totalStages = (int) count;
        distance = totalDistance;
    }

    public int storedNodeCount() { return nodes.size(); }
    public static boolean withinPackedBounds(BlockPos node) {
        return node != null && node.getX() >= MIN_PACKED_COORDINATE && node.getX() <= MAX_PACKED_COORDINATE
                && node.getZ() >= MIN_PACKED_COORDINATE && node.getZ() <= MAX_PACKED_COORDINATE;
    }
    public int totalStages() { return totalStages; }
    public double distance() { return distance; }
    public List<BlockPos> nodes() { return nodes; }
    public List<RoadPlannerSegmentType> types() { return types; }

    public Stage stage(int index) {
        if (index < 0 || index >= totalStages) throw new IndexOutOfBoundsException(index);
        int leg = java.util.Arrays.binarySearch(firstStage, index);
        if (leg < 0) leg = -leg - 2;
        BlockPos from = nodes.get(leg), to = nodes.get(leg + 1);
        double length = lengths[leg];
        double begin = (double) (index - firstStage[leg]) * CORE_BLOCKS;
        double end = Math.min(length, begin + CORE_BLOCKS);
        List<BlockPos> contextNodes = new ArrayList<>();
        List<RoadPlannerSegmentType> contextTypes = new ArrayList<>();
        double low = Math.max(0, legDistances[leg] + begin - HALO_BLOCKS);
        double high = Math.min(distance, legDistances[leg] + end + HALO_BLOCKS);
        for (int part = 0; part < lengths.length; part++) {
            if (lengths[part] == 0 || legDistances[part + 1] <= low || legDistances[part] >= high) continue;
            BlockPos a = lerp(nodes.get(part), nodes.get(part + 1), Math.max(0, low - legDistances[part]), lengths[part]);
            BlockPos b = lerp(nodes.get(part), nodes.get(part + 1), Math.min(lengths[part], high - legDistances[part]), lengths[part]);
            if (contextNodes.isEmpty()) contextNodes.add(a);
            if (!contextNodes.get(contextNodes.size() - 1).equals(b)) { contextNodes.add(b); contextTypes.add(types.get(part)); }
        }
        if (contextNodes.size() < 2) { contextNodes = new ArrayList<>(List.of(from, to)); contextTypes = new ArrayList<>(List.of(types.get(leg))); }
        return new Stage(index, leg, lerp(from, to, begin, length), lerp(from, to, end, length),
                contextNodes.get(0), contextNodes.get(contextNodes.size() - 1), from, to, types.get(leg),
                begin, end, length, List.copyOf(contextNodes), List.copyOf(contextTypes), index == totalStages - 1);
    }

    private static BlockPos lerp(BlockPos from, BlockPos to, double at, double length) {
        double t = length == 0 ? 0 : Math.max(0, Math.min(1, at / length));
        return new BlockPos((int) Math.round(from.getX() + ((double) to.getX() - from.getX()) * t),
                (int) Math.round(from.getY() + ((double) to.getY() - from.getY()) * t),
                (int) Math.round(from.getZ() + ((double) to.getZ() - from.getZ()) * t));
    }

    public record Stage(int index, int leg, BlockPos coreFrom, BlockPos coreTo, BlockPos sampleFrom,
                        BlockPos sampleTo, BlockPos spanFrom, BlockPos spanTo, RoadPlannerSegmentType type,
                        double fromDistance, double toDistance, double spanLength,
                        List<BlockPos> nodes, List<RoadPlannerSegmentType> types, boolean terminal) {

        /** Includes support/lighting margins along the corridor, without a diagonal bounding rectangle. */
        public List<Long> chunks() {
            java.util.Set<Long> chunks = new java.util.LinkedHashSet<>();
            for (int part = 0; part < nodes.size() - 1; part++) {
                BlockPos from = nodes.get(part), to = nodes.get(part + 1);
                double length = Math.hypot((double) to.getX() - from.getX(), (double) to.getZ() - from.getZ());
                int count = Math.max(1, (int) Math.ceil(length / 8));
                for (int step = 0; step <= count; step++) {
                    BlockPos sample = lerp(from, to, (double) step * length / count, length);
                    int cx = Math.floorDiv(sample.getX(), 16), cz = Math.floorDiv(sample.getZ(), 16);
                    for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) chunks.add(ChunkPos.asLong(cx + dx, cz + dz));
                }
            }
            return List.copyOf(chunks);
        }

        /** Halo samples guide smoothing; only this core owns emitted placement columns. */
        public boolean ownsColumn(int x, int z) {
            if (spanLength == 0) return true;
            double dx = (double) spanTo.getX() - spanFrom.getX();
            double dz = (double) spanTo.getZ() - spanFrom.getZ();
            double at = (((double) x - spanFrom.getX()) * dx + ((double) z - spanFrom.getZ()) * dz) / spanLength;
            return (index == 0 || at >= fromDistance - 1.0e-6) && (terminal || at < toDistance - 1.0e-6);
        }

        /** Actual profile checks exclude smoothing halo and end caps that another stage may already have built. */
        public boolean coreContains(int x, int z) {
            if (spanLength == 0) return x == coreFrom.getX() && z == coreFrom.getZ();
            double dx = (double) spanTo.getX() - spanFrom.getX(), dz = (double) spanTo.getZ() - spanFrom.getZ();
            double at = (((double) x - spanFrom.getX()) * dx + ((double) z - spanFrom.getZ()) * dz) / spanLength;
            double perpendicular = (((double) x - spanFrom.getX()) * dz - ((double) z - spanFrom.getZ()) * dx) / spanLength;
            return Math.abs(perpendicular) <= 1.0 && at >= fromDistance - 1.0e-6
                    && (terminal ? at <= toDistance + 1.0e-6 : at < toDistance - 1.0e-6);
        }
    }
}
