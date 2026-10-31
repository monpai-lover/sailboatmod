package com.monpai.sailboatmod.route.water;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** Bounded, tri-state local port validation. It does not prove an inter-port route exists. */
public final class PortBerthValidator {
    public static final int MAX_ZONE_RADIUS = 32;
    public static final int EXIT_DISTANCE = 8;
    public static final int SNAPSHOT_MARGIN = EXIT_DISTANCE + 1;
    private static final int MAX_SAMPLES = 12_000;
    private static final int MAX_EXPANSIONS = 8_192;

    public enum State { WATER, LAND, UNKNOWN }
    public enum Origin { LIVE, NBT, TELLUS, NONE }
    public enum Verdict { VERIFIED, PREDICTED, INVALID, PENDING }
    public record Cell(State state, int surfaceY, Origin origin) {
        public static Cell unknown() { return new Cell(State.UNKNOWN, 0, Origin.NONE); }
        public boolean predicted() { return origin == Origin.TELLUS; }
    }
    public record Zone(int x, int y, int z, int minX, int maxX, int minZ, int maxZ) {}
    public record Berth(int x, int y, int z) {}
    public record Result(Verdict verdict, Berth berth) {
        public boolean usable() { return verdict == Verdict.VERIFIED || verdict == Verdict.PREDICTED; }
        public boolean listed() { return verdict != Verdict.INVALID; }
        public static Result pending() { return new Result(Verdict.PENDING, null); }
    }
    @FunctionalInterface
    public interface Samples { Cell sample(int x, int z); }

    private PortBerthValidator() {}

    public static Result resolve(Zone zone, Samples samples, BooleanSupplier expired) {
        if (zone == null || samples == null) return Result.pending();
        if (zone.minX > zone.maxX || zone.minZ > zone.maxZ) return new Result(Verdict.INVALID, null);
        int minX = Math.max(-MAX_ZONE_RADIUS, zone.minX), maxX = Math.min(MAX_ZONE_RADIUS, zone.maxX);
        int minZ = Math.max(-MAX_ZONE_RADIUS, zone.minZ), maxZ = Math.min(MAX_ZONE_RADIUS, zone.maxZ);
        Probe probe = new Probe(samples, expired);
        // A truncated search cannot prove that the entire configured zone is invalid.
        probe.uncertain = minX != zone.minX || maxX != zone.maxX || minZ != zone.minZ || maxZ != zone.maxZ;
        ArrayList<int[]> candidates = new ArrayList<>();
        for (int dx = minX; dx <= maxX; dx++) {
            for (int dz = minZ; dz <= maxZ; dz++) {
                if ((long) dx * dx + (long) dz * dz < 3.5D * 3.5D) continue;
                candidates.add(new int[]{zone.x + dx, zone.z + dz});
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> Math.abs(Math.hypot((double) p[0] - zone.x,
                (double) p[1] - zone.z) - 8.0D)));
        for (int[] p : candidates) {
            if (probe.exhausted()) return Result.pending();
            Footprint berth = probe.footprint(p[0], p[1]);
            if (berth.state != State.WATER) continue;
            Exit exit = probe.exit(p[0], p[1], berth.surfaceY);
            if (exit.reached) {
                return new Result(berth.predicted || exit.predicted ? Verdict.PREDICTED : Verdict.VERIFIED,
                        new Berth(p[0], berth.surfaceY + 1, p[1]));
            }
        }
        return probe.uncertain || probe.exhausted() ? Result.pending() : new Result(Verdict.INVALID, null);
    }

    /** Missing NBT may fall back to prediction; failed/unfinished NBT must not override real edits. */
    public static Cell choose(Cell live, Cell disk, boolean diskDefinitelyAbsent,
                              java.util.function.Supplier<Cell> predicted) {
        if (live != null) return live;
        if (disk != null) return disk;
        if (!diskDefinitelyAbsent || predicted == null) return Cell.unknown();
        Cell result = predicted.get();
        return result == null ? Cell.unknown() : result;
    }

    private record Footprint(State state, int surfaceY, boolean predicted) {}
    private record Exit(boolean reached, boolean predicted) {}
    private record Node(int x, int z, boolean predicted) {}
    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    private static final class Probe {
        private final Samples samples;
        private final BooleanSupplier expired;
        private final Map<Long, Cell> cache = new HashMap<>();
        private int expansions;
        private boolean uncertain;
        private Probe(Samples samples, BooleanSupplier expired) {
            this.samples = samples;
            this.expired = expired == null ? () -> false : expired;
        }
        private boolean exhausted() {
            return expansions >= MAX_EXPANSIONS || cache.size() >= MAX_SAMPLES || expired.getAsBoolean();
        }
        private Cell cell(int x, int z) {
            Cell cached = cache.get(key(x, z));
            if (cached != null) return cached;
            if (exhausted()) { uncertain = true; return Cell.unknown(); }
            Cell value = samples.sample(x, z);
            if (value == null) value = Cell.unknown();
            if (value.state == State.UNKNOWN) uncertain = true;
            cache.put(key(x, z), value);
            return value;
        }
        private Footprint footprint(int x, int z) {
            boolean missing = false, predicted = false;
            Integer height = null;
            for (int dx = 0; dx <= 1; dx++) {
                for (int dz = 0; dz <= 1; dz++) {
                    Cell c = cell(x + dx, z + dz);
                    if (c.state == State.LAND) return new Footprint(State.LAND, 0, false);
                    if (c.state == State.UNKNOWN) { missing = true; continue; }
                    if (height != null && c.surfaceY != height) return new Footprint(State.LAND, 0, false);
                    height = c.surfaceY;
                    predicted |= c.predicted();
                }
            }
            return new Footprint(missing ? State.UNKNOWN : State.WATER, height == null ? 0 : height, predicted);
        }
        private Exit exit(int x, int z, int surfaceY) {
            ArrayDeque<Node> queue = new ArrayDeque<>();
            Set<Long> visited = new HashSet<>();
            queue.add(new Node(x, z, false));
            visited.add(key(x, z));
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            while (!queue.isEmpty() && !exhausted()) {
                Node n = queue.removeFirst();
                expansions++;
                if (Math.max(Math.abs(n.x - x), Math.abs(n.z - z)) >= EXIT_DISTANCE) {
                    return new Exit(true, n.predicted);
                }
                for (int[] d : dirs) {
                    int nx = n.x + d[0], nz = n.z + d[1];
                    if (!visited.add(key(nx, nz))) continue;
                    Footprint f = footprint(nx, nz);
                    if (f.state == State.WATER && f.surfaceY == surfaceY) {
                        queue.addLast(new Node(nx, nz, n.predicted || f.predicted));
                    }
                }
            }
            return new Exit(false, false);
        }
    }
}
