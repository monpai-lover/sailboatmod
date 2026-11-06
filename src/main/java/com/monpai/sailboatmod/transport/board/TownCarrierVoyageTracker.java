package com.monpai.sailboatmod.transport.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;

/** Observational per-carrier identity and bounded confirmed-progress cache; never owns navigation. */
public final class TownCarrierVoyageTracker {
    @FunctionalInterface public interface GeometryBudget { boolean reserve(String geometryId, int points); }
    private TownVoyageBinding binding = new TownVoyageBinding();
    private TownVoyageEta.GroundPath path;
    private String geometryId = UUID.randomUUID().toString();
    private boolean prepared, wasMoving;
    private long retryAt;
    private final List<TownVoyageEta.ProgressSample> samples = new ArrayList<>();

    public void started(boolean success, TownVoyageSnapshot.Endpoint source, TownVoyageSnapshot.Endpoint target) {
        if (!success) return;
        binding.startSuccessfulVoyage(source, target);
        invalidateGeometry();
    }
    public Optional<TownVoyageBinding.Leg> current() { return binding.current(); }
    /** A delayed automatic return is a completed arrival, even while the carrier's active flag holds it parked. */
    public Optional<TownVoyageBinding.Leg> observe(boolean active, boolean completedHold,
            TownVoyageSnapshot.Endpoint source, TownVoyageSnapshot.Endpoint target) {
        if (!active || completedHold) { finish(); return Optional.empty(); }
        if (binding.current().isEmpty()) binding.startSuccessfulVoyage(source, target);
        else binding.observeEndpoints(source, target);
        return binding.current();
    }
    public void finish() {
        boolean retained = binding.current().isPresent() || path != null || prepared || !samples.isEmpty() || retryAt != 0;
        binding.finish();
        if (retained) invalidateGeometry();
    }
    public void readFrom(CompoundTag tag) { binding = TownVoyageBinding.readFrom(tag); invalidateGeometry(); }
    public void writeTo(CompoundTag tag) { binding.writeTo(tag); }
    /** Call at actual geometry mutations, never by hashing/scanning the complete route on a heartbeat. */
    public void invalidateGeometry() {
        path = null; prepared = false; retryAt = 0;
        geometryId = UUID.randomUUID().toString();
        clearMotion();
    }
    /** Pause/resume/unload drops motion trust while preserving the actual voyage identity. */
    public void clearMotion() { samples.clear(); wasMoving = false; }
    public String geometryId() { return path == null ? "" : geometryId; }
    public OptionalLong ground(List<Vec3> route, int targetIndex, Vec3 position, long tick,
            boolean moving, GeometryBudget budget) {
        if (!prepared && tick >= retryAt) {
            if (route == null || route.size() < 2 || route.size() > TownVoyageEta.MAX_PATH_POINTS) prepared = true;
            else if (budget.reserve(geometryId, route.size())) {
                path = TownVoyageEta.preprocessGround(geometryId, route).orElse(null);
                prepared = true;
            } else retryAt = tick + TownVoyageEta.MAX_SAMPLE_AGE_TICKS;
        }
        if (!moving || path == null) { clearMotion(); return OptionalLong.empty(); }
        if (!wasMoving) samples.clear();
        wasMoving = true;
        OptionalDouble remaining = TownVoyageEta.remainingGroundBlocks(path, targetIndex, position);
        if (remaining.isEmpty()) { samples.clear(); return OptionalLong.empty(); }
        double progress = path.lengthBlocks() - remaining.getAsDouble();
        samples.removeIf(sample -> tick - sample.tick() > TownVoyageEta.MAX_SAMPLE_AGE_TICKS);
        if (!samples.isEmpty()) {
            var previous = samples.getLast();
            if (tick == previous.tick()) return TownVoyageEta.ground(path, targetIndex, position, samples, tick, false);
            if (tick < previous.tick() || progress <= previous.progressBlocks()) samples.clear();
        }
        samples.add(new TownVoyageEta.ProgressSample(geometryId, tick, progress));
        while (samples.size() > TownVoyageEta.MAX_PROGRESS_SAMPLES) samples.removeFirst();
        return TownVoyageEta.ground(path, targetIndex, position, samples, tick, false);
    }
}
