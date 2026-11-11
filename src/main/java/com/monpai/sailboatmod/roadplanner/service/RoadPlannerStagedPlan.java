package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.roadplanner.corridor.RoadAlignment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/** Compressed submitted geometry. A distant corridor is never enumerated before its window is needed. */
public final class RoadPlannerStagedPlan {
    public static final int CORE_BLOCKS = 128;
    /**
     * Route on each side of a stage that is sampled but not built. It must cover everything the
     * road geometry looks at around a point, so that neighbouring stages agree where they meet.
     */
    public static final int HALO_BLOCKS = 120;
    public static final int MAX_NODES = 4096;
    public static final int MIN_PACKED_COORDINATE = -33_554_432;
    public static final int MAX_PACKED_COORDINATE = 33_554_431;
    private final List<BlockPos> nodes;
    private final List<RoadPlannerSegmentType> types;
    /** First leg of each stage group, then the leg count. A group is one long leg, or a run of short same-typed legs. */
    private final int[] groupLeg;
    /** First stage of each group, then the stage count. */
    private final int[] groupStage;
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
        lengths = new double[nodes.size() - 1];
        legDistances = new double[nodes.size()];
        double totalDistance = 0;
        for (int leg = 0; leg < lengths.length; leg++) {
            BlockPos from = nodes.get(leg), to = nodes.get(leg + 1);
            double length = Math.hypot((double) to.getX() - from.getX(), (double) to.getZ() - from.getZ());
            lengths[leg] = length;
            totalDistance += length;
            legDistances[leg + 1] = totalDistance;
            normalized.add(types != null && leg < types.size() && types.get(leg) != null ? types.get(leg) : RoadPlannerSegmentType.ROAD);
        }
        this.types = List.copyOf(normalized);
        distance = totalDistance;
        // A generated route arrives as many short legs. Building each as its own stage would sample
        // the same surrounding terrain again for every few blocks of road, so short neighbours of one
        // type share a stage; a leg longer than a stage is still cut into several.
        int[] legs = new int[lengths.length + 1];
        int[] stages = new int[lengths.length + 1];
        int groups = 0;
        long count = 0;
        for (int leg = 0; leg < lengths.length; ) {
            legs[groups] = leg;
            stages[groups] = (int) count;
            groups++;
            double length = lengths[leg];
            int next = leg + 1;
            while (next < lengths.length && this.types.get(next) == this.types.get(leg)
                    && length + lengths[next] <= CORE_BLOCKS) {
                length += lengths[next++];
            }
            count += next == leg + 1 ? Math.max(1L, (long) Math.ceil(length / CORE_BLOCKS)) : 1L;
            if (count > Integer.MAX_VALUE) throw new IllegalArgumentException("Too many road stages");
            leg = next;
        }
        legs[groups] = lengths.length;
        stages[groups] = (int) count;
        groupLeg = java.util.Arrays.copyOf(legs, groups + 1);
        groupStage = java.util.Arrays.copyOf(stages, groups + 1);
        totalStages = (int) count;
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
        int group = java.util.Arrays.binarySearch(groupStage, index);
        if (group < 0) group = -group - 2;
        int leg = groupLeg[group];
        int lastLeg = groupLeg[group + 1] - 1;
        BlockPos from = nodes.get(leg), to = nodes.get(lastLeg + 1);
        double length = leg == lastLeg ? lengths[leg] : legDistances[lastLeg + 1] - legDistances[leg];
        double begin = leg == lastLeg ? (double) (index - groupStage[group]) * CORE_BLOCKS : 0;
        double end = leg == lastLeg ? Math.min(length, begin + CORE_BLOCKS) : length;
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
        return new Stage(index, leg, leg == lastLeg ? lerp(from, to, begin, length) : from,
                leg == lastLeg ? lerp(from, to, end, length) : to,
                contextNodes.get(0), contextNodes.get(contextNodes.size() - 1), from, to, types.get(leg),
                begin, end, length, List.copyOf(contextNodes), List.copyOf(contextTypes), index == totalStages - 1,
                legDistances[leg] + begin, legDistances[leg] + end);
    }

    /** The rounded road geometry around a stage, addressed by distance along the submitted route. */
    public RoadAlignment alignment(Stage stage) {
        return RoadAlignment.slice(nodes, types, legDistances, stage.routeFrom() - HALO_BLOCKS, stage.routeTo() + HALO_BLOCKS);
    }

    /** Columns the finished centreline passes through inside a stage, in travel order. Y is not meaningful. */
    public List<BlockPos> centerColumns(Stage stage) {
        RoadAlignment alignment = alignment(stage);
        List<BlockPos> columns = new ArrayList<>();
        double[] point = new double[4];
        long first = (long) Math.ceil(stage.routeFrom() - 1.0e-9);
        for (long at = first; stage.terminal() ? at <= Math.ceil(distance) : at < stage.routeTo() - 1.0e-9; at++) {
            alignment.evaluate(Math.min(distance, at), point);
            BlockPos column = new BlockPos((int) Math.round(point[0]), stage.coreFrom().getY(), (int) Math.round(point[1]));
            if (columns.isEmpty() || !columns.get(columns.size() - 1).equals(column)) columns.add(column);
        }
        return columns;
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
                        List<BlockPos> nodes, List<RoadPlannerSegmentType> types, boolean terminal,
                        double routeFrom, double routeTo) {

        /**
         * Start of the route range this stage builds. The first stage also takes anything before
         * the route and the last anything after it, so every column near the road belongs to
         * exactly one stage.
         */
        public double ownedFrom() {
            return index == 0 ? Double.NEGATIVE_INFINITY : routeFrom;
        }

        public double ownedTo() {
            return terminal ? Double.POSITIVE_INFINITY : routeTo;
        }

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
    }
}
