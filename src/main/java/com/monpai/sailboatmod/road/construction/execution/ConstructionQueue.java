package com.monpai.sailboatmod.road.construction.execution;

import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class ConstructionQueue {
    public enum State { RUNNING, PAUSED, COMPLETED, CANCELLED }

    private final String roadId;
    private final List<BuildStep> steps;
    private int currentIndex;
    private State state;
    private final List<RollbackEntry> rollbackEntries = new ArrayList<>();
    private int rollbackIndex = -1; // -1 means not rolling back

    public record RollbackEntry(BlockPos pos, BlockState previousState) {}

    public ConstructionQueue(String roadId, List<BuildStep> steps) {
        this.roadId = roadId;
        this.steps = List.copyOf(steps);
        this.currentIndex = 0;
        this.state = State.RUNNING;
    }

    public boolean hasNext() {
        return currentIndex < steps.size() && state == State.RUNNING;
    }

    public BuildStep next() {
        if (!hasNext()) return null;
        return steps.get(currentIndex++);
    }

    public BuildStep peek() {
        if (!hasNext()) return null;
        return steps.get(currentIndex);
    }

    public void executeStep(BuildStep step, ServerLevel level) {
        BlockState prev = level.getBlockState(step.pos());
        rollbackEntries.add(new RollbackEntry(step.pos(), prev));
        level.setBlock(step.pos(), step.state(), 3);
    }

    public double progress() {
        if (steps.isEmpty()) return 1.0;
        return (double) currentIndex / steps.size();
    }

    public void pause() { if (state == State.RUNNING) state = State.PAUSED; }
    public void resume() { if (state == State.PAUSED) state = State.RUNNING; }
    public void cancel() { state = State.CANCELLED; }
    public void complete() { state = State.COMPLETED; }

    public void rollback(ServerLevel level) {
        for (int i = rollbackEntries.size() - 1; i >= 0; i--) {
            RollbackEntry entry = rollbackEntries.get(i);
            level.setBlock(entry.pos(), entry.previousState(), 3);
        }
    }

    public boolean isRollingBack() {
        return rollbackIndex >= 0;
    }

    public boolean progressiveRollback(ServerLevel level, int batchSize) {
        if (rollbackIndex < 0) {
            rollbackIndex = rollbackEntries.size() - 1;
            state = State.CANCELLED;
        }
        int count = 0;
        while (rollbackIndex >= 0 && count < batchSize) {
            RollbackEntry entry = rollbackEntries.get(rollbackIndex);
            level.setBlock(entry.pos(), entry.previousState(), 3);
            rollbackIndex--;
            count++;
        }
        return rollbackIndex < 0; // true when done
    }

    public String getRoadId() { return roadId; }
    public State getState() { return state; }
    public int getTotalSteps() { return steps.size(); }
    public int getCompletedSteps() { return currentIndex; }
}