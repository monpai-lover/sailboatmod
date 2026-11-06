package com.monpai.sailboatmod.transport.board;

import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftMission;
import com.monpai.sailboatmod.transport.air.fuel.RailFlightDuration;
import java.util.*;
import net.minecraft.world.phys.Vec3;

/** Pure ETA calculations from confirmed immutable observations, with no movement authority. */
public final class TownVoyageEta {
    public static final int MAX_PATH_POINTS = 8192, MAX_PROGRESS_SAMPLES = 16;
    public static final long MAX_SAMPLE_AGE_TICKS = 200;
    private static final double MAX_LENGTH_BLOCKS = 10_000_000, MAX_BLOCKS_PER_TICK = 100;
    public static final class GroundPath {
        private final String geometryId;
        private final List<Vec3> points;
        private final double[] suffix;
        private GroundPath(String geometryId, List<Vec3> points, double[] suffix) {
            this.geometryId = geometryId; this.points = List.copyOf(points); this.suffix = suffix;
        }
        public String geometryId() { return geometryId; }
        public List<Vec3> points() { return points; }
        public double lengthBlocks() { return suffix[0]; }
    }
    public record ProgressSample(String geometryId, long tick, double progressBlocks) { }
    private TownVoyageEta() { }
    /** Build once per geometry version; later heartbeats touch only their current segment and bounded samples. */
    public static Optional<GroundPath> preprocessGround(String geometryId, List<Vec3> points) {
        if (!geometry(geometryId) || points == null || points.size() < 2 || points.size() > MAX_PATH_POINTS) return Optional.empty();
        List<Vec3> copy = new ArrayList<>(points.size());
        for (Vec3 point : points) {
            if (!validPoint(point)) return Optional.empty();
            copy.add(point);
        }
        double[] suffix = new double[copy.size()];
        for (int i = copy.size() - 2; i >= 0; i--) {
            Vec3 from = copy.get(i), to = copy.get(i + 1);
            suffix[i] = suffix[i + 1] + Math.hypot(to.x - from.x, to.z - from.z);
            if (!Double.isFinite(suffix[i]) || suffix[i] > MAX_LENGTH_BLOCKS) return Optional.empty();
        }
        return Optional.of(new GroundPath(geometryId, copy, suffix));
    }
    /** Projection clamps to the current target segment; subsequent turns retain their full XZ arc length. */
    public static OptionalDouble remainingGroundBlocks(GroundPath path, int currentTargetIndex, Vec3 confirmedPosition) {
        if (path == null || !validPoint(confirmedPosition) || currentTargetIndex < 1 || currentTargetIndex >= path.points.size()) return OptionalDouble.empty();
        Vec3 from = path.points.get(currentTargetIndex - 1), to = path.points.get(currentTargetIndex);
        double dx = to.x - from.x, dz = to.z - from.z;
        double lengthSquared = dx * dx + dz * dz;
        double fraction = lengthSquared == 0 ? 1 : Math.clamp(((confirmedPosition.x - from.x) * dx + (confirmedPosition.z - from.z) * dz) / lengthSquared, 0, 1);
        double remaining = path.suffix[currentTargetIndex] + Math.sqrt(lengthSquared) * (1 - fraction);
        return Double.isFinite(remaining) && remaining >= 0 && remaining <= MAX_LENGTH_BLOCKS ? OptionalDouble.of(remaining) : OptionalDouble.empty();
    }
    /** A sample is real confirmed along-path progress in blocks, never a proposed target or commanded speed. */
    public static OptionalDouble effectiveSpeed(List<ProgressSample> samples, String geometryId, long nowTick, boolean paused) {
        if (paused || !geometry(geometryId) || nowTick < 0 || samples == null || samples.size() < 2 || samples.size() > MAX_PROGRESS_SAMPLES) return OptionalDouble.empty();
        ProgressSample previous = null;
        for (ProgressSample sample : samples) {
            if (sample == null || !geometryId.equals(sample.geometryId()) || sample.tick() < 0 || sample.tick() > nowTick
                    || nowTick - sample.tick() > MAX_SAMPLE_AGE_TICKS || !Double.isFinite(sample.progressBlocks())
                    || sample.progressBlocks() < 0 || sample.progressBlocks() > MAX_LENGTH_BLOCKS) return OptionalDouble.empty();
            if (previous != null) {
                if (sample.tick() <= previous.tick() || sample.progressBlocks() <= previous.progressBlocks()) return OptionalDouble.empty();
                double segmentSpeed = (sample.progressBlocks() - previous.progressBlocks()) / (sample.tick() - previous.tick());
                if (!Double.isFinite(segmentSpeed) || segmentSpeed <= 0 || segmentSpeed > MAX_BLOCKS_PER_TICK) return OptionalDouble.empty();
            }
            previous = sample;
        }
        ProgressSample first = samples.getFirst(), last = samples.getLast();
        double speed = (last.progressBlocks() - first.progressBlocks()) / (last.tick() - first.tick());
        return Double.isFinite(speed) && speed > 0 && speed <= MAX_BLOCKS_PER_TICK ? OptionalDouble.of(speed) : OptionalDouble.empty();
    }
    public static OptionalDouble metresPerSecondToBlocksPerTick(double metresPerSecond) {
        double speed = metresPerSecond / 20;
        return Double.isFinite(speed) && speed > 0 && speed <= MAX_BLOCKS_PER_TICK ? OptionalDouble.of(speed) : OptionalDouble.empty();
    }
    public static OptionalLong ground(GroundPath path, int currentTargetIndex, Vec3 confirmedPosition,
                                     List<ProgressSample> samples, long nowTick, boolean paused) {
        if (path == null) return OptionalLong.empty();
        OptionalDouble remaining = remainingGroundBlocks(path, currentTargetIndex, confirmedPosition);
        OptionalDouble speed = effectiveSpeed(samples, path.geometryId, nowTick, paused);
        if (remaining.isEmpty() || speed.isEmpty()) return OptionalLong.empty();
        for (ProgressSample sample : samples) if (sample.progressBlocks() > path.lengthBlocks()) return OptionalLong.empty();
        return ticks(remaining.getAsDouble() / speed.getAsDouble());
    }
    /** Reuses actual compiled rail speed/phase behavior, including taxi-in, plus confirmed stand evidence. */
    public static OptionalLong air(RailAircraftPath path, double confirmedDistance, int actualStandTicks) {
        if (path == null || actualStandTicks < 0 || actualStandTicks > RailAircraftMission.REQUIRED_STAND_TICKS
                || actualStandTicks > 0 && Double.compare(confirmedDistance, path.length()) != 0) return OptionalLong.empty();
        try {
            long movementTicks = RailFlightDuration.estimateTicks(path, confirmedDistance);
            long standTicks = Math.max(0, RailAircraftMission.REQUIRED_STAND_TICKS - actualStandTicks);
            return TownVoyageSnapshot.boundedEta(OptionalLong.of(Math.addExact(movementTicks, standTicks)));
        } catch (IllegalArgumentException | ArithmeticException ignored) { return OptionalLong.empty(); }
    }
    private static OptionalLong ticks(double value) {
        if (!Double.isFinite(value) || value <= 0 || value > TownVoyageSnapshot.MAX_ETA_TICKS) return OptionalLong.empty();
        return TownVoyageSnapshot.boundedEta(OptionalLong.of((long) Math.ceil(value)));
    }
    private static boolean geometry(String value) {
        if (value == null || value.isBlank() || value.length() > 256) return false;
        return value.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
    }
    private static boolean validPoint(Vec3 value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z)
                && Math.abs(value.x) <= 30_000_000 && Math.abs(value.z) <= 30_000_000;
    }
}
