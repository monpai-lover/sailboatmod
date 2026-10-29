package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.road.construction.execution.ConstructionQueue;
import com.monpai.sailboatmod.road.model.BuildStep;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Consumer;

/** Prediction is never executable: each real window warms up, compiles once, executes, and releases. */
public final class RoadPlannerStagedBuildQueue extends ConstructionQueue {
    private final RoadPlannerStagedPlan plan;
    private final RoadChunkWarmup.ChunkPort port;
    private final Function<RoadPlannerStagedPlan.Stage, List<BuildStep>> compile;
    private final List<BuildStep> executed = new ArrayList<>();
    private final List<net.minecraft.core.BlockPos> actualCenters = new ArrayList<>();
    private List<BuildStep> current = List.of();
    private RoadChunkWarmup warmup;
    private int stageIndex;
    private int stepIndex;
    private boolean compiled;
    private boolean windowReady;
    private boolean centersRemembered;
    private Consumer<List<BuildStep>> completedWindow = ignored -> { };

    public RoadPlannerStagedBuildQueue(String roadId, RoadPlannerStagedPlan plan,
                                      RoadChunkWarmup.ChunkPort port,
                                      Function<RoadPlannerStagedPlan.Stage, List<BuildStep>> compile) {
        super(roadId, List.of());
        this.plan = java.util.Objects.requireNonNull(plan);
        this.port = java.util.Objects.requireNonNull(port);
        this.compile = java.util.Objects.requireNonNull(compile);
    }

    public void advanceWindow() {
        if (getState() != State.RUNNING || finishedPlanning()) return;
        if (compiled && stepIndex >= current.size()) {
            if (!centersRemembered) { rememberCenters(); centersRemembered = true; }
            completedWindow.accept(current);
            releaseWindow();
            stageIndex++;
            current = List.of();
            stepIndex = 0;
            compiled = false;
            centersRemembered = false;
            if (finishedPlanning()) return;
        }
        if (windowReady && plan.stage(stageIndex).chunks().stream().anyMatch(key -> !port.isLoaded(key))) releaseWindow();
        if (warmup == null) warmup = new RoadChunkWarmup(plan.stage(stageIndex).chunks(), port, 8, 32);
        if (warmup.tick() != RoadChunkWarmup.Status.READY) return;
        windowReady = true;
        if (!compiled) {
            current = List.copyOf(compile.apply(plan.stage(stageIndex)));
            compiled = true;
        }
    }

    public boolean finishedPlanning() { return stageIndex >= plan.totalStages(); }
    public void onWindowCompleted(Consumer<List<BuildStep>> callback) { completedWindow = java.util.Objects.requireNonNull(callback); }
    public int completedStages() { return stageIndex; }
    public int totalStages() { return plan.totalStages(); }
    public List<net.minecraft.core.BlockPos> completedCenterPath() { return List.copyOf(actualCenters); }
    @Override public boolean hasNext() { return getState() == State.RUNNING && compiled && windowReady && stepIndex < current.size(); }
    @Override public BuildStep peek() { return hasNext() ? current.get(stepIndex) : null; }
    @Override public BuildStep next() {
        if (!hasNext()) return null;
        BuildStep step = current.get(stepIndex++);
        BuildStep ordered = new BuildStep(executed.size(), step.pos(), step.state(), step.phase());
        executed.add(ordered);
        return ordered;
    }
    @Override public int getCompletedSteps() { return executed.size(); }
    @Override public int getTotalSteps() {
        long estimate = (long) executed.size() + current.size() - stepIndex;
        long perStage = Math.max(1, executed.size() / Math.max(1, stageIndex + 1));
        estimate += perStage * Math.max(0, plan.totalStages() - stageIndex - 1);
        return (int) Math.min(Integer.MAX_VALUE, estimate);
    }
    @Override public double progress() {
        double local = current.isEmpty() ? 0 : (double) stepIndex / current.size();
        return Math.min(1, (stageIndex + local) / plan.totalStages());
    }
    /** Only already consumed real steps are retained for rollback and the authoritative completed-road registry. */
    @Override public List<BuildStep> getSteps() { return List.copyOf(executed); }
    @Override public void pause() { super.pause(); releaseWindow(); }
    @Override public void cancel() { super.cancel(); releaseWindow(); }
    @Override public void complete() { super.complete(); releaseWindow(); }
    private void releaseWindow() {
        windowReady = false;
        if (warmup != null) { warmup.release(); warmup = null; }
    }

    private void rememberCenters() {
        var stage = plan.stage(stageIndex);
        java.util.Map<Long, Integer> tops = new java.util.HashMap<>();
        for (BuildStep step : current) {
            if (step.state().isAir() || (step.phase() != com.monpai.sailboatmod.road.model.BuildPhase.SURFACE
                    && step.phase() != com.monpai.sailboatmod.road.model.BuildPhase.RAMP
                    && step.phase() != com.monpai.sailboatmod.road.model.BuildPhase.DECK)) continue;
            tops.merge(net.minecraft.world.level.ChunkPos.asLong(step.pos().getX(), step.pos().getZ()), step.pos().getY() + 1, Math::max);
        }
        double dx = (double) stage.coreTo().getX() - stage.coreFrom().getX();
        double dz = (double) stage.coreTo().getZ() - stage.coreFrom().getZ();
        int count = Math.max(1, (int) Math.ceil(Math.hypot(dx, dz)));
        for (int i = 0; i <= count; i++) {
            int x = (int) Math.round(stage.coreFrom().getX() + dx * i / count);
            int z = (int) Math.round(stage.coreFrom().getZ() + dz * i / count);
            if (!stage.ownsColumn(x, z)) continue;
            int y = tops.getOrDefault(net.minecraft.world.level.ChunkPos.asLong(x, z), stage.coreFrom().getY());
            var point = new net.minecraft.core.BlockPos(x, y, z);
            if (actualCenters.isEmpty() || !actualCenters.get(actualCenters.size() - 1).equals(point)) actualCenters.add(point);
        }
    }
}
